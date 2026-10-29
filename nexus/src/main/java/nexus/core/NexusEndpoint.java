package nexus.core;

import nexus.protocol.Packet;
import nexus.protocol.PacketCodec;
import nexus.protocol.PacketType;
import nexus.protocol.ProtocolConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NexusEndpoint {
    private final UdpTransport transport;
    private final ReliabilityManager reliabilityManager;
    private final SessionManager sessionManager;
    private final FileTransferManager fileTransferManager;
    private volatile InetSocketAddress defaultPeerAddress;
    private BiConsumer<Session, String> incomingMessageListener;
    private BiConsumer<Session, FileTransferManager.CompletedFile> incomingFileListener;
    private Consumer<ProtocolTelemetry> telemetryListener;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Map<Integer, Long> sentPacketTimes = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile Session activeSession;

    public NexusEndpoint(UdpTransport transport) {
        this(transport, new ReliabilityManager(transport), new SessionManager());
    }

    public NexusEndpoint(
            UdpTransport transport,
            ReliabilityManager reliabilityManager,
            SessionManager sessionManager) {
        this.transport = transport;
        this.reliabilityManager = reliabilityManager;
        this.sessionManager = sessionManager;
        this.fileTransferManager = new FileTransferManager();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Nexus-Heartbeat-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start(int port) throws IOException {
        if (running) {
            return;
        }
        transport.bind(port);
        transport.startListening(this::handleIncomingDatagram);
        this.running = true;

        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                ProtocolConfig.DEFAULT_HEARTBEAT_INTERVAL_MS,
                ProtocolConfig.DEFAULT_HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    public void setPeerAddress(InetSocketAddress peerAddress) {
        this.defaultPeerAddress = peerAddress;
    }

    public InetSocketAddress getPeerAddress() {
        return defaultPeerAddress;
    }

    public void setIncomingMessageListener(BiConsumer<Session, String> listener) {
        this.incomingMessageListener = listener;
    }

    public void setIncomingFileListener(BiConsumer<Session, FileTransferManager.CompletedFile> listener) {
        this.incomingFileListener = listener;
    }

    public void setTelemetryListener(Consumer<ProtocolTelemetry> listener) {
        this.telemetryListener = listener;
    }

    public void simulateDropNext(int count) {
        transport.simulateDropNext(count);
    }

    public void simulateDropAll(boolean enable) {
        transport.simulateDropAll(enable);
    }

    public CompletableFuture<DeliveryStatus> sendMessage(
            String text,
            Consumer<DeliveryStatus> statusListener) throws IOException {
        return sendMessageTo(defaultPeerAddress, text, statusListener);
    }

    public CompletableFuture<DeliveryStatus> sendMessageTo(
            InetSocketAddress target,
            String text,
            Consumer<DeliveryStatus> statusListener) throws IOException {
        if (!running) {
            throw new IllegalStateException("Endpoint is not running");
        }
        if (target == null) {
            throw new IllegalStateException("Peer address is not configured");
        }

        Session session = getOrCreateSession(target);
        int sequenceNumber = session.nextOutgoingSequenceNumber();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);

        Packet packet = Packet.createData(session.getSessionId(), sequenceNumber, payload);
        recordTelemetry("OUT", packet, target, 0);
        sentPacketTimes.put(sequenceNumber, System.currentTimeMillis());

        return reliabilityManager.trackAndSend(packet, target, statusListener);
    }

    public CompletableFuture<DeliveryStatus> sendFileTo(
            InetSocketAddress target,
            byte[] fileBytes,
            String mimeType,
            short ttlSeconds,
            Consumer<DeliveryStatus> statusListener) throws IOException {
        if (!running) {
            throw new IllegalStateException("Endpoint is not running");
        }
        if (target == null) {
            throw new IllegalStateException("Peer address is not configured");
        }

        Session session = getOrCreateSession(target);
        List<Packet> chunkPackets = fileTransferManager.createOutgoingFilePackets(
                session.getSessionId(), session, fileBytes, mimeType, ttlSeconds);

        CompletableFuture<DeliveryStatus> combined = CompletableFuture.completedFuture(DeliveryStatus.PEER_ACKNOWLEDGED);
        for (Packet chunkPacket : chunkPackets) {
            recordTelemetry("OUT", chunkPacket, target, 0);
            sentPacketTimes.put(chunkPacket.getSequenceNumber(), System.currentTimeMillis());
            combined = reliabilityManager.trackAndSend(chunkPacket, target, statusListener);
        }

        return combined;
    }

    public void sendConnect(InetSocketAddress destination) throws IOException {
        Session session = getOrCreateSession(destination);
        Packet packet = Packet.createConnect(session.getSessionId());
        byte[] encoded = PacketCodec.encode(packet);
        recordTelemetry("OUT", packet, destination, 0);
        transport.send(encoded, destination);
    }

    public Session getOrCreateSession(InetSocketAddress remoteAddress) {
        if (activeSession != null && activeSession.getRemoteAddress().equals(remoteAddress)
                && activeSession.getState() != SessionState.CLOSED) {
            return activeSession;
        }
        Session session = sessionManager.findSessionByAddress(remoteAddress);
        if (session == null || session.getState() == SessionState.CLOSED) {
            session = sessionManager.createSession(remoteAddress);
        }
        activeSession = session;
        return session;
    }

    private void handleIncomingDatagram(ReceivedDatagram datagram) {
        try {
            Packet packet = PacketCodec.decode(datagram.getData(), datagram.getLength());
            long latency = 0;
            if (packet.getType() == PacketType.ACK) {
                Long sentTime = sentPacketTimes.remove(packet.getAckNumber());
                if (sentTime != null) {
                    latency = System.currentTimeMillis() - sentTime;
                }
            }
            recordTelemetry("IN", packet, datagram.getSender(), latency);
            dispatchPacket(packet, datagram.getSender());
        } catch (IllegalArgumentException e) {
            sendErrorPacket(0, datagram.getSender(), e.getMessage());
        } catch (Exception ignored) {
        }
    }

    private void recordTelemetry(String direction, Packet packet, InetSocketAddress remote, long latencyMs) {
        if (telemetryListener != null) {
            telemetryListener.accept(new ProtocolTelemetry(direction, packet, remote, latencyMs));
        }
    }

    private void dispatchPacket(Packet packet, InetSocketAddress sender) {
        Session session = sessionManager.getSession(packet.getSessionId());
        if (session == null) {
            session = sessionManager.findSessionByAddress(sender);
        }

        switch (packet.getType()) {
            case CONNECT:
                handleConnect(packet, sender);
                break;
            case ACCEPT:
                handleAccept(packet, sender);
                break;
            case DATA:
                handleData(packet, sender, session);
                break;
            case FILE_CHUNK:
                handleFileChunk(packet, sender, session);
                break;
            case ACK:
                handleAck(packet);
                break;
            case HEARTBEAT:
                handleHeartbeat(packet, sender);
                break;
            case HEARTBEAT_ACK:
                if (session != null) {
                    session.updateActivity();
                }
                break;
            case DISCONNECT:
                handleDisconnect(packet, sender, session);
                break;
            case DISCONNECT_ACK:
                if (session != null) {
                    session.setState(SessionState.CLOSED);
                    sessionManager.removeSession(session.getSessionId());
                }
                break;
            case ERROR:
                if (session != null) {
                    session.updateActivity();
                }
                break;
        }
    }

    private void handleConnect(Packet packet, InetSocketAddress sender) {
        Session session = sessionManager.getSession(packet.getSessionId());
        if (session == null) {
            session = sessionManager.registerSession(packet.getSessionId(), sender);
        }
        session.setState(SessionState.ACTIVE);
        activeSession = session;

        Packet acceptPacket = Packet.createAccept(session.getSessionId());
        try {
            byte[] encoded = PacketCodec.encode(acceptPacket);
            recordTelemetry("OUT", acceptPacket, sender, 0);
            transport.send(encoded, sender);
        } catch (IOException ignored) {
        }
    }

    private void handleAccept(Packet packet, InetSocketAddress sender) {
        Session session = sessionManager.getSession(packet.getSessionId());
        if (session == null) {
            session = sessionManager.registerSession(packet.getSessionId(), sender);
        }
        session.setState(SessionState.ACTIVE);
        activeSession = session;
    }

    private void handleData(Packet packet, InetSocketAddress sender, Session session) {
        if (session == null) {
            session = sessionManager.registerSession(packet.getSessionId(), sender);
            session.setState(SessionState.ACTIVE);
            activeSession = session;
        }

        Packet ackPacket = Packet.createAck(session.getSessionId(), packet.getSequenceNumber());
        try {
            byte[] encoded = PacketCodec.encode(ackPacket);
            recordTelemetry("OUT", ackPacket, sender, 0);
            transport.send(encoded, sender);
        } catch (IOException ignored) {
        }

        List<Packet> deliverable = session.processIncomingData(packet);
        for (Packet readyPacket : deliverable) {
            String text = new String(readyPacket.getPayload(), StandardCharsets.UTF_8);
            if (incomingMessageListener != null) {
                incomingMessageListener.accept(session, text);
            }
        }
    }

    private void handleFileChunk(Packet packet, InetSocketAddress sender, Session session) {
        if (session == null) {
            session = sessionManager.registerSession(packet.getSessionId(), sender);
            session.setState(SessionState.ACTIVE);
            activeSession = session;
        }

        Packet ackPacket = Packet.createAck(session.getSessionId(), packet.getSequenceNumber());
        try {
            byte[] encoded = PacketCodec.encode(ackPacket);
            recordTelemetry("OUT", ackPacket, sender, 0);
            transport.send(encoded, sender);
        } catch (IOException ignored) {
        }

        List<Packet> deliverable = session.processIncomingData(packet);
        for (Packet readyPacket : deliverable) {
            FileTransferManager.CompletedFile completed = fileTransferManager.processIncomingChunk(readyPacket.getPayload());
            if (completed != null && incomingFileListener != null) {
                incomingFileListener.accept(session, completed);
            }
        }
    }

    private void handleAck(Packet packet) {
        reliabilityManager.acknowledge(packet.getAckNumber());
        Session session = sessionManager.getSession(packet.getSessionId());
        if (session != null) {
            session.updateActivity();
        }
    }

    private void handleHeartbeat(Packet packet, InetSocketAddress sender) {
        Packet ack = Packet.createHeartbeatAck(packet.getSessionId());
        try {
            byte[] encoded = PacketCodec.encode(ack);
            recordTelemetry("OUT", ack, sender, 0);
            transport.send(encoded, sender);
        } catch (IOException ignored) {
        }
    }

    private void handleDisconnect(Packet packet, InetSocketAddress sender, Session session) {
        Packet ack = Packet.createDisconnectAck(packet.getSessionId());
        try {
            byte[] encoded = PacketCodec.encode(ack);
            recordTelemetry("OUT", ack, sender, 0);
            transport.send(encoded, sender);
        } catch (IOException ignored) {
        }
        if (session != null) {
            session.setState(SessionState.CLOSED);
            sessionManager.removeSession(session.getSessionId());
        }
    }

    private void sendErrorPacket(int sessionId, InetSocketAddress destination, String message) {
        Packet error = Packet.createError(sessionId, message);
        try {
            byte[] encoded = PacketCodec.encode(error);
            recordTelemetry("OUT", error, destination, 0);
            transport.send(encoded, destination);
        } catch (IOException ignored) {
        }
    }

    private void sendHeartbeats() {
        if (!running) {
            return;
        }
        for (Session session : sessionManager.getAllSessions()) {
            if (session.getState() == SessionState.ACTIVE) {
                Packet heartbeat = Packet.createHeartbeat(session.getSessionId());
                try {
                    byte[] encoded = PacketCodec.encode(heartbeat);
                    recordTelemetry("OUT", heartbeat, session.getRemoteAddress(), 0);
                    transport.send(encoded, session.getRemoteAddress());
                } catch (IOException ignored) {
                }
            }
        }
    }

    public int getLocalPort() {
        return transport.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public Session getActiveSession() {
        return activeSession;
    }

    public ReliabilityManager getReliabilityManager() {
        return reliabilityManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public synchronized void stop() {
        running = false;
        heartbeatScheduler.shutdownNow();
        reliabilityManager.stop();
        sessionManager.stop();
        transport.close();
    }
}
