package nexus.core;

import nexus.protocol.Packet;
import nexus.protocol.PacketFlags;
import nexus.protocol.PacketType;

import java.net.InetSocketAddress;

public class ProtocolTelemetry {
    private final long timestamp;
    private final String direction;
    private final PacketType type;
    private final int sequenceNumber;
    private final int ackNumber;
    private final short flags;
    private final int checksum;
    private final int length;
    private final String remoteAddress;
    private final long latencyMs;

    public ProtocolTelemetry(
            String direction,
            Packet packet,
            InetSocketAddress remote,
            long latencyMs) {
        this.timestamp = System.currentTimeMillis();
        this.direction = direction;
        this.type = packet.getType();
        this.sequenceNumber = packet.getSequenceNumber();
        this.ackNumber = packet.getAckNumber();
        this.flags = packet.getFlags();
        this.checksum = packet.getChecksum();
        this.length = packet.getPayloadLength();
        this.remoteAddress = remote != null ? remote.getHostString() + ":" + remote.getPort() : "unknown";
        this.latencyMs = latencyMs;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getDirection() {
        return direction;
    }

    public PacketType getType() {
        return type;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public int getAckNumber() {
        return ackNumber;
    }

    public short getFlags() {
        return flags;
    }

    public int getChecksum() {
        return checksum;
    }

    public int getLength() {
        return length;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String toJson() {
        boolean isRetransmit = PacketFlags.isRetransmit(flags);
        return "{"
                + "\"timestamp\":" + timestamp + ","
                + "\"direction\":\"" + direction + "\","
                + "\"type\":\"" + type.name() + "\","
                + "\"seq\":" + sequenceNumber + ","
                + "\"ack\":" + ackNumber + ","
                + "\"flags\":\"" + (isRetransmit ? "RETRANSMIT" : "NONE") + "\","
                + "\"checksum\":\"0x" + Integer.toHexString(checksum).toUpperCase() + "\","
                + "\"length\":" + length + ","
                + "\"remote\":\"" + remoteAddress + "\","
                + "\"latencyMs\":" + latencyMs
                + "}";
    }
}

