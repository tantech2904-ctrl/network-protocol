package nexus.web;

import nexus.core.DeliveryStatus;
import nexus.core.FileTransferManager;
import nexus.core.NexusEndpoint;
import nexus.core.ProtocolTelemetry;
import nexus.core.Session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MessageService {
    private final NexusEndpoint nexusEndpoint;
    private final List<ApplicationMessage> history;
    private final Map<String, ApplicationMessage> messagesById;
    private final Set<WebSocketSession> sessions;
    private final Map<String, InetSocketAddress> knownPeers;
    private final Map<String, String> peerColors;
    private final Map<String, ClientProfile> clientProfiles;
    private final Map<WebSocketSession, String> sessionClientIds;
    private final int maxHistorySize;
    private Consumer<String> simulationSender;

    public MessageService(NexusEndpoint nexusEndpoint) {
        this(nexusEndpoint, 150);
    }

    public MessageService(NexusEndpoint nexusEndpoint, int maxHistorySize) {
        this.nexusEndpoint = nexusEndpoint;
        this.maxHistorySize = maxHistorySize;
        this.history = new CopyOnWriteArrayList<>();
        this.messagesById = new ConcurrentHashMap<>();
        this.sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.knownPeers = new ConcurrentHashMap<>();
        this.peerColors = new ConcurrentHashMap<>();
        this.clientProfiles = new ConcurrentHashMap<>();
        this.sessionClientIds = new ConcurrentHashMap<>();

        registerPeer("Alice", new InetSocketAddress("127.0.0.1", 5000), "#AF52DE");
        registerPeer("Bob", new InetSocketAddress("127.0.0.1", 5002), "#34C759");
        registerPeer("Sensor-Alpha", new InetSocketAddress("127.0.0.1", 5003), "#FF9500");

        if (this.nexusEndpoint != null) {
            this.nexusEndpoint.setIncomingMessageListener(this::onPeerMessageReceived);
            this.nexusEndpoint.setIncomingFileListener(this::onPeerFileReceived);
            this.nexusEndpoint.setTelemetryListener(this::onTelemetry);
        }
    }

    public void registerPeer(String name, InetSocketAddress address, String color) {
        knownPeers.put(name.toLowerCase(), address);
        peerColors.put(name.toLowerCase(), color);
    }

    public void setSimulationSender(Consumer<String> sender) {
        this.simulationSender = sender;
    }

    public void updateClientProfile(WebSocketSession session, String senderId, String name, String color) {
        if (senderId == null || senderId.trim().isEmpty()) {
            return;
        }
        String cleanId = senderId.trim();
        sessionClientIds.put(session, cleanId);

        ClientProfile profile = clientProfiles.computeIfAbsent(cleanId, id -> new ClientProfile(id, name, color));
        profile.setName(name);
        profile.setColor(color);

        broadcastRoster();
    }

    public ApplicationMessage submitMessage(String rawContent) throws IOException {
        return submitMessage("local-user", "You", "#007AFF", rawContent);
    }

    public ApplicationMessage submitMessage(
            String senderId,
            String senderName,
            String senderColor,
            String rawContent) throws IOException {
        if (rawContent == null) {
            throw new IllegalArgumentException("Message content cannot be null");
        }
        String trimmed = rawContent.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Message content cannot be empty");
        }

        if ("/clear".equalsIgnoreCase(trimmed)) {
            history.clear();
            messagesById.clear();
            broadcast("{\"type\":\"CLEAR_HISTORY\"}");
            return new ApplicationMessage("system", "History cleared.");
        }

        if ("/help".equalsIgnoreCase(trimmed)) {
            return sendHelpMessage();
        }

        if ("/stats".equalsIgnoreCase(trimmed)) {
            return sendStatsMessage();
        }

        if (trimmed.startsWith("/image")) {
            return handleImageCommand(senderId, senderName, senderColor, trimmed);
        }

        if (trimmed.startsWith("/sim ")) {
            return handleSimCommand(trimmed);
        }

        InetSocketAddress targetPeer = nexusEndpoint.getPeerAddress();
        String targetPeerName = "Peer";

        if (trimmed.startsWith("/peer ")) {
            String[] parts = trimmed.split(" ", 3);
            if (parts.length >= 3) {
                String pName = parts[1].toLowerCase();
                InetSocketAddress customTarget = knownPeers.get(pName);
                if (customTarget != null) {
                    targetPeer = customTarget;
                    targetPeerName = parts[1];
                    trimmed = parts[2].trim();
                }
            }
        }

        if (trimmed.startsWith("/drop ")) {
            trimmed = trimmed.substring(6).trim();
            if (nexusEndpoint != null) {
                nexusEndpoint.simulateDropNext(1);
            }
        } else if (trimmed.startsWith("/fail ")) {
            trimmed = trimmed.substring(6).trim();
            if (nexusEndpoint != null) {
                nexusEndpoint.simulateDropAll(true);
                Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                    nexusEndpoint.simulateDropAll(false);
                }, 4000, TimeUnit.MILLISECONDS);
            }
        }

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Message content cannot be empty");
        }
        if (trimmed.length() > 1000) {
            throw new IllegalArgumentException("Message length exceeds maximum allowed 1000 characters");
        }

        ApplicationMessage message = new ApplicationMessage(senderId, senderName, senderColor, trimmed);
        message.setPeerName(senderName);
        message.setPeerColor(senderColor);
        addMessage(message);

        broadcast("{\"type\":\"MESSAGE_ACCEPTED\",\"message\":" + message.toJson() + "}");

        String wirePayload = (senderId != null && !"local-user".equals(senderId))
                ? "[" + senderName + "|" + senderColor + "|" + senderId + "]" + trimmed
                : trimmed;
        nexusEndpoint.sendMessageTo(targetPeer, wirePayload, status -> onDeliveryStatusChanged(message.getId(), status));

        return message;
    }

    private ApplicationMessage handleImageCommand(
            String senderId,
            String senderName,
            String senderColor,
            String command) throws IOException {
        short ttl = 15;
        String[] parts = command.split(" ");
        if (parts.length > 1) {
            try {
                ttl = Short.parseShort(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }

        byte[] sampleImage = SampleImageGenerator.createSampleNexusImage();
        InetSocketAddress target = nexusEndpoint.getPeerAddress();

        ApplicationMessage message = new ApplicationMessage(senderId, senderName, senderColor, "Sent photo (" + sampleImage.length + " bytes over UDP)");
        message.setMessageType("IMAGE");
        message.setMediaData("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(sampleImage));
        message.setTtlSeconds(ttl);
        addMessage(message);

        broadcast("{\"type\":\"MESSAGE_ACCEPTED\",\"message\":" + message.toJson() + "}");

        nexusEndpoint.sendFileTo(target, sampleImage, "image/png", ttl, status -> onDeliveryStatusChanged(message.getId(), status));

        return message;
    }

    private ApplicationMessage handleSimCommand(String command) {
        String peerTarget = command.substring(5).trim();
        String messageText = "Standard sensor status report";
        int spaceIdx = peerTarget.indexOf(' ');
        if (spaceIdx != -1) {
            messageText = peerTarget.substring(spaceIdx + 1).trim();
            peerTarget = peerTarget.substring(0, spaceIdx).trim();
        }

        if (simulationSender != null) {
            simulationSender.accept(peerTarget + ":" + messageText);
        }

        ApplicationMessage sys = new ApplicationMessage("system", "Simulated transmission requested from: " + peerTarget);
        sys.setMessageType("SYSTEM");
        addMessage(sys);
        broadcast("{\"type\":\"PEER_MESSAGE\",\"message\":" + sys.toJson() + "}");
        return sys;
    }

    private ApplicationMessage sendHelpMessage() {
        String help = "NEXUS Slash Commands:\n"
                + "• /drop <text> : Drops 1st UDP packet (shows live retransmission)\n"
                + "• /fail <text> : Drops all packets (shows retry exhaustion)\n"
                + "• /peer <alice|bob|sensor> <text> : Targets specific peer\n"
                + "• /sim <alice|bob|sensor> [msg] : Triggers simulated peer transmission\n"
                + "• /image [ttlSeconds] : Sends chunked image over UDP with ephemeral timer\n"
                + "• /stats : Shows protocol and connection statistics\n"
                + "• /clear : Clears chat history";
        ApplicationMessage msg = new ApplicationMessage("system", help);
        msg.setMessageType("SYSTEM");
        addMessage(msg);
        broadcast("{\"type\":\"PEER_MESSAGE\",\"message\":" + msg.toJson() + "}");
        return msg;
    }

    private ApplicationMessage sendStatsMessage() {
        int sessionsCount = nexusEndpoint.getSessionManager().getActiveSessionCount();
        int clientCount = sessions.size();
        String stats = "NEXUS System Stats:\n"
                + "• Active UDP Sessions: " + sessionsCount + "\n"
                + "• Connected Browsers: " + clientCount + "\n"
                + "• Stored Messages: " + history.size() + "\n"
                + "• UDP Client Port: " + nexusEndpoint.getLocalPort() + "\n"
                + "• Default Target Peer: " + (nexusEndpoint.getPeerAddress() != null ? nexusEndpoint.getPeerAddress().toString() : "None");
        ApplicationMessage msg = new ApplicationMessage("system", stats);
        msg.setMessageType("SYSTEM");
        addMessage(msg);
        broadcast("{\"type\":\"PEER_MESSAGE\",\"message\":" + msg.toJson() + "}");
        return msg;
    }

    public void onPeerMessageReceived(Session session, String rawText) {
        String pName = resolvePeerName(session);
        String pColor = resolvePeerColor(session);
        String senderId = session != null ? "udp-" + session.getSessionId() : "udp-unknown";
        String content = rawText;

        if (rawText != null && rawText.startsWith("[")) {
            int closeBracket = rawText.indexOf(']');
            if (closeBracket != -1) {
                String meta = rawText.substring(1, closeBracket);
                String[] parts = meta.split("\\|", 3);
                if (parts.length >= 2) {
                    pName = parts[0];
                    pColor = parts[1];
                    if (parts.length >= 3) {
                        senderId = parts[2];
                    }
                    content = rawText.substring(closeBracket + 1);
                }
            }
        }

        if (session != null) {
            session.setPeerName(pName);
            session.setPeerColor(pColor);
        }

        ApplicationMessage message = new ApplicationMessage(
                java.util.UUID.randomUUID().toString(),
                "peer",
                senderId,
                content,
                System.currentTimeMillis(),
                DeliveryStatus.DELIVERED_TO_APP,
                0,
                0,
                pName,
                pColor,
                "TEXT",
                null,
                0);

        addMessage(message);
        broadcast("{\"type\":\"PEER_MESSAGE\",\"message\":" + message.toJson() + "}");
    }

    public void onPeerFileReceived(Session session, FileTransferManager.CompletedFile file) {
        String pName = resolvePeerName(session);
        String pColor = resolvePeerColor(session);
        String senderId = session != null ? "udp-" + session.getSessionId() : "udp-unknown";

        if (session != null) {
            session.setPeerName(pName);
            session.setPeerColor(pColor);
        }

        ApplicationMessage message = new ApplicationMessage(
                java.util.UUID.randomUUID().toString(),
                "peer",
                senderId,
                "Received photo (" + file.getTotalBytes() + " bytes over UDP)",
                System.currentTimeMillis(),
                DeliveryStatus.DELIVERED_TO_APP,
                0,
                0,
                pName,
                pColor,
                "IMAGE",
                file.getDataUri(),
                file.getTtlSeconds());

        addMessage(message);
        broadcast("{\"type\":\"PEER_MESSAGE\",\"message\":" + message.toJson() + "}");
    }

    public void onTelemetry(ProtocolTelemetry telemetry) {
        broadcast("{\"type\":\"TELEMETRY\",\"telemetry\":" + telemetry.toJson() + "}");
    }

    private String resolvePeerName(Session session) {
        if (session == null || session.getRemoteAddress() == null) {
            return "Peer";
        }
        int port = session.getRemoteAddress().getPort();
        if (port == 5000) {
            return "Alice";
        }
        if (port == 5002) {
            return "Bob";
        }
        if (port == 5003) {
            return "Sensor-Alpha";
        }
        return "Peer-" + port;
    }

    private String resolvePeerColor(Session session) {
        if (session == null || session.getRemoteAddress() == null) {
            return "#8E8E93";
        }
        int port = session.getRemoteAddress().getPort();
        if (port == 5000) {
            return "#AF52DE";
        }
        if (port == 5002) {
            return "#34C759";
        }
        if (port == 5003) {
            return "#FF9500";
        }
        return "#007AFF";
    }

    private void onDeliveryStatusChanged(String messageId, DeliveryStatus status) {
        ApplicationMessage message = messagesById.get(messageId);
        if (message != null) {
            message.setStatus(status);
            if (status == DeliveryStatus.RETRANSMITTING) {
                message.incrementRetryCount();
            }
            broadcast("{\"type\":\"STATUS_UPDATED\",\"messageId\":\"" + messageId + "\",\"status\":\"" + status.name() + "\",\"retryCount\":" + message.getRetryCount() + "}");
        }
    }

    private void addMessage(ApplicationMessage message) {
        messagesById.put(message.getId(), message);
        history.add(message);
        if (history.size() > maxHistorySize) {
            ApplicationMessage removed = history.remove(0);
            if (removed != null) {
                messagesById.remove(removed.getId());
            }
        }
    }

    public void registerSession(WebSocketSession session) {
        sessions.add(session);
        sendHistoryTo(session);
        sendStatusTo(session);
        sendRosterTo(session);
    }

    public void unregisterSession(WebSocketSession session) {
        sessions.remove(session);
        String cid = sessionClientIds.remove(session);
        if (cid != null && !sessionClientIds.containsValue(cid)) {
            clientProfiles.remove(cid);
            broadcastRoster();
        }
    }

    public void broadcast(String json) {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.sendText(json);
            }
        }
    }

    public void broadcastRoster() {
        String rosterJson = buildRosterJson();
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.sendText(rosterJson);
            }
        }
    }

    private void sendRosterTo(WebSocketSession session) {
        session.sendText(buildRosterJson());
    }

    private String buildRosterJson() {
        StringBuilder sb = new StringBuilder("{\"type\":\"ROSTER_UPDATED\",\"peers\":[");
        int count = 0;
        for (ClientProfile p : clientProfiles.values()) {
            if (count > 0) {
                sb.append(",");
            }
            sb.append(p.toJson());
            count++;
        }
        sb.append("]}");
        return sb.toString();
    }

    private void sendHistoryTo(WebSocketSession session) {
        StringBuilder sb = new StringBuilder("{\"type\":\"HISTORY\",\"messages\":[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(history.get(i).toJson());
        }
        sb.append("]}");
        session.sendText(sb.toString());
    }

    public void sendStatusTo(WebSocketSession session) {
        String peerAddr = nexusEndpoint.getPeerAddress() != null ? nexusEndpoint.getPeerAddress().toString() : "Not configured";
        String statusJson = "{\"type\":\"SYSTEM_STATUS\","
                + "\"endpointRunning\":" + nexusEndpoint.isRunning() + ","
                + "\"localUdpPort\":" + nexusEndpoint.getLocalPort() + ","
                + "\"peerAddress\":\"" + ApplicationMessage.escapeJson(peerAddr) + "\","
                + "\"activeSessions\":" + nexusEndpoint.getSessionManager().getActiveSessionCount()
                + "}";
        session.sendText(statusJson);
    }

    public List<ApplicationMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public ApplicationMessage getMessage(String id) {
        return messagesById.get(id);
    }

    public int getConnectedClientCount() {
        return sessions.size();
    }
}
