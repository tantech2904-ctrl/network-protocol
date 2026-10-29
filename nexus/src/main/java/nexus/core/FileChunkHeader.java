package nexus.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class FileChunkHeader {
    private final int transferId;
    private final short chunkIndex;
    private final short totalChunks;
    private final short ttlSeconds;
    private final String mimeType;
    private final byte[] chunkData;

    public FileChunkHeader(
            int transferId,
            short chunkIndex,
            short totalChunks,
            short ttlSeconds,
            String mimeType,
            byte[] chunkData) {
        this.transferId = transferId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.ttlSeconds = ttlSeconds;
        this.mimeType = mimeType != null ? mimeType : "image/png";
        this.chunkData = chunkData != null ? chunkData : new byte[0];
    }

    public static byte[] encode(
            int transferId,
            short chunkIndex,
            short totalChunks,
            short ttlSeconds,
            String mimeType,
            byte[] chunkData) {
        byte[] mimeBytes = (mimeType != null ? mimeType : "image/png").getBytes(StandardCharsets.UTF_8);
        int totalLen = 12 + mimeBytes.length + (chunkData != null ? chunkData.length : 0);
        ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(transferId);
        buf.putShort(chunkIndex);
        buf.putShort(totalChunks);
        buf.putShort(ttlSeconds);
        buf.putShort((short) mimeBytes.length);
        buf.put(mimeBytes);
        if (chunkData != null && chunkData.length > 0) {
            buf.put(chunkData);
        }
        return buf.array();
    }

    public static FileChunkHeader decode(byte[] payload) {
        if (payload == null || payload.length < 12) {
            throw new IllegalArgumentException("Payload too short for FileChunkHeader");
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = buf.getInt();
        short chunkIndex = buf.getShort();
        short totalChunks = buf.getShort();
        short ttlSeconds = buf.getShort();
        short mimeLen = buf.getShort();
        if (mimeLen < 0 || mimeLen > 128 || buf.remaining() < mimeLen) {
            throw new IllegalArgumentException("Invalid mime length");
        }
        byte[] mimeBytes = new byte[mimeLen];
        buf.get(mimeBytes);
        String mimeType = new String(mimeBytes, StandardCharsets.UTF_8);

        byte[] chunkData = new byte[buf.remaining()];
        buf.get(chunkData);

        return new FileChunkHeader(transferId, chunkIndex, totalChunks, ttlSeconds, mimeType, chunkData);
    }

    public int getTransferId() {
        return transferId;
    }

    public short getChunkIndex() {
        return chunkIndex;
    }

    public short getTotalChunks() {
        return totalChunks;
    }

    public short getTtlSeconds() {
        return ttlSeconds;
    }

    public String getMimeType() {
        return mimeType;
    }

    public byte[] getChunkData() {
        return chunkData.clone();
    }
}

