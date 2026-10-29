package nexus.protocol;

import nexus.test.TestAssert;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class PacketCodecTest {

    public static void runAllTests() {
        testEncodeAndDecodeValidDataPacket();
        testEncodeAndDecodeEmptyPayload();
        testEncodeAndDecodeMaxPayload();
        testRejectInvalidMagic();
        testRejectUnsupportedVersion();
        testRejectUnknownPacketType();
        testRejectTruncatedHeader();
        testRejectTruncatedPayload();
        testRejectIncorrectPayloadLength();
        testRejectChecksumMismatch();
        testRejectOversizedPayload();
        testBoundarySequenceNumbers();
    }

    public static void testEncodeAndDecodeValidDataPacket() {
        byte[] payload = "Hello NEXUS reliable protocol!".getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.createData(1001, 42, payload);
        byte[] encoded = PacketCodec.encode(packet);
        Packet decoded = PacketCodec.decode(encoded, encoded.length);

        TestAssert.assertEquals(ProtocolConfig.MAGIC, decoded.getMagic(), "Magic should match");
        TestAssert.assertEquals(ProtocolConfig.VERSION, decoded.getVersion(), "Version should match");
        TestAssert.assertEquals(PacketType.DATA, decoded.getType(), "Type should be DATA");
        TestAssert.assertEquals(1001, decoded.getSessionId(), "Session ID should match");
        TestAssert.assertEquals(42, decoded.getSequenceNumber(), "Sequence number should match");
        TestAssert.assertEquals((short) payload.length, decoded.getPayloadLength(), "Payload length should match");
        TestAssert.assertEquals(payload, decoded.getPayload(), "Payload content should match");
    }

    public static void testEncodeAndDecodeEmptyPayload() {
        Packet packet = Packet.createAck(2002, 99);
        byte[] encoded = PacketCodec.encode(packet);
        Packet decoded = PacketCodec.decode(encoded, encoded.length);

        TestAssert.assertEquals(PacketType.ACK, decoded.getType(), "Type should be ACK");
        TestAssert.assertEquals(2002, decoded.getSessionId(), "Session ID should match");
        TestAssert.assertEquals(99, decoded.getAckNumber(), "Ack number should match");
        TestAssert.assertEquals(0, (int) decoded.getPayloadLength(), "Payload length should be 0");
        TestAssert.assertEquals(0, decoded.getPayload().length, "Payload array length should be 0");
    }

    public static void testEncodeAndDecodeMaxPayload() {
        byte[] maxPayload = new byte[ProtocolConfig.MAX_PAYLOAD_SIZE];
        Arrays.fill(maxPayload, (byte) 0x7E);
        Packet packet = Packet.createData(3003, 1, maxPayload);
        byte[] encoded = PacketCodec.encode(packet);
        Packet decoded = PacketCodec.decode(encoded, encoded.length);

        TestAssert.assertEquals((short) ProtocolConfig.MAX_PAYLOAD_SIZE, decoded.getPayloadLength(), "Payload length should equal max size");
        TestAssert.assertEquals(maxPayload, decoded.getPayload(), "Max payload content should match");
    }

    public static void testRejectInvalidMagic() {
        Packet packet = Packet.createHeartbeat(4004);
        byte[] encoded = PacketCodec.encode(packet);
        encoded[0] = 0x00;

        boolean caught = false;
        try {
            PacketCodec.decode(encoded, encoded.length);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject invalid magic identifier");
    }

    public static void testRejectUnsupportedVersion() {
        Packet packet = Packet.createHeartbeat(4004);
        byte[] encoded = PacketCodec.encode(packet);
        encoded[4] = 0x02;

        boolean caught = false;
        try {
            PacketCodec.decode(encoded, encoded.length);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject unsupported version");
    }

    public static void testRejectUnknownPacketType() {
        Packet packet = Packet.createHeartbeat(4004);
        byte[] encoded = PacketCodec.encode(packet);
        encoded[5] = 0x7F;

        boolean caught = false;
        try {
            PacketCodec.decode(encoded, encoded.length);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject unknown packet type");
    }

    public static void testRejectTruncatedHeader() {
        byte[] shortData = new byte[ProtocolConfig.HEADER_SIZE - 1];
        boolean caught = false;
        try {
            PacketCodec.decode(shortData, shortData.length);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject truncated header");
    }

    public static void testRejectTruncatedPayload() {
        byte[] payload = "Testing payload truncation".getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.createData(5005, 10, payload);
        byte[] encoded = PacketCodec.encode(packet);

        boolean caught = false;
        try {
            PacketCodec.decode(encoded, encoded.length - 5);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject truncated payload");
    }

    public static void testRejectIncorrectPayloadLength() {
        byte[] payload = "Testing incorrect payload length".getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.createData(5005, 10, payload);
        byte[] encoded = PacketCodec.encode(packet);
        encoded[21] = (byte) (encoded[21] + 1);

        boolean caught = false;
        try {
            PacketCodec.decode(encoded, encoded.length);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject mismatch between buffer length and header length");
    }

    public static void testRejectChecksumMismatch() {
        byte[] payload = "Tamper detection test".getBytes(StandardCharsets.UTF_8);
        Packet packet = Packet.createData(6006, 12, payload);
        byte[] encoded = PacketCodec.encode(packet);
        encoded[encoded.length - 1] ^= 0x5A;

        boolean caught = false;
        try {
            PacketCodec.decode(encoded, encoded.length);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject packet with checksum mismatch");
    }

    public static void testRejectOversizedPayload() {
        byte[] hugePayload = new byte[ProtocolConfig.MAX_PAYLOAD_SIZE + 1];
        Packet packet = new Packet(
                ProtocolConfig.MAGIC,
                ProtocolConfig.VERSION,
                PacketType.DATA,
                PacketFlags.NONE,
                7007,
                1,
                0,
                (short) hugePayload.length,
                0,
                hugePayload);

        boolean caught = false;
        try {
            PacketCodec.encode(packet);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Should reject encoding of oversized payload");
    }

    public static void testBoundarySequenceNumbers() {
        int[] boundaries = new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int seq : boundaries) {
            Packet packet = Packet.createData(8008, seq, "boundary".getBytes(StandardCharsets.UTF_8));
            byte[] encoded = PacketCodec.encode(packet);
            Packet decoded = PacketCodec.decode(encoded, encoded.length);
            TestAssert.assertEquals(seq, decoded.getSequenceNumber(), "Sequence number should preserve boundary: " + seq);
        }
    }
}

