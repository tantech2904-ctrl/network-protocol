package nexus.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

public interface UdpTransport {
    void bind(int port) throws IOException;

    void send(byte[] data, InetSocketAddress destination) throws IOException;

    void startListening(Consumer<ReceivedDatagram> listener);

    int getLocalPort();

    boolean isClosed();

    void close();

    default void simulateDropNext(int count) {
    }

    default void simulateDropAll(boolean enable) {
    }
}
