package nexus.web;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebGateway {
    private final int port;
    private final MessageService messageService;
    private ServerSocket serverSocket;
    private ExecutorService connectionPool;
    private Thread acceptThread;
    private volatile boolean running;

    public WebGateway(int port, MessageService messageService) {
        this.port = port;
        this.messageService = messageService;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));

        connectionPool = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "Nexus-WebGateway-Worker");
            thread.setDaemon(true);
            return thread;
        });

        running = true;
        acceptThread = new Thread(this::acceptLoop, "Nexus-WebGateway-Acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                connectionPool.execute(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (!running) {
                    break;
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }

            String method = parts[0].toUpperCase();
            String path = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon != -1) {
                    String headerName = line.substring(0, colon).trim().toLowerCase();
                    String headerVal = line.substring(colon + 1).trim();
                    headers.put(headerName, headerVal);
                }
            }

            if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                handleWebSocketUpgrade(socket, headers, out);
                return;
            }

            handleHttpRequest(method, path, headers, reader, out);
            socket.close();

        } catch (Exception ignored) {
            try {
                socket.close();
            } catch (IOException ignored2) {
            }
        }
    }

    private void handleWebSocketUpgrade(Socket socket, Map<String, String> headers, OutputStream out) throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            sendHttpResponse(out, 400, "Bad Request", "text/plain", "Missing Sec-WebSocket-Key".getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }

        String accept = createWebSocketAccept(key);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();

        final WebSocketSession[] sessionRef = new WebSocketSession[1];
        WebSocketSession session = new WebSocketSession(
                socket,
                msg -> handleWebSocketMessage(sessionRef[0], msg),
                closedSession -> messageService.unregisterSession(closedSession));
        sessionRef[0] = session;

        messageService.registerSession(session);
        session.startReading();
    }

    void handleWebSocketMessage(String rawJson) {
        handleWebSocketMessage(null, rawJson);
    }

    void handleWebSocketMessage(WebSocketSession session, String rawJson) {
        try {
            String type = extractJsonField(rawJson, "type");
            String action = extractJsonField(rawJson, "action");
            if ("SET_PROFILE".equalsIgnoreCase(type) || "SET_PROFILE".equalsIgnoreCase(action)) {
                String senderId = extractJsonField(rawJson, "senderId");
                String name = extractJsonField(rawJson, "name");
                String color = extractJsonField(rawJson, "color");
                if (session != null && senderId != null) {
                    messageService.updateClientProfile(session, senderId, name, color);
                }
                return;
            }

            String content = extractJsonField(rawJson, "content");
            if (content == null || content.isEmpty()) {
                content = extractJsonField(rawJson, "text");
            }
            if (content != null && !content.isEmpty()) {
                String senderId = extractJsonField(rawJson, "senderId");
                String senderName = extractJsonField(rawJson, "senderName");
                String senderColor = extractJsonField(rawJson, "senderColor");
                if (senderId != null && !senderId.isEmpty()) {
                    messageService.submitMessage(senderId, senderName, senderColor, content);
                } else {
                    messageService.submitMessage(content);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static String extractJsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) {
            return null;
        }
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon == -1) {
            return null;
        }
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart == -1) {
            return null;
        }
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd == -1) {
            return null;
        }
        return json.substring(quoteStart + 1, quoteEnd);
    }

    public static String createWebSocketAccept(String key) {
        try {
            String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((key + magic).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    void handleHttpRequest(
            String method,
            String path,
            Map<String, String> headers,
            BufferedReader reader,
            OutputStream out) throws IOException {

        if ("/".equals(path) || "/index.html".equals(path)) {
            byte[] html = loadStaticResource("index.html");
            sendHttpResponse(out, 200, "OK", "text/html; charset=UTF-8", html);
        } else if ("/styles.css".equals(path)) {
            byte[] css = loadStaticResource("styles.css");
            sendHttpResponse(out, 200, "OK", "text/css; charset=UTF-8", css);
        } else if ("/app.js".equals(path)) {
            byte[] js = loadStaticResource("app.js");
            sendHttpResponse(out, 200, "OK", "application/javascript; charset=UTF-8", js);
        } else if ("/api/status".equals(path)) {
            String status = "{\"status\":\"UP\",\"port\":" + getPort() + ",\"connectedClients\":" + messageService.getConnectedClientCount() + "}";
            sendHttpResponse(out, 200, "OK", "application/json; charset=UTF-8", status.getBytes(StandardCharsets.UTF_8));
        } else if ("/api/messages".equals(path) && "POST".equalsIgnoreCase(method)) {
            int length = 0;
            String cl = headers.get("content-length");
            if (cl != null) {
                try {
                    length = Integer.parseInt(cl);
                } catch (NumberFormatException ignored) {
                }
            }
            char[] bodyChars = new char[length];
            if (length > 0) {
                reader.read(bodyChars, 0, length);
            }
            String body = new String(bodyChars);
            String content = extractJsonField(body, "content");
            if (content != null && !content.isEmpty()) {
                messageService.submitMessage(content);
                sendHttpResponse(out, 200, "OK", "application/json; charset=UTF-8", "{\"result\":\"accepted\"}".getBytes(StandardCharsets.UTF_8));
            } else {
                sendHttpResponse(out, 400, "Bad Request", "application/json; charset=UTF-8", "{\"error\":\"Empty content\"}".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            byte[] notFound = "<html><body><h1>404 Not Found</h1></body></html>".getBytes(StandardCharsets.UTF_8);
            sendHttpResponse(out, 404, "Not Found", "text/html; charset=UTF-8", notFound);
        }
    }

    private void sendHttpResponse(
            OutputStream out,
            int statusCode,
            String statusMessage,
            String contentType,
            byte[] body) throws IOException {
        String responseHeaders = "HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + (body != null ? body.length : 0) + "\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    public byte[] loadStaticResource(String resourceName) throws IOException {
        InputStream is = getClass().getResourceAsStream("/static/" + resourceName);
        if (is != null) {
            try (is) {
                return readAllBytes(is);
            }
        }

        File file = new File("nexus/src/main/resources/static/" + resourceName);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                return readAllBytes(fis);
            }
        }

        File fileDirect = new File("src/main/resources/static/" + resourceName);
        if (fileDirect.exists()) {
            try (FileInputStream fis = new FileInputStream(fileDirect)) {
                return readAllBytes(fis);
            }
        }

        return ("Resource " + resourceName + " not found").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (connectionPool != null) {
            connectionPool.shutdownNow();
        }
    }
}

