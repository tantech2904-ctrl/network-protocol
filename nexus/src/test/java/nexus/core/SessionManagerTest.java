package nexus.core;

import nexus.protocol.Packet;
import nexus.test.TestAssert;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SessionManagerTest {

    public static void runAllTests() throws Exception {
        testSessionCreationAndIsolation();
        testDuplicatePacketDetection();
        testOutOfOrderPacketBufferingAndSequentialDelivery();
        testSessionExpiration();
        testGracefulSessionRemoval();
    }

    public static void testSessionCreationAndIsolation() {
        SessionManager manager = new SessionManager(10000, 10);
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 6001);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.1", 6002);

        Session session1 = manager.createSession(addr1);
        Session session2 = manager.createSession(addr2);

        TestAssert.assertTrue(session1.getSessionId() != session2.getSessionId(), "Session IDs must be distinct");
        TestAssert.assertEquals(1, session1.nextOutgoingSequenceNumber(), "Session 1 start seq must be 1");
        TestAssert.assertEquals(2, session1.nextOutgoingSequenceNumber(), "Session 1 next seq must be 2");
        TestAssert.assertEquals(1, session2.nextOutgoingSequenceNumber(), "Session 2 seq must be isolated at 1");

        manager.stop();
    }

    public static void testDuplicatePacketDetection() {
        Session session = new Session(201, new InetSocketAddress("127.0.0.1", 7001));
        Packet p1 = Packet.createData(201, 1, "first".getBytes(StandardCharsets.UTF_8));
        Packet p1Duplicate = Packet.createData(201, 1, "first duplicate".getBytes(StandardCharsets.UTF_8));

        List<Packet> deliveredFirst = session.processIncomingData(p1);
        TestAssert.assertEquals(1, deliveredFirst.size(), "First packet must be delivered");

        List<Packet> deliveredDuplicate = session.processIncomingData(p1Duplicate);
        TestAssert.assertEquals(0, deliveredDuplicate.size(), "Duplicate packet must not be delivered");
        TestAssert.assertTrue(session.isDuplicate(1), "Sequence 1 must be detected as duplicate");
    }

    public static void testOutOfOrderPacketBufferingAndSequentialDelivery() {
        Session session = new Session(202, new InetSocketAddress("127.0.0.1", 7002));
        Packet p1 = Packet.createData(202, 1, "packet-1".getBytes(StandardCharsets.UTF_8));
        Packet p2 = Packet.createData(202, 2, "packet-2".getBytes(StandardCharsets.UTF_8));
        Packet p3 = Packet.createData(202, 3, "packet-3".getBytes(StandardCharsets.UTF_8));

        List<Packet> deliveredP3 = session.processIncomingData(p3);
        TestAssert.assertEquals(0, deliveredP3.size(), "Packet 3 arrived early and must be buffered");
        TestAssert.assertEquals(1, session.getReorderBufferSize(), "Reorder buffer size must be 1");

        List<Packet> deliveredP2 = session.processIncomingData(p2);
        TestAssert.assertEquals(0, deliveredP2.size(), "Packet 2 arrived before 1 and must be buffered");
        TestAssert.assertEquals(2, session.getReorderBufferSize(), "Reorder buffer size must be 2");

        List<Packet> deliveredP1 = session.processIncomingData(p1);
        TestAssert.assertEquals(3, deliveredP1.size(), "When packet 1 arrives, all 3 packets must be delivered sequentially");
        TestAssert.assertEquals(1, deliveredP1.get(0).getSequenceNumber(), "First delivered must be seq 1");
        TestAssert.assertEquals(2, deliveredP1.get(1).getSequenceNumber(), "Second delivered must be seq 2");
        TestAssert.assertEquals(3, deliveredP1.get(2).getSequenceNumber(), "Third delivered must be seq 3");
        TestAssert.assertEquals(0, session.getReorderBufferSize(), "Reorder buffer must now be empty");
        TestAssert.assertEquals(4, session.getExpectedIncomingSequenceNumber(), "Expected incoming seq must advance to 4");
    }

    public static void testSessionExpiration() throws Exception {
        SessionManager manager = new SessionManager(50, 10);
        AtomicBoolean expired = new AtomicBoolean(false);
        manager.setSessionExpiredListener(s -> expired.set(true));

        manager.createSession(new InetSocketAddress("127.0.0.1", 8001));
        TestAssert.assertEquals(1, manager.getActiveSessionCount(), "Active session count must be 1");

        Thread.sleep(150);
        manager.cleanupExpiredSessions();

        TestAssert.assertEquals(0, manager.getActiveSessionCount(), "Session must be expired and removed");
        TestAssert.assertTrue(expired.get(), "Session expired listener must be triggered");

        manager.stop();
    }

    public static void testGracefulSessionRemoval() {
        SessionManager manager = new SessionManager(10000, 10);
        Session session = manager.createSession(new InetSocketAddress("127.0.0.1", 8002));
        int id = session.getSessionId();

        TestAssert.assertNotNull(manager.getSession(id), "Session must exist");
        Session removed = manager.removeSession(id);
        TestAssert.assertNotNull(removed, "Removed session must not be null");
        TestAssert.assertEquals(SessionState.CLOSED, removed.getState(), "Removed session state must be CLOSED");
        TestAssert.assertNull(manager.getSession(id), "Session must no longer exist in manager");

        manager.stop();
    }
}

