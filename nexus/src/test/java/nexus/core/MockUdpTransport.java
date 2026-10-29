package nexus.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MockUdpTransport implements UdpTransport {
    private int port;
    private boolean closed;
    private Consumer<ReceivedDatagram> listener;
    private final List<byte[]> sentPackets = new ArrayList<>();
    private final List<InetSocketAddress> destinations = new ArrayList<>();

    public MockUdpTransport() {
        this.port = 5000;
    }

    @Override
    public void bind(int port) throws IOException {
        this.port = port;
        this.closed = false;
    }

    @Override
    public synchronized void send(byte[] data, InetSocketAddress destination) throws IOException {
        if (closed) {
            throw new IOException("Socket closed");
        }
        sentPackets.add(data.clone());
        destinations.add(destination);
    }

    @Override
    public void startListening(Consumer<ReceivedDatagram> listener) {
        this.listener = listener;
    }

    public synchronized void simulateReceive(byte[] data, InetSocketAddress sender) {
        if (listener != null && !closed) {
            listener.accept(new ReceivedDatagram(data, data.length, sender));
        }
    }

    public synchronized List<byte[]> getSentPackets() {
        return new ArrayList<>(sentPackets);
    }

    public synchronized int getSentCount() {
        return sentPackets.size();
    }

    public synchronized void clearSent() {
        sentPackets.clear();
        destinations.clear();
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

