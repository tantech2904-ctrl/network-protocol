package nexus.integration;

import nexus.core.ReceivedDatagram;
import nexus.core.UdpTransport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class VirtualLossyTransport implements UdpTransport {
    private final int port;
    private VirtualLossyTransport pairedTransport;
    private Consumer<ReceivedDatagram> listener;
    private volatile boolean dropAll;
    private final AtomicInteger dropFirstNPackets = new AtomicInteger(0);
    private final AtomicInteger packetsSentCount = new AtomicInteger(0);
    private final AtomicInteger packetsDroppedCount = new AtomicInteger(0);
    private volatile boolean closed;

    public VirtualLossyTransport(int port) {
        this.port = port;
    }

    public void setPairedTransport(VirtualLossyTransport paired) {
        this.pairedTransport = paired;
    }

    public void setDropAll(boolean dropAll) {
        this.dropAll = dropAll;
    }

    public void setDropFirstNPackets(int n) {
        this.dropFirstNPackets.set(n);
    }

    public int getPacketsSentCount() {
        return packetsSentCount.get();
    }

    public int getPacketsDroppedCount() {
        return packetsDroppedCount.get();
    }

    @Override
    public void bind(int port) {
    }

    @Override
    public void send(byte[] data, InetSocketAddress destination) throws IOException {
        if (closed) {
            throw new IOException("Transport closed");
        }
        packetsSentCount.incrementAndGet();

        if (dropAll) {
            packetsDroppedCount.incrementAndGet();
            return;
        }

        if (dropFirstNPackets.get() > 0) {
            dropFirstNPackets.decrementAndGet();
            packetsDroppedCount.incrementAndGet();
            return;
        }

        if (pairedTransport != null && !pairedTransport.isClosed()) {
            pairedTransport.deliverIncoming(data, new InetSocketAddress("127.0.0.1", port));
        }
    }

    public void deliverIncoming(byte[] data, InetSocketAddress sender) {
        if (listener != null && !closed) {
            listener.accept(new ReceivedDatagram(data, data.length, sender));
        }
    }

    @Override
    public void startListening(Consumer<ReceivedDatagram> listener) {
        this.listener = listener;
    }

    @Override
    public int getLocalPort() {
        return port;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}

