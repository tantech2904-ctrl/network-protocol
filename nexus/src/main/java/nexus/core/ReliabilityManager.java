package nexus.core;

import nexus.protocol.Packet;
import nexus.protocol.PacketCodec;
import nexus.protocol.ProtocolConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ReliabilityManager {
    private final UdpTransport transport;
    private final long ackTimeoutMs;
    private final int maxRetries;
    private final int maxPending;
    private final Map<Integer, PendingPacket> pendingPackets;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public ReliabilityManager(UdpTransport transport) {
        this(transport, ProtocolConfig.DEFAULT_ACK_TIMEOUT_MS, ProtocolConfig.DEFAULT_MAX_RETRIES, 256);
    }

    public ReliabilityManager(UdpTransport transport, long ackTimeoutMs, int maxRetries, int maxPending) {
        this.transport = transport;
        this.ackTimeoutMs = ackTimeoutMs;
        this.maxRetries = maxRetries;
        this.maxPending = maxPending;
        this.pendingPackets = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Nexus-Reliability-Timer");
            thread.setDaemon(true);
            return thread;
        });
        this.running = true;
        this.scheduler.scheduleAtFixedRate(this::checkRetransmissions, 50, 50, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<DeliveryStatus> trackAndSend(
            Packet packet,
            InetSocketAddress destination,
            Consumer<DeliveryStatus> statusListener) throws IOException {
        if (!running) {
            throw new IllegalStateException("ReliabilityManager is stopped");
        }
        if (pendingPackets.size() >= maxPending) {
            throw new IllegalStateException("Pending packet buffer is full (" + maxPending + ")");
        }

        CompletableFuture<DeliveryStatus> future = new CompletableFuture<>();
        PendingPacket pending = new PendingPacket(packet.getSequenceNumber(), packet, destination, future, statusListener);
        pendingPackets.put(packet.getSequenceNumber(), pending);

        if (statusListener != null) {
            statusListener.accept(DeliveryStatus.UDP_SENT);
        }

        byte[] encoded = PacketCodec.encode(packet);
        transport.send(encoded, destination);

        return future;
    }

    public boolean acknowledge(int sequenceNumber) {
        PendingPacket pending = pendingPackets.remove(sequenceNumber);
        if (pending != null) {
            if (pending.getStatusListener() != null) {
                pending.getStatusListener().accept(DeliveryStatus.PEER_ACKNOWLEDGED);
            }
            pending.getFuture().complete(DeliveryStatus.PEER_ACKNOWLEDGED);
            return true;
        }
        return false;
    }

    private void checkRetransmissions() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, PendingPacket>> iterator = pendingPackets.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Integer, PendingPacket> entry = iterator.next();
            PendingPacket pending = entry.getValue();

            if (now - pending.getLastSentTimestamp() >= ackTimeoutMs) {
                if (pending.getRetryCount() < maxRetries) {
                    pending.incrementRetryCount();
                    pending.setLastSentTimestamp(now);
                    Packet retransmitPacket = pending.getPacket().withRetransmitFlag();
                    byte[] encoded = PacketCodec.encode(retransmitPacket);
                    try {
                        if (pending.getStatusListener() != null) {
                            pending.getStatusListener().accept(DeliveryStatus.RETRANSMITTING);
                        }
                        transport.send(encoded, pending.getDestination());
                    } catch (IOException e) {
                        iterator.remove();
                        if (pending.getStatusListener() != null) {
                            pending.getStatusListener().accept(DeliveryStatus.FAILED);
                        }
                        pending.getFuture().complete(DeliveryStatus.FAILED);
                    }
                } else {
                    iterator.remove();
                    if (pending.getStatusListener() != null) {
                        pending.getStatusListener().accept(DeliveryStatus.FAILED);
                    }
                    pending.getFuture().complete(DeliveryStatus.FAILED);
                }
            }
        }
    }

    public int getPendingCount() {
        return pendingPackets.size();
    }

    public boolean isPending(int sequenceNumber) {
        return pendingPackets.containsKey(sequenceNumber);
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
        for (PendingPacket pending : pendingPackets.values()) {
            if (pending.getStatusListener() != null) {
                pending.getStatusListener().accept(DeliveryStatus.FAILED);
            }
            pending.getFuture().complete(DeliveryStatus.FAILED);
        }
        pendingPackets.clear();
    }
}
