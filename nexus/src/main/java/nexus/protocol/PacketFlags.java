package nexus.protocol;

public final class PacketFlags {
    public static final short NONE = 0x0000;
    public static final short RETRANSMIT = 0x0001;
    public static final short URGENT = 0x0002;

    private PacketFlags() {
    }

    public static boolean isRetransmit(short flags) {
        return (flags & RETRANSMIT) != 0;
    }

    public static boolean isUrgent(short flags) {
        return (flags & URGENT) != 0;
    }
}

