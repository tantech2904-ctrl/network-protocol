package nexus.core;

import nexus.protocol.Packet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class FileTransferManager {
    public static final int CHUNK_SIZE = 1000;

    public static class CompletedFile {
        private final int transferId;
        private final String mimeType;
        private final int ttlSeconds;
        private final String dataUri;
        private final int totalBytes;

        public CompletedFile(int transferId, String mimeType, int ttlSeconds, String dataUri, int totalBytes) {
            this.transferId = transferId;
            this.mimeType = mimeType;
            this.ttlSeconds = ttlSeconds;
            this.dataUri = dataUri;
            this.totalBytes = totalBytes;
        }

        public int getTransferId() {
            return transferId;
        }

        public String getMimeType() {
            return mimeType;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public String getDataUri() {
            return dataUri;
        }

        public int getTotalBytes() {
            return totalBytes;
        }
    }

    private static class InProgress {
        private final int transferId;
        private final short totalChunks;
        private final short ttlSeconds;
        private final String mimeType;
        private final Map<Short, byte[]> chunks = new ConcurrentHashMap<>();
        private final long startedAt = System.currentTimeMillis();

        public InProgress(int transferId, short totalChunks, short ttlSeconds, String mimeType) {
            this.transferId = transferId;
            this.totalChunks = totalChunks;
            this.ttlSeconds = ttlSeconds;
            this.mimeType = mimeType;
        }
    }

    private final Map<Integer, InProgress> incomingTransfers = new ConcurrentHashMap<>();

    public CompletedFile processIncomingChunk(byte[] chunkPayload) {
        FileChunkHeader header = FileChunkHeader.decode(chunkPayload);
        InProgress inProgress = incomingTransfers.computeIfAbsent(
                header.getTransferId(),
                id -> new InProgress(header.getTransferId(), header.getTotalChunks(), header.getTtlSeconds(), header.getMimeType()));

        inProgress.chunks.put(header.getChunkIndex(), header.getChunkData());

        if (inProgress.chunks.size() >= inProgress.totalChunks) {
            incomingTransfers.remove(header.getTransferId());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (short i = 0; i < inProgress.totalChunks; i++) {
                byte[] part = inProgress.chunks.get(i);
                if (part != null) {
                    try {
                        baos.write(part);
                    } catch (IOException ignored) {
                    }
                }
            }
            byte[] completeFile = baos.toByteArray();
            String base64 = Base64.getEncoder().encodeToString(completeFile);
            String dataUri = "data:" + inProgress.mimeType + ";base64," + base64;
            return new CompletedFile(inProgress.transferId, inProgress.mimeType, inProgress.ttlSeconds, dataUri, completeFile.length);
        }

        return null;
    }

    public List<Packet> createOutgoingFilePackets(
            int sessionId,
            Session session,
            byte[] fileBytes,
            String mimeType,
            short ttlSeconds) {
        List<Packet> packets = new ArrayList<>();
        int transferId = ThreadLocalRandom.current().nextInt(100000, Integer.MAX_VALUE);

        int totalLen = fileBytes.length;
        short totalChunks = (short) Math.max(1, (int) Math.ceil((double) totalLen / CHUNK_SIZE));

        for (short i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, totalLen);
            byte[] chunkData = new byte[end - start];
            System.arraycopy(fileBytes, start, chunkData, 0, chunkData.length);

            byte[] chunkPayload = FileChunkHeader.encode(transferId, i, totalChunks, ttlSeconds, mimeType, chunkData);
            int seq = session.nextOutgoingSequenceNumber();
            Packet packet = Packet.createFileChunk(sessionId, seq, chunkPayload);
            packets.add(packet);
        }

        return packets;
    }
}

