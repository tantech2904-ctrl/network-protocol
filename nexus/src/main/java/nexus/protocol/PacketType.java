package nexus.protocol;

public enum PacketType {
    CONNECT((byte) 0x01),
    ACCEPT((byte) 0x02),
    DATA((byte) 0x03),
    ACK((byte) 0x04),
    HEARTBEAT((byte) 0x05),
    HEARTBEAT_ACK((byte) 0x06),
    DISCONNECT((byte) 0x07),
    DISCONNECT_ACK((byte) 0x08),
    ERROR((byte) 0x09),
    FILE_CHUNK((byte) 0x0A);

    private final byte code;

    PacketType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static PacketType fromCode(byte code) {
        for (PacketType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown packet type code: " + code);
    }
}
