package nexus.web;

public class ClientProfile {
    private final String id;
    private volatile String name;
    private volatile String color;
    private volatile long lastSeen;

    public ClientProfile(String id, String name, String color) {
        this.id = id;
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "Peer";
        this.color = color != null && !color.trim().isEmpty() ? color.trim() : "#007AFF";
        this.lastSeen = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "Peer";
        this.lastSeen = System.currentTimeMillis();
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color != null && !color.trim().isEmpty() ? color.trim() : "#007AFF";
        this.lastSeen = System.currentTimeMillis();
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void touch() {
        this.lastSeen = System.currentTimeMillis();
    }

    public String toJson() {
        return "{"
                + "\"id\":\"" + ApplicationMessage.escapeJson(id) + "\","
                + "\"name\":\"" + ApplicationMessage.escapeJson(name) + "\","
                + "\"color\":\"" + ApplicationMessage.escapeJson(color) + "\""
                + "}";
    }
}

