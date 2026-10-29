package nexus.protocol;

import java.util.Arrays;

public final class Packet {
    private final int magic;
    private final byte version;
    private final PacketType type;
    private final short flags;
    private final int sessionId;
    private final int sequenceNumber;
    private final int ackNumber;
    private final short payloadLength;
    private final int checksum;
    private final byte[] payload;

    public Packet(
            int magic,
            byte version,
            PacketType type,
            short flags,
            int sessionId,
            int sequenceNumber,
            int ackNumber,
            short payloadLength,
            int checksum,
            byte[] payload) {
        this.magic = magic;
        this.version = version;
        this.type = type;
        this.flags = flags;
        this.sessionId = sessionId;
        this.sequenceNumber = sequenceNumber;
        this.ackNumber = ackNumber;
        this.payloadLength = payloadLength;
        this.checksum = checksum;
        this.payload = payload != null ? payload.clone() : new byte[0];
    }

    public static Packet createConnect(int sessionId) {
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.CONNECT,
                PacketFlags.NONE,
                sessionId,
                0,
                0,
                (short) 0,
                0,
                new byte[0]);
    }

    public static Packet createAccept(int sessionId) {
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.ACCEPT,
                PacketFlags.NONE,
                sessionId,
                0,
                0,
                (short) 0,
                0,
                new byte[0]);
    }

    public static Packet createData(int sessionId, int sequenceNumber, byte[] payload) {
        short length = payload != null ? (short) payload.length : 0;
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.DATA,
                PacketFlags.NONE,
                sessionId,
                sequenceNumber,
                0,
                length,
                0,
                payload);
    }

    public static Packet createFileChunk(int sessionId, int sequenceNumber, byte[] payload) {
        short length = payload != null ? (short) payload.length : 0;
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.FILE_CHUNK,
                PacketFlags.NONE,
                sessionId,
                sequenceNumber,
                0,
                length,
                0,
                payload);
    }

    public static Packet createAck(int sessionId, int ackNumber) {
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.ACK,
                PacketFlags.NONE,
                sessionId,
                0,
                ackNumber,
                (short) 0,
                0,
                new byte[0]);
    }

    public static Packet createHeartbeat(int sessionId) {
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.HEARTBEAT,
                PacketFlags.NONE,
                sessionId,
                0,
                0,
                (short) 0,
                0,
                new byte[0]);
    }

    public static Packet createHeartbeatAck(int sessionId) {
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.HEARTBEAT_ACK,
                PacketFlags.NONE,
                sessionId,
                0,
                0,
                (short) 0,
                0,
                new byte[0]);
    }

    public static Packet createDisconnect(int sessionId) {
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.DISCONNECT,
                PacketFlags.NONE,
                sessionId,
                0,
                0,
                (short) 0,
                0,
                new byte[0]);
    }

    public static Packet createDisconnectAck(int sessionId) {
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.DISCONNECT_ACK,
                PacketFlags.NONE,
                sessionId,
                0,
                0,
                (short) 0,
                0,
                new byte[0]);
    }

    public static Packet createError(int sessionId, String errorMessage) {
        byte[] payload = errorMessage != null ? errorMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        return new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.ERROR,
                PacketFlags.NONE,
                sessionId,
                0,
                0,
                (short) payload.length,
                0,
                payload);
    }

    public Packet withRetransmitFlag() {
        short newFlags = (short) (this.flags | PacketFlags.RETRANSMIT);
        return new Packet(
                this.magic,
                this.version,
                this.type,
                newFlags,
                this.sessionId,
                this.sequenceNumber,
                this.ackNumber,
                this.payloadLength,
                0,
                this.payload);
    }

    public int getMagic() {
        return magic;
    }

    public byte getVersion() {
        return version;
    }

    public PacketType getType() {
        return type;
    }

    public short getFlags() {
        return flags;
    }

    public int getSessionId() {
        return sessionId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public int getAckNumber() {
        return ackNumber;
    }

    public short getPayloadLength() {
        return payloadLength;
    }

    public int getChecksum() {
        return checksum;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Packet that)) {
            return false;
        }
        return magic == that.magic
                && version == that.version
                && flags == that.flags
                && sessionId == that.sessionId
                && sequenceNumber == that.sequenceNumber
                && ackNumber == that.ackNumber
                && payloadLength == that.payloadLength
                && checksum == that.checksum
                && type == that.type
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = magic;
        result = 31 * result + (int) version;
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (int) flags;
        result = 31 * result + sessionId;
        result = 31 * result + sequenceNumber;
        result = 31 * result + ackNumber;
        result = 31 * result + (int) payloadLength;
        result = 31 * result + checksum;
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "Packet{"
                + "type=" + type
                + ", seq=" + sequenceNumber
                + ", ack=" + ackNumber
                + ", sessionId=" + sessionId
                + ", flags=" + flags
                + ", payloadLength=" + payloadLength
                + '}';
    }
}
