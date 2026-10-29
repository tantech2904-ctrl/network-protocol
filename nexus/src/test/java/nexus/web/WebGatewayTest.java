package nexus.web;

import nexus.core.DeliveryStatus;
import nexus.core.NexusEndpoint;
import nexus.core.SocketUdpTransport;
import nexus.test.TestAssert;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class WebGatewayTest {

    public static void runAllTests() throws Exception {
        testStaticResourcesLoadLocally();
        testStatusApiReturnsUp();
        testValidMessageSubmissionViaHttp();
        testEmptyAndOversizedMessageRejection();
        testSafeJsonEscaping();
        testWebSocketAcceptCalculation();
        testMessageServiceDeliveryUpdate();
        testNoDuplicateHistoryEntries();
    }

    public static void testStaticResourcesLoadLocally() throws Exception {
        SocketUdpTransport transport = new SocketUdpTransport();
        NexusEndpoint endpoint = new NexusEndpoint(transport);
        MessageService service = new MessageService(endpoint);
        WebGateway gateway = new WebGateway(8881, service);

        ByteArrayOutputStream htmlOut = new ByteArrayOutputStream();
        gateway.handleHttpRequest("GET", "/", new HashMap<>(), new BufferedReader(new StringReader("")), htmlOut);
        String html = htmlOut.toString(StandardCharsets.UTF_8);
        TestAssert.assertTrue(html.contains("<title>NEXUS</title>"), "index.html should contain title");
        TestAssert.assertTrue(html.contains("NEXUS Offline Messaging"), "index.html should contain description");

        ByteArrayOutputStream cssOut = new ByteArrayOutputStream();
        gateway.handleHttpRequest("GET", "/styles.css", new HashMap<>(), new BufferedReader(new StringReader("")), cssOut);
        String css = cssOut.toString(StandardCharsets.UTF_8);
        TestAssert.assertTrue(css.contains("--accent-blue"), "styles.css should contain styling variables");

        ByteArrayOutputStream jsOut = new ByteArrayOutputStream();
        gateway.handleHttpRequest("GET", "/app.js", new HashMap<>(), new BufferedReader(new StringReader("")), jsOut);
        String js = jsOut.toString(StandardCharsets.UTF_8);
        TestAssert.assertTrue(js.contains("connectWebSocket"), "app.js should contain WebSocket logic");

        transport.close();
    }

    public static void testStatusApiReturnsUp() throws Exception {
        SocketUdpTransport transport = new SocketUdpTransport();
        NexusEndpoint endpoint = new NexusEndpoint(transport);
        MessageService service = new MessageService(endpoint);
        WebGateway gateway = new WebGateway(8882, service);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gateway.handleHttpRequest("GET", "/api/status", new HashMap<>(), new BufferedReader(new StringReader("")), out);
        String json = out.toString(StandardCharsets.UTF_8);
        TestAssert.assertTrue(json.contains("\"status\":\"UP\""), "Status API should report UP");

        transport.close();
    }

    public static void testValidMessageSubmissionViaHttp() throws Exception {
        SocketUdpTransport transport = new SocketUdpTransport();
        NexusEndpoint endpoint = new NexusEndpoint(transport);
        endpoint.setPeerAddress(new InetSocketAddress("127.0.0.1", 9091));
        endpoint.start(9090);

        MessageService service = new MessageService(endpoint);
        WebGateway gateway = new WebGateway(8883, service);

        String body = "{\"content\":\"Hello from WebGatewayTest\"}";
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", String.valueOf(body.length()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gateway.handleHttpRequest("POST", "/api/messages", headers, new BufferedReader(new StringReader(body)), out);

        String response = out.toString(StandardCharsets.UTF_8);
        TestAssert.assertTrue(response.contains("200 OK"), "POST /api/messages should return 200 OK");
        TestAssert.assertEquals(1, service.getHistory().size(), "History should contain 1 message");
        TestAssert.assertEquals("Hello from WebGatewayTest", service.getHistory().get(0).getContent(), "Message content should match");

        endpoint.stop();
    }

    public static void testEmptyAndOversizedMessageRejection() {
        SocketUdpTransport transport = new SocketUdpTransport();
        NexusEndpoint endpoint = new NexusEndpoint(transport);
        MessageService service = new MessageService(endpoint);

        boolean emptyCaught = false;
        try {
            service.submitMessage("   ");
        } catch (IllegalArgumentException e) {
            emptyCaught = true;
        } catch (Exception ignored) {
        }
        TestAssert.assertTrue(emptyCaught, "Should reject empty message");

        boolean oversizedCaught = false;
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1005; i++) {
                sb.append("A");
            }
            service.submitMessage(sb.toString());
        } catch (IllegalArgumentException e) {
            oversizedCaught = true;
        } catch (Exception ignored) {
        }
        TestAssert.assertTrue(oversizedCaught, "Should reject oversized message (>1000 characters)");

        transport.close();
    }

    public static void testSafeJsonEscaping() {
        String unsafe = "<script>alert('xss')</script>\\\"\n\r\t";
        String escaped = ApplicationMessage.escapeJson(unsafe);
        TestAssert.assertTrue(escaped.contains("\\\""), "Quotes must be escaped");
        TestAssert.assertTrue(escaped.contains("\\\\"), "Backslashes must be escaped");
        TestAssert.assertTrue(escaped.contains("\\n"), "Newlines must be escaped");
        TestAssert.assertTrue(escaped.contains("\\r"), "Carriage returns must be escaped");
        TestAssert.assertTrue(escaped.contains("\\t"), "Tabs must be escaped");
    }

    public static void testWebSocketAcceptCalculation() {
        String testKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String accept = WebGateway.createWebSocketAccept(testKey);
        TestAssert.assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept, "RFC 6455 test vector should produce expected accept key");
    }

    public static void testMessageServiceDeliveryUpdate() {
        SocketUdpTransport transport = new SocketUdpTransport();
        NexusEndpoint endpoint = new NexusEndpoint(transport);
        MessageService service = new MessageService(endpoint);

        ApplicationMessage msg = new ApplicationMessage("user", "Testing delivery state updates");
        TestAssert.assertEquals(DeliveryStatus.GATEWAY_ACCEPTED, msg.getStatus(), "Initial state must be GATEWAY_ACCEPTED");

        msg.setStatus(DeliveryStatus.UDP_SENT);
        TestAssert.assertEquals(DeliveryStatus.UDP_SENT, msg.getStatus(), "State must advance to UDP_SENT");

        msg.setStatus(DeliveryStatus.PEER_ACKNOWLEDGED);
        TestAssert.assertEquals(DeliveryStatus.PEER_ACKNOWLEDGED, msg.getStatus(), "State must advance to PEER_ACKNOWLEDGED");

        transport.close();
    }

    public static void testNoDuplicateHistoryEntries() {
        SocketUdpTransport transport = new SocketUdpTransport();
        NexusEndpoint endpoint = new NexusEndpoint(transport);
        MessageService service = new MessageService(endpoint, 5);

        for (int i = 0; i < 8; i++) {
            service.onPeerMessageReceived(null, "Msg " + i);
        }

        TestAssert.assertEquals(5, service.getHistory().size(), "History must be bounded at max 5 messages");
        TestAssert.assertEquals("Msg 7", service.getHistory().get(4).getContent(), "Last message should be Msg 7");

        transport.close();
    }
}

