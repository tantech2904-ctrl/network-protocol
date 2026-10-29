package nexus.core;

import java.net.InetSocketAddress;
import java.util.Arrays;

public final class ReceivedDatagram {
    private final byte[] data;
    private final int length;
    private final InetSocketAddress sender;

    public ReceivedDatagram(byte[] data, int length, InetSocketAddress sender) {
        this.data = data != null ? Arrays.copyOf(data, length) : new byte[0];
        this.length = length;
        this.sender = sender;
    }

    public byte[] getData() {
        return data.clone();
    }

    public int getLength() {
        return length;
    }

    public InetSocketAddress getSender() {
        return sender;
    }
}

