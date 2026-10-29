package nexus.integration;

import nexus.core.DeliveryStatus;
import nexus.core.NexusEndpoint;
import nexus.core.ReliabilityManager;
import nexus.core.SessionManager;
import nexus.core.SocketUdpTransport;
import nexus.core.UdpTransport;
import nexus.test.TestAssert;
import nexus.web.ApplicationMessage;
import nexus.web.MessageService;
import nexus.web.WebGateway;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LoopbackIntegrationTest {

    public static void runAllTests() throws Exception {
        testFullLoopbackExchange();
        testRetransmissionAndDuplicateSuppressionUnderPacketLoss();
        testRetryExhaustionUnderTotalNetworkLoss();
    }

    public static void testFullLoopbackExchange() throws Exception {
        boolean realLoopbackAvailable = isRealUdpLoopbackSupported();

        UdpTransport serverTransport;
        UdpTransport clientTransport;

        if (realLoopbackAvailable) {
            serverTransport = new SocketUdpTransport();
            clientTransport = new SocketUdpTransport();
        } else {
            VirtualLossyTransport s = new VirtualLossyTransport(5500);
            VirtualLossyTransport c = new VirtualLossyTransport(5501);
            s.setPairedTransport(c);
            c.setPairedTransport(s);
            serverTransport = s;
            clientTransport = c;
        }

        int serverUdpPort = 5500;
        int clientUdpPort = 5501;
        int webPort = 8884;

        NexusEndpoint serverEndpoint = new NexusEndpoint(serverTransport);
        serverEndpoint.setPeerAddress(new InetSocketAddress("127.0.0.1", clientUdpPort));

        CopyOnWriteArrayList<String> serverReceivedMessages = new CopyOnWriteArrayList<>();
        serverEndpoint.setIncomingMessageListener((session, text) -> serverReceivedMessages.add(text));
        serverEndpoint.start(serverUdpPort);

        NexusEndpoint clientEndpoint = new NexusEndpoint(clientTransport);
        clientEndpoint.setPeerAddress(new InetSocketAddress("127.0.0.1", serverUdpPort));
        clientEndpoint.start(clientUdpPort);

        MessageService messageService = new MessageService(clientEndpoint);
        WebGateway gateway = new WebGateway(webPort, messageService);

        ApplicationMessage appMsg = messageService.submitMessage("Test reliable UDP loopback exchange");
        TestAssert.assertTrue(
                appMsg.getStatus() == DeliveryStatus.UDP_SENT || appMsg.getStatus() == DeliveryStatus.PEER_ACKNOWLEDGED,
                "Message must immediately transition to UDP_SENT or PEER_ACKNOWLEDGED");

        long start = System.currentTimeMillis();
        while (appMsg.getStatus() != DeliveryStatus.PEER_ACKNOWLEDGED && (System.currentTimeMillis() - start < 3000)) {
            Thread.sleep(50);
        }

        TestAssert.assertEquals(DeliveryStatus.PEER_ACKNOWLEDGED, appMsg.getStatus(), "Message must be acknowledged over UDP");

        long recvStart = System.currentTimeMillis();
        while (serverReceivedMessages.isEmpty() && (System.currentTimeMillis() - recvStart < 2000)) {
            Thread.sleep(20);
        }

        TestAssert.assertEquals(1, serverReceivedMessages.size(), "Server must have received exactly 1 message");
        TestAssert.assertEquals("Test reliable UDP loopback exchange", serverReceivedMessages.get(0), "Received text must match");

        clientEndpoint.stop();
        serverEndpoint.stop();
        gateway.stop();
    }

    public static void testRetransmissionAndDuplicateSuppressionUnderPacketLoss() throws Exception {
        VirtualLossyTransport clientTransport = new VirtualLossyTransport(6001);
        VirtualLossyTransport serverTransport = new VirtualLossyTransport(6002);
        clientTransport.setPairedTransport(serverTransport);
        serverTransport.setPairedTransport(clientTransport);

        ReliabilityManager clientReliability = new ReliabilityManager(clientTransport, 80, 3, 20);
        SessionManager clientSessionManager = new SessionManager(5000, 10);
        NexusEndpoint clientEndpoint = new NexusEndpoint(clientTransport, clientReliability, clientSessionManager);
        clientEndpoint.setPeerAddress(new InetSocketAddress("127.0.0.1", 6002));
        clientEndpoint.start(6001);

        ReliabilityManager serverReliability = new ReliabilityManager(serverTransport, 80, 3, 20);
        SessionManager serverSessionManager = new SessionManager(5000, 10);
        NexusEndpoint serverEndpoint = new NexusEndpoint(serverTransport, serverReliability, serverSessionManager);
        serverEndpoint.setPeerAddress(new InetSocketAddress("127.0.0.1", 6001));

        CopyOnWriteArrayList<String> serverReceived = new CopyOnWriteArrayList<>();
        serverEndpoint.setIncomingMessageListener((s, text) -> serverReceived.add(text));
        serverEndpoint.start(6002);

        clientTransport.setDropFirstNPackets(1);

        AtomicReference<DeliveryStatus> statusRef = new AtomicReference<>();
        CompletableFuture<DeliveryStatus> future = clientEndpoint.sendMessage(
                "Message requiring UDP retransmission", statusRef::set);

        DeliveryStatus status = future.get(3, TimeUnit.SECONDS);

        TestAssert.assertEquals(DeliveryStatus.PEER_ACKNOWLEDGED, status, "Message must be acknowledged after retransmission");
        TestAssert.assertTrue(clientTransport.getPacketsSentCount() >= 2, "Transport must have sent at least 2 packets (original + retransmit)");
        TestAssert.assertEquals(1, clientTransport.getPacketsDroppedCount(), "Exactly 1 packet was dropped");

        long waitStart = System.currentTimeMillis();
        while (serverReceived.isEmpty() && (System.currentTimeMillis() - waitStart < 2000)) {
            Thread.sleep(20);
        }

        TestAssert.assertEquals(1, serverReceived.size(), "Server must deliver message exactly once despite retransmission");
        TestAssert.assertEquals("Message requiring UDP retransmission", serverReceived.get(0), "Delivered content must match");

        clientEndpoint.stop();
        serverEndpoint.stop();
    }

    public static void testRetryExhaustionUnderTotalNetworkLoss() throws Exception {
        VirtualLossyTransport clientTransport = new VirtualLossyTransport(7001);
        VirtualLossyTransport serverTransport = new VirtualLossyTransport(7002);
        clientTransport.setPairedTransport(serverTransport);
        serverTransport.setPairedTransport(clientTransport);

        ReliabilityManager clientReliability = new ReliabilityManager(clientTransport, 60, 2, 20);
        SessionManager clientSessionManager = new SessionManager(5000, 10);
        NexusEndpoint clientEndpoint = new NexusEndpoint(clientTransport, clientReliability, clientSessionManager);
        clientEndpoint.setPeerAddress(new InetSocketAddress("127.0.0.1", 7002));
        clientEndpoint.start(7001);

        clientTransport.setDropAll(true);

        AtomicReference<DeliveryStatus> statusRef = new AtomicReference<>();
        CompletableFuture<DeliveryStatus> future = clientEndpoint.sendMessage(
                "Doomed message", statusRef::set);

        DeliveryStatus status = future.get(2, TimeUnit.SECONDS);

        TestAssert.assertEquals(DeliveryStatus.FAILED, status, "Message must fail after retry exhaustion");
        TestAssert.assertEquals(DeliveryStatus.FAILED, statusRef.get(), "Status callback must receive FAILED");
        TestAssert.assertEquals(0, clientReliability.getPendingCount(), "Pending queue must be empty");

        clientEndpoint.stop();
    }

    private static boolean isRealUdpLoopbackSupported() {
        try (DatagramSocket s1 = new DatagramSocket();
             DatagramSocket s2 = new DatagramSocket()) {
            s2.setSoTimeout(100);
            byte[] probe = new byte[]{1, 2, 3};
            DatagramPacket sendP = new DatagramPacket(probe, probe.length, InetAddress.getByName("127.0.0.1"), s2.getLocalPort());
            s1.send(sendP);
            DatagramPacket recvP = new DatagramPacket(new byte[10], 10);
            s2.receive(recvP);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

