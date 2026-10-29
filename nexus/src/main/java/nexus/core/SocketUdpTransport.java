package nexus.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.function.Consumer;

public class SocketUdpTransport implements UdpTransport {
    private DatagramSocket socket;
    private Thread listenerThread;
    private volatile boolean running;
    private Consumer<ReceivedDatagram> listener;
    private volatile int dropNextN = 0;
    private volatile boolean dropAll = false;

    public SocketUdpTransport() {
    }

    public SocketUdpTransport(int port) throws IOException {
        bind(port);
    }

    @Override
    public synchronized void bind(int port) throws IOException {
        if (socket != null && !socket.isClosed()) {
            throw new IllegalStateException("Socket is already bound to port " + socket.getLocalPort());
        }
        socket = new DatagramSocket(new InetSocketAddress(port));
    }

    @Override
    public void send(byte[] data, InetSocketAddress destination) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("Socket is not open");
        }
        if (dropAll) {
            return;
        }
        if (dropNextN > 0) {
            dropNextN--;
            return;
        }
        DatagramPacket packet = new DatagramPacket(data, data.length, destination);
        socket.send(packet);
    }

    @Override
    public void simulateDropNext(int count) {
        this.dropNextN = count;
    }

    @Override
    public void simulateDropAll(boolean enable) {
        this.dropAll = enable;
    }

    @Override
    public synchronized void startListening(Consumer<ReceivedDatagram> packetListener) {
        if (running) {
            return;
        }
        if (socket == null || socket.isClosed()) {
            throw new IllegalStateException("Cannot start listening before binding socket");
        }
        this.listener = packetListener;
        this.running = true;

        listenerThread = new Thread(() -> {
            byte[] buffer = new byte[65535];
            while (running && socket != null && !socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    if (!running) {
                        break;
                    }
                    InetSocketAddress sender = (InetSocketAddress) packet.getSocketAddress();
                    ReceivedDatagram datagram = new ReceivedDatagram(packet.getData(), packet.getLength(), sender);
                    if (listener != null) {
                        listener.accept(datagram);
                    }
                } catch (SocketException e) {
                    if (!running) {
                        break;
                    }
                } catch (IOException ignored) {
                }
            }
        }, "Nexus-Udp-Listener-" + getLocalPort());
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    @Override
    public int getLocalPort() {
        return socket != null ? socket.getLocalPort() : -1;
    }

    @Override
    public boolean isClosed() {
        return socket == null || socket.isClosed();
    }

    @Override
    public synchronized void close() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (listenerThread != null && listenerThread.isAlive()) {
            listenerThread.interrupt();
        }
    }
}
