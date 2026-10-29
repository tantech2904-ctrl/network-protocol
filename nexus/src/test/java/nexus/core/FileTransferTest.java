package nexus.core;

import nexus.protocol.Packet;
import nexus.test.TestAssert;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class FileTransferTest {

    public static void runAllTests() {
        testChunkHeaderEncodeDecode();
        testFileChunkingAndReassembly();
        testCorruptedChunkRejection();
    }

    public static void testChunkHeaderEncodeDecode() {
        byte[] chunk = "Sample image chunk data".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = FileChunkHeader.encode(1234, (short) 1, (short) 5, (short) 15, "image/png", chunk);

        FileChunkHeader decoded = FileChunkHeader.decode(encoded);
        TestAssert.assertEquals(1234, decoded.getTransferId(), "Transfer ID must match");
        TestAssert.assertEquals(1, (int) decoded.getChunkIndex(), "Chunk index must match");
        TestAssert.assertEquals(5, (int) decoded.getTotalChunks(), "Total chunks must match");
        TestAssert.assertEquals(15, (int) decoded.getTtlSeconds(), "TTL seconds must match");
        TestAssert.assertEquals("image/png", decoded.getMimeType(), "MIME type must match");
        TestAssert.assertEquals(chunk, decoded.getChunkData(), "Chunk bytes must match");
    }

    public static void testFileChunkingAndReassembly() {
        FileTransferManager manager = new FileTransferManager();
        Session session = new Session(8888, new InetSocketAddress("127.0.0.1", 9999));

        byte[] original = new byte[2500];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 256);
        }

        List<Packet> packets = manager.createOutgoingFilePackets(8888, session, original, "image/jpeg", (short) 30);
        TestAssert.assertEquals(3, packets.size(), "2500 bytes should split into 3 chunks of <= 1000 bytes");

        FileTransferManager receiverManager = new FileTransferManager();
        FileTransferManager.CompletedFile result1 = receiverManager.processIncomingChunk(packets.get(0).getPayload());
        TestAssert.assertNull(result1, "First chunk should not complete the file");

        FileTransferManager.CompletedFile result2 = receiverManager.processIncomingChunk(packets.get(1).getPayload());
        TestAssert.assertNull(result2, "Second chunk should not complete the file");

        FileTransferManager.CompletedFile finalResult = receiverManager.processIncomingChunk(packets.get(2).getPayload());
        TestAssert.assertNotNull(finalResult, "Third chunk must complete the file");
        TestAssert.assertEquals(2500, finalResult.getTotalBytes(), "Reassembled byte count must match original");
        TestAssert.assertEquals("image/jpeg", finalResult.getMimeType(), "MIME type must match");
        TestAssert.assertEquals(30, finalResult.getTtlSeconds(), "TTL must match");
        TestAssert.assertTrue(finalResult.getDataUri().startsWith("data:image/jpeg;base64,"), "Data URI prefix must match");
    }

    public static void testCorruptedChunkRejection() {
        boolean caught = false;
        try {
            FileChunkHeader.decode(new byte[5]);
        } catch (IllegalArgumentException e) {
            caught = true;
        }
        TestAssert.assertTrue(caught, "Short header must be rejected");
    }
}

