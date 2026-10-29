package nexus.web;

import nexus.core.DeliveryStatus;

import java.util.UUID;

public class ApplicationMessage {
    private final String id;
    private final String sender;
    private final String content;
    private final long timestamp;
    private volatile DeliveryStatus status;
    private volatile int sequenceNumber;
    private volatile int retryCount;
    private volatile String peerName;
    private volatile String peerColor;
    private volatile String messageType;
    private volatile String mediaData;
    private volatile int ttlSeconds;

    public ApplicationMessage(String sender, String content) {
        this(UUID.randomUUID().toString(), sender, content, System.currentTimeMillis(),
                DeliveryStatus.GATEWAY_ACCEPTED, 0, 0,
                "user".equals(sender) ? "You" : "Peer",
                "user".equals(sender) ? "#007AFF" : "#8E8E93",
                "TEXT", null, 0);
    }

    public ApplicationMessage(
            String id,
            String sender,
            String content,
            long timestamp,
            DeliveryStatus status,
            int sequenceNumber,
            int retryCount,
            String peerName,
            String peerColor,
            String messageType,
            String mediaData,
            int ttlSeconds) {
        this.id = id;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
        this.status = status;
        this.sequenceNumber = sequenceNumber;
        this.retryCount = retryCount;
        this.peerName = peerName != null ? peerName : ("user".equals(sender) ? "You" : "Peer");
        this.peerColor = peerColor != null ? peerColor : ("user".equals(sender) ? "#007AFF" : "#8E8E93");
        this.messageType = messageType != null ? messageType : "TEXT";
        this.mediaData = mediaData;
        this.ttlSeconds = ttlSeconds;
    }

    public String getId() {
        return id;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getPeerName() {
        return peerName;
    }

    public void setPeerName(String peerName) {
        this.peerName = peerName;
    }

    public String getPeerColor() {
        return peerColor;
    }

    public void setPeerColor(String peerColor) {
        this.peerColor = peerColor;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getMediaData() {
        return mediaData;
    }

    public void setMediaData(String mediaData) {
        this.mediaData = mediaData;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":\"").append(escapeJson(id)).append("\",");
        sb.append("\"sender\":\"").append(escapeJson(sender)).append("\",");
        sb.append("\"content\":\"").append(escapeJson(content)).append("\",");
        sb.append("\"timestamp\":").append(timestamp).append(",");
        sb.append("\"status\":\"").append(status.name()).append("\",");
        sb.append("\"sequenceNumber\":").append(sequenceNumber).append(",");
        sb.append("\"retryCount\":").append(retryCount).append(",");
        sb.append("\"peerName\":\"").append(escapeJson(peerName)).append("\",");
        sb.append("\"peerColor\":\"").append(escapeJson(peerColor)).append("\",");
        sb.append("\"messageType\":\"").append(escapeJson(messageType)).append("\",");
        sb.append("\"ttlSeconds\":").append(ttlSeconds);
        if (mediaData != null) {
            sb.append(",\"mediaData\":\"").append(escapeJson(mediaData)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    public static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < ' ') {
                        String hex = "000" + Integer.toHexString(ch);
                        sb.append("\\u").append(hex.substring(hex.length() - 4));
                    } else {
                        sb.append(ch);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
