package nexus.core;

public enum DeliveryStatus {
    GATEWAY_ACCEPTED,
    NEXUS_SUBMITTED,
    UDP_SENT,
    RETRANSMITTING,
    PEER_ACKNOWLEDGED,
    DELIVERED_TO_APP,
    FAILED
}
