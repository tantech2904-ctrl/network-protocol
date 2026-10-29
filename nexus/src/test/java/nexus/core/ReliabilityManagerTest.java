package nexus.core;

import nexus.protocol.Packet;
import nexus.protocol.PacketCodec;
import nexus.protocol.PacketFlags;
import nexus.test.TestAssert;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ReliabilityManagerTest {

    public static void runAllTests() throws Exception {
        testSuccessfulAck();
        testRetransmissionOnAckTimeout();
        testRetryExhaustion();
        testQueueCapacityLimit();
        testGracefulStopFailsPending();
    }

    public static void testSuccessfulAck() throws Exception {
        MockUdpTransport transport = new MockUdpTransport();
        ReliabilityManager manager = new ReliabilityManager(transport, 500, 3, 10);
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 5000);
        Packet packet = Packet.createData(101, 1, "test ack".getBytes(StandardCharsets.UTF_8));

        AtomicReference<DeliveryStatus> statusRef = new AtomicReference<>();
        CompletableFuture<DeliveryStatus> future = manager.trackAndSend(packet, dest, statusRef::set);

        TestAssert.assertEquals(1, transport.getSentCount(), "Packet should be sent immediately");
        TestAssert.assertEquals(DeliveryStatus.UDP_SENT, statusRef.get(), "Status should be UDP_SENT");
        TestAssert.assertTrue(manager.isPending(1), "Sequence 1 should be pending");

        boolean acknowledged = manager.acknowledge(1);
        TestAssert.assertTrue(acknowledged, "Acknowledge should succeed");
        TestAssert.assertFalse(manager.isPending(1), "Sequence 1 should no longer be pending");

        DeliveryStatus result = future.get(1, TimeUnit.SECONDS);
        TestAssert.assertEquals(DeliveryStatus.PEER_ACKNOWLEDGED, result, "Future should complete with PEER_ACKNOWLEDGED");
        TestAssert.assertEquals(DeliveryStatus.PEER_ACKNOWLEDGED, statusRef.get(), "Callback should receive PEER_ACKNOWLEDGED");

        manager.stop();
    }

    public static void testRetransmissionOnAckTimeout() throws Exception {
        MockUdpTransport transport = new MockUdpTransport();
        ReliabilityManager manager = new ReliabilityManager(transport, 80, 2, 10);
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 5000);
        Packet packet = Packet.createData(102, 5, "retransmit test".getBytes(StandardCharsets.UTF_8));

        manager.trackAndSend(packet, dest, null);
        TestAssert.assertEquals(1, transport.getSentCount(), "Initial packet sent");

        Thread.sleep(250);

        TestAssert.assertTrue(transport.getSentCount() >= 2, "Should have retransmitted at least once");
        byte[] retransmittedBytes = transport.getSentPackets().get(1);
        Packet retransmittedPacket = PacketCodec.decode(retransmittedBytes, retransmittedBytes.length);

        TestAssert.assertTrue(PacketFlags.isRetransmit(retransmittedPacket.getFlags()), "Retransmit flag must be set");

        manager.stop();
    }

    public static void testRetryExhaustion() throws Exception {
        MockUdpTransport transport = new MockUdpTransport();
        ReliabilityManager manager = new ReliabilityManager(transport, 60, 2, 10);
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 5000);
        Packet packet = Packet.createData(103, 9, "exhaustion test".getBytes(StandardCharsets.UTF_8));

        AtomicReference<DeliveryStatus> statusRef = new AtomicReference<>();
        CompletableFuture<DeliveryStatus> future = manager.trackAndSend(packet, dest, statusRef::set);

        DeliveryStatus result = future.get(2, TimeUnit.SECONDS);
        TestAssert.assertEquals(DeliveryStatus.FAILED, result, "Future must complete with FAILED after max retries");
        TestAssert.assertEquals(DeliveryStatus.FAILED, statusRef.get(), "Callback must receive FAILED");
        TestAssert.assertFalse(manager.isPending(9), "Exhausted sequence must be removed from pending map");

        manager.stop();
    }

    public static void testQueueCapacityLimit() throws Exception {
        MockUdpTransport transport = new MockUdpTransport();
        ReliabilityManager manager = new ReliabilityManager(transport, 1000, 3, 2);
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 5000);

        manager.trackAndSend(Packet.createData(104, 1, new byte[0]), dest, null);
        manager.trackAndSend(Packet.createData(104, 2, new byte[0]), dest, null);

        boolean rejected = false;
        try {
            manager.trackAndSend(Packet.createData(104, 3, new byte[0]), dest, null);
        } catch (IllegalStateException e) {
            rejected = true;
        }
        TestAssert.assertTrue(rejected, "Should reject when pending queue capacity is reached");

        manager.stop();
    }

    public static void testGracefulStopFailsPending() throws Exception {
        MockUdpTransport transport = new MockUdpTransport();
        ReliabilityManager manager = new ReliabilityManager(transport, 2000, 3, 10);
        InetSocketAddress dest = new InetSocketAddress("127.0.0.1", 5000);

        CompletableFuture<DeliveryStatus> future = manager.trackAndSend(
                Packet.createData(105, 1, new byte[0]), dest, null);
        manager.stop();

        DeliveryStatus status = future.get(500, TimeUnit.MILLISECONDS);
        TestAssert.assertEquals(DeliveryStatus.FAILED, status, "Pending future must be failed on stop");
        TestAssert.assertEquals(0, manager.getPendingCount(), "Pending count must be 0 after stop");
    }
}

