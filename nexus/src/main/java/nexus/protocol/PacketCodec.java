package nexus.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

public final class PacketCodec {

    private PacketCodec() {
    }

    public static byte[] encode(Packet packet) {
        if (packet == null) {
            throw new IllegalArgumentException("Packet cannot be null");
        }
        byte[] payload = packet.getPayload();
        if (payload.length > ProtocolConfig.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload size " + payload.length + " exceeds maximum allowed: " + ProtocolConfig.MAX_PAYLOAD_SIZE);
        }

        int totalLength = ProtocolConfig.HEADER_SIZE + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(packet.getMagic());
        buffer.put(packet.getVersion());
        buffer.put(packet.getType().getCode());
        buffer.putShort(packet.getFlags());
        buffer.putInt(packet.getSessionId());
        buffer.putInt(packet.getSequenceNumber());
        buffer.putInt(packet.getAckNumber());
        buffer.putShort((short) payload.length);
        buffer.putInt(0);
        buffer.put(payload);

        byte[] encoded = buffer.array();
        int checksum = calculateChecksum(encoded, totalLength);

        ByteBuffer checksumWriter = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        checksumWriter.position(22);
        checksumWriter.putInt(checksum);

        return encoded;
    }

    public static Packet decode(byte[] data, int length) {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        if (length < ProtocolConfig.HEADER_SIZE) {
            throw new IllegalArgumentException("Packet length " + length + " is smaller than header size " + ProtocolConfig.HEADER_SIZE);
        }
        if (length > ProtocolConfig.HEADER_SIZE + ProtocolConfig.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Packet length " + length + " exceeds maximum allowable size");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);

        int magic = buffer.getInt();
        if (magic != ProtocolConfig.MAGIC) {
            throw new IllegalArgumentException("Invalid magic identifier: 0x" + Integer.toHexString(magic));
        }

        byte version = buffer.get();
        if (version != ProtocolConfig.VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }

        byte typeCode = buffer.get();
        PacketType type = PacketType.fromCode(typeCode);

        short flags = buffer.getShort();
        int sessionId = buffer.getInt();
        int sequenceNumber = buffer.getInt();
        int ackNumber = buffer.getInt();
        int payloadLength = buffer.getShort() & 0xFFFF;

        if (length != ProtocolConfig.HEADER_SIZE + payloadLength) {
            throw new IllegalArgumentException("Packet length " + length + " does not match header payload length " + payloadLength);
        }

        int receivedChecksum = buffer.getInt();

        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        copy[22] = 0;
        copy[23] = 0;
        copy[24] = 0;
        copy[25] = 0;

        int expectedChecksum = calculateChecksum(copy, length);
        if (receivedChecksum != expectedChecksum) {
            throw new IllegalArgumentException("Checksum mismatch: received 0x" + Integer.toHexString(receivedChecksum)
                    + ", calculated 0x" + Integer.toHexString(expectedChecksum));
        }

        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        return new Packet(
                magic,
                version,
                type,
                flags,
                sessionId,
                sequenceNumber,
                ackNumber,
                (short) payloadLength,
                receivedChecksum,
                payload);
    }

    private static int calculateChecksum(byte[] bytes, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, length);
        return (int) (crc.getValue() & 0xFFFFFFFFL);
    }
}

