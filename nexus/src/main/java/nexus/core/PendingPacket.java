package nexus.core;

import nexus.protocol.Packet;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class PendingPacket {
    private final int sequenceNumber;
    private final Packet packet;
    private final InetSocketAddress destination;
    private final long firstSentTimestamp;
    private volatile long lastSentTimestamp;
    private volatile int retryCount;
    private final CompletableFuture<DeliveryStatus> future;
    private final Consumer<DeliveryStatus> statusListener;

    public PendingPacket(
            int sequenceNumber,
            Packet packet,
            InetSocketAddress destination,
            CompletableFuture<DeliveryStatus> future,
            Consumer<DeliveryStatus> statusListener) {
        this.sequenceNumber = sequenceNumber;
        this.packet = packet;
        this.destination = destination;
        long now = System.currentTimeMillis();
        this.firstSentTimestamp = now;
        this.lastSentTimestamp = now;
        this.retryCount = 0;
        this.future = future;
        this.statusListener = statusListener;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public Packet getPacket() {
        return packet;
    }

    public InetSocketAddress getDestination() {
        return destination;
    }

    public long getFirstSentTimestamp() {
        return firstSentTimestamp;
    }

    public long getLastSentTimestamp() {
        return lastSentTimestamp;
    }

    public void setLastSentTimestamp(long timestamp) {
        this.lastSentTimestamp = timestamp;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public CompletableFuture<DeliveryStatus> getFuture() {
        return future;
    }

    public Consumer<DeliveryStatus> getStatusListener() {
        return statusListener;
    }
}

