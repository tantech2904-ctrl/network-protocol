package nexus.protocol;

public final class ProtocolConfig {
    public static final int MAGIC = 0x4E585553;
    public static final byte VERSION = 0x01;
    public static final int HEADER_SIZE = 26;
    public static final int MAX_PAYLOAD_SIZE = 1400;
    public static final int DEFAULT_ACK_TIMEOUT_MS = 600;
    public static final int DEFAULT_MAX_RETRIES = 4;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 5000;
    public static final int DEFAULT_SESSION_TIMEOUT_MS = 15000;
    public static final int DEFAULT_WEB_PORT = 8080;
    public static final int DEFAULT_NEXUS_SERVER_PORT = 5000;
    public static final int DEFAULT_NEXUS_CLIENT_PORT = 5001;
    public static final int RECEIVE_WINDOW_SIZE = 64;

    private ProtocolConfig() {
    }
}

