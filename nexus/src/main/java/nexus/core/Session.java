package nexus.core;

import nexus.protocol.Packet;
import nexus.protocol.ProtocolConfig;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Session {
    private final int sessionId;
    private final InetSocketAddress remoteAddress;
    private volatile SessionState state;
    private final AtomicInteger outgoingSequenceNumber;
    private int expectedIncomingSequenceNumber;
    private final Set<Integer> receivedSequences;
    private final TreeMap<Integer, Packet> reorderBuffer;
    private volatile long lastActivityTimestamp;
    private volatile String peerName;
    private volatile String peerColor;

    public Session(int sessionId, InetSocketAddress remoteAddress) {
        this.sessionId = sessionId;
        this.remoteAddress = remoteAddress;
        this.state = SessionState.CONNECTING;
        this.outgoingSequenceNumber = new AtomicInteger(1);
        this.expectedIncomingSequenceNumber = 1;
        this.receivedSequences = new LinkedHashSet<>();
        this.reorderBuffer = new TreeMap<>();
        this.lastActivityTimestamp = System.currentTimeMillis();
        this.peerName = "Peer-" + remoteAddress.getPort();
        this.peerColor = "#007AFF";
    }

    public int getSessionId() {
        return sessionId;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public SessionState getState() {
        return state;
    }

    public void setState(SessionState state) {
        this.state = state;
        updateActivity();
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

    public int nextOutgoingSequenceNumber() {
        return outgoingSequenceNumber.getAndIncrement();
    }

    public int getExpectedIncomingSequenceNumber() {
        return expectedIncomingSequenceNumber;
    }

    public synchronized void setExpectedIncomingSequenceNumber(int sequenceNumber) {
        this.expectedIncomingSequenceNumber = sequenceNumber;
    }

    public synchronized boolean isDuplicate(int sequenceNumber) {
        return receivedSequences.contains(sequenceNumber)
                || (sequenceNumber < expectedIncomingSequenceNumber - ProtocolConfig.RECEIVE_WINDOW_SIZE);
    }

    public synchronized List<Packet> processIncomingData(Packet packet) {
        updateActivity();
        List<Packet> readyToDeliver = new ArrayList<>();
        int seq = packet.getSequenceNumber();

        if (isDuplicate(seq)) {
            return readyToDeliver;
        }

        markReceived(seq);

        if (seq == expectedIncomingSequenceNumber) {
            readyToDeliver.add(packet);
            expectedIncomingSequenceNumber++;

            while (reorderBuffer.containsKey(expectedIncomingSequenceNumber)) {
                Packet nextPacket = reorderBuffer.remove(expectedIncomingSequenceNumber);
                readyToDeliver.add(nextPacket);
                expectedIncomingSequenceNumber++;
            }
        } else if (seq > expectedIncomingSequenceNumber
                && seq < expectedIncomingSequenceNumber + ProtocolConfig.RECEIVE_WINDOW_SIZE) {
            if (reorderBuffer.size() < ProtocolConfig.RECEIVE_WINDOW_SIZE) {
                reorderBuffer.put(seq, packet);
            }
        }

        return readyToDeliver;
    }

    private synchronized void markReceived(int seq) {
        receivedSequences.add(seq);
        if (receivedSequences.size() > ProtocolConfig.RECEIVE_WINDOW_SIZE * 2) {
            Integer oldest = receivedSequences.iterator().next();
            receivedSequences.remove(oldest);
        }
    }

    public long getLastActivityTimestamp() {
        return lastActivityTimestamp;
    }

    public void updateActivity() {
        this.lastActivityTimestamp = System.currentTimeMillis();
    }

    public synchronized int getReorderBufferSize() {
        return reorderBuffer.size();
    }
}
