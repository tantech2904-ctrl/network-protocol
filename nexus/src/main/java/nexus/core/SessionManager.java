package nexus.core;

import nexus.protocol.ProtocolConfig;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SessionManager {
    private final int maxSessions;
    private final long sessionTimeoutMs;
    private final Map<Integer, Session> sessionsById;
    private final ScheduledExecutorService cleanupExecutor;
    private Consumer<Session> sessionExpiredListener;
    private volatile boolean running;

    public SessionManager() {
        this(ProtocolConfig.DEFAULT_SESSION_TIMEOUT_MS, 128);
    }

    public SessionManager(long sessionTimeoutMs, int maxSessions) {
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.maxSessions = maxSessions;
        this.sessionsById = new ConcurrentHashMap<>();
        this.running = true;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Nexus-Session-Cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions, 50, 50, TimeUnit.MILLISECONDS);
    }

    public void setSessionExpiredListener(Consumer<Session> listener) {
        this.sessionExpiredListener = listener;
    }

    public Session createSession(InetSocketAddress remoteAddress) {
        if (!running) {
            throw new IllegalStateException("SessionManager is stopped");
        }
        if (sessionsById.size() >= maxSessions) {
            throw new IllegalStateException("Maximum active sessions reached: " + maxSessions);
        }

        int sessionId;
        do {
            sessionId = ThreadLocalRandom.current().nextInt(10000, Integer.MAX_VALUE);
        } while (sessionsById.containsKey(sessionId));

        Session session = new Session(sessionId, remoteAddress);
        sessionsById.put(sessionId, session);
        return session;
    }

    public Session registerSession(int sessionId, InetSocketAddress remoteAddress) {
        if (!running) {
            throw new IllegalStateException("SessionManager is stopped");
        }
        if (sessionsById.size() >= maxSessions) {
            throw new IllegalStateException("Maximum active sessions reached: " + maxSessions);
        }
        Session session = new Session(sessionId, remoteAddress);
        sessionsById.put(sessionId, session);
        return session;
    }

    public Session getSession(int sessionId) {
        return sessionsById.get(sessionId);
    }

    public Session findSessionByAddress(InetSocketAddress address) {
        for (Session session : sessionsById.values()) {
            if (session.getRemoteAddress().equals(address)) {
                return session;
            }
        }
        return null;
    }

    public Session removeSession(int sessionId) {
        Session session = sessionsById.remove(sessionId);
        if (session != null) {
            session.setState(SessionState.CLOSED);
        }
        return session;
    }

    public Collection<Session> getAllSessions() {
        return sessionsById.values();
    }

    public int getActiveSessionCount() {
        return sessionsById.size();
    }

    public void cleanupExpiredSessions() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Session>> iterator = sessionsById.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Integer, Session> entry = iterator.next();
            Session session = entry.getValue();
            if (now - session.getLastActivityTimestamp() > sessionTimeoutMs) {
                iterator.remove();
                session.setState(SessionState.CLOSED);
                if (sessionExpiredListener != null) {
                    sessionExpiredListener.accept(session);
                }
            }
        }
    }

    public void stop() {
        running = false;
        cleanupExecutor.shutdownNow();
        for (Session session : sessionsById.values()) {
            session.setState(SessionState.CLOSED);
        }
        sessionsById.clear();
    }
}
