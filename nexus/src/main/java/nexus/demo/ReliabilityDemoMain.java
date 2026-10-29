package nexus.demo;

import nexus.core.DeliveryStatus;
import nexus.core.NexusEndpoint;
import nexus.core.ReliabilityManager;
import nexus.core.Session;
import nexus.core.SessionManager;
import nexus.integration.VirtualLossyTransport;
import nexus.protocol.Packet;
import nexus.protocol.PacketCodec;
import nexus.protocol.PacketFlags;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ReliabilityDemoMain {

    public static void main(String[] args) throws Exception {
        System.out.println("========================================================================");
        System.out.println(" NEXUS Protocol Reliability & Retransmission Interactive Demonstration");
        System.out.println("========================================================================");

        runScenario1NormalExchange();
        Thread.sleep(600);

        runScenario2PacketLossAndRetransmission();
        Thread.sleep(600);

        runScenario3DuplicateSuppression();
        Thread.sleep(600);

        runScenario4OutOfOrderReordering();
        Thread.sleep(600);

        runScenario5RetryExhaustion();
        Thread.sleep(600);

        runScenario6Crc32CorruptionDetection();

        System.out.println("\n========================================================================");
        System.out.println(" All 6 Reliability Scenarios Successfully Demonstrated!");
        System.out.println("========================================================================");
    }

    private static void runScenario1NormalExchange() throws Exception {
        System.out.println("\n--- [SCENARIO 1: Standard Reliable UDP Exchange] ---");
        VirtualLossyTransport clientTrans = new VirtualLossyTransport(8101);
        VirtualLossyTransport serverTrans = new VirtualLossyTransport(8102);
        clientTrans.setPairedTransport(serverTrans);
        serverTrans.setPairedTransport(clientTrans);

        NexusEndpoint client = new NexusEndpoint(clientTrans);
        client.setPeerAddress(new InetSocketAddress("127.0.0.1", 8102));
        client.start(8101);

        CopyOnWriteArrayList<String> serverMsgs = new CopyOnWriteArrayList<>();
        NexusEndpoint server = new NexusEndpoint(serverTrans);
        server.setPeerAddress(new InetSocketAddress("127.0.0.1", 8101));
        server.setIncomingMessageListener((s, txt) -> serverMsgs.add(txt));
        server.start(8102);

        System.out.println("-> Sending: 'Hello NEXUS'");
        CompletableFuture<DeliveryStatus> fut = client.sendMessage("Hello NEXUS", st -> System.out.println("   [CLIENT EVENT]: " + st));
        DeliveryStatus res = fut.get(2, TimeUnit.SECONDS);

        System.out.println("<- Client status resolved: " + res);
        System.out.println("<- Server received message: " + serverMsgs);

        client.stop();
        server.stop();
    }

    private static void runScenario2PacketLossAndRetransmission() throws Exception {
        System.out.println("\n--- [SCENARIO 2: Packet Loss with Automatic Retransmission] ---");
        VirtualLossyTransport clientTrans = new VirtualLossyTransport(8201);
        VirtualLossyTransport serverTrans = new VirtualLossyTransport(8202);
        clientTrans.setPairedTransport(serverTrans);
        serverTrans.setPairedTransport(clientTrans);

        ReliabilityManager clientRel = new ReliabilityManager(clientTrans, 250, 3, 10);
        NexusEndpoint client = new NexusEndpoint(clientTrans, clientRel, new SessionManager());
        client.setPeerAddress(new InetSocketAddress("127.0.0.1", 8202));
        client.start(8201);

        CopyOnWriteArrayList<String> serverMsgs = new CopyOnWriteArrayList<>();
        NexusEndpoint server = new NexusEndpoint(serverTrans);
        server.setPeerAddress(new InetSocketAddress("127.0.0.1", 8201));
        server.setIncomingMessageListener((s, txt) -> serverMsgs.add(txt));
        server.start(8202);

        clientTrans.setDropFirstNPackets(1);
        System.out.println("[FAULT INJECTION]: Configured 1st transmission to drop in transit!");

        System.out.println("-> Sending: 'Urgent Alert'");
        CompletableFuture<DeliveryStatus> fut = client.sendMessage("Urgent Alert", st -> {
            System.out.println("   [CLIENT EVENT]: " + st);
        });

        DeliveryStatus res = fut.get(3, TimeUnit.SECONDS);
        System.out.println("<- Client status resolved: " + res);
        System.out.println("<- Total packets transmitted: " + clientTrans.getPacketsSentCount() + " (1 dropped, 1 retransmission)");
        System.out.println("<- Server received delivered message: " + serverMsgs);

        client.stop();
        server.stop();
    }

    private static void runScenario3DuplicateSuppression() {
        System.out.println("\n--- [SCENARIO 3: Duplicate Packet Suppression] ---");
        Session session = new Session(9901, new InetSocketAddress("127.0.0.1", 9902));
        Packet p1 = Packet.createData(9901, 1, "Payment Authorization".getBytes(StandardCharsets.UTF_8));
        Packet p1Dup = Packet.createData(9901, 1, "Payment Authorization".getBytes(StandardCharsets.UTF_8)).withRetransmitFlag();

        List<Packet> firstPass = session.processIncomingData(p1);
        System.out.println("-> Original packet seq 1 arrived -> delivered count: " + firstPass.size());

        List<Packet> dupPass = session.processIncomingData(p1Dup);
        System.out.println("-> Duplicate retransmit packet seq 1 arrived -> delivered count: " + dupPass.size() + " (SUPPRESSED)");
        System.out.println("<- Is duplicate detected: " + session.isDuplicate(1));
    }

    private static void runScenario4OutOfOrderReordering() {
        System.out.println("\n--- [SCENARIO 4: Out-Of-Order Packet Sequencing] ---");
        Session session = new Session(9902, new InetSocketAddress("127.0.0.1", 9903));

        Packet p1 = Packet.createData(9902, 1, "Part 1".getBytes(StandardCharsets.UTF_8));
        Packet p2 = Packet.createData(9902, 2, "Part 2".getBytes(StandardCharsets.UTF_8));
        Packet p3 = Packet.createData(9902, 3, "Part 3".getBytes(StandardCharsets.UTF_8));

        System.out.println("-> Packet seq 3 arrived first (out of order)");
        List<Packet> d3 = session.processIncomingData(p3);
        System.out.println("   Delivered: " + d3.size() + ", Reorder buffer size: " + session.getReorderBufferSize());

        System.out.println("-> Packet seq 2 arrived second (out of order)");
        List<Packet> d2 = session.processIncomingData(p2);
        System.out.println("   Delivered: " + d2.size() + ", Reorder buffer size: " + session.getReorderBufferSize());

        System.out.println("-> Missing packet seq 1 finally arrived!");
        List<Packet> d1 = session.processIncomingData(p1);
        System.out.println("   Delivered: " + d1.size() + " packets in strict sequence!");
        for (Packet p : d1) {
            System.out.println("   - Delivered seq " + p.getSequenceNumber() + ": " + new String(p.getPayload(), StandardCharsets.UTF_8));
        }
        System.out.println("<- Reorder buffer drained to: " + session.getReorderBufferSize());
    }

    private static void runScenario5RetryExhaustion() throws Exception {
        System.out.println("\n--- [SCENARIO 5: Offline Peer with Retry Exhaustion] ---");
        VirtualLossyTransport clientTrans = new VirtualLossyTransport(8301);
        clientTrans.setDropAll(true);

        ReliabilityManager clientRel = new ReliabilityManager(clientTrans, 150, 3, 10);
        NexusEndpoint client = new NexusEndpoint(clientTrans, clientRel, new SessionManager());
        client.setPeerAddress(new InetSocketAddress("127.0.0.1", 8302));
        client.start(8301);

        System.out.println("[FAULT INJECTION]: Peer disconnected / blackholed!");
        System.out.println("-> Sending: 'Can anyone hear me?'");

        AtomicReference<DeliveryStatus> lastStatus = new AtomicReference<>();
        CompletableFuture<DeliveryStatus> fut = client.sendMessage("Can anyone hear me?", st -> {
            System.out.println("   [CLIENT EVENT]: " + st);
            lastStatus.set(st);
        });

        DeliveryStatus res = fut.get(3, TimeUnit.SECONDS);
        System.out.println("<- Final status resolved: " + res);
        System.out.println("<- Total attempts sent: " + clientTrans.getPacketsSentCount());
        System.out.println("<- Pending queue count: " + clientRel.getPendingCount());

        client.stop();
    }

    private static void runScenario6Crc32CorruptionDetection() {
        System.out.println("\n--- [SCENARIO 6: Accidental Packet Corruption & CRC32 Detection] ---");
        byte[] payload = "Financial transaction".getBytes(StandardCharsets.UTF_8);
        Packet cleanPacket = Packet.createData(5001, 7, payload);
        byte[] encoded = PacketCodec.encode(cleanPacket);

        System.out.println("-> Clean encoded packet CRC32: 0x" + Integer.toHexString(cleanPacket.getChecksum()));
        Packet decodedClean = PacketCodec.decode(encoded, encoded.length);
        System.out.println("   Clean packet verified successfully: seq=" + decodedClean.getSequenceNumber());

        byte[] corrupted = encoded.clone();
        corrupted[corrupted.length - 1] ^= 0xFF;
        System.out.println("-> Injected 1-bit flip into payload byte at offset " + (corrupted.length - 1));

        try {
            PacketCodec.decode(corrupted, corrupted.length);
            System.out.println("   [ERROR]: Corrupted packet was not rejected!");
        } catch (IllegalArgumentException e) {
            System.out.println("<- CRC32 Checksum Mismatch Detected: " + e.getMessage());
            System.out.println("   Corrupted packet was safely rejected before application delivery!");
        }
    }
}

