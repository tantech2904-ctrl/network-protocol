package nexus.demo;

import nexus.core.DeliveryStatus;
import nexus.core.NexusEndpoint;
import nexus.core.SocketUdpTransport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class PeerNodeMain {

    public static void main(String[] args) {
        int localPort = 5000;
        String remoteHost = "127.0.0.1";
        int remotePort = 5001;

        for (int i = 0; i < args.length; i++) {
            if ("--local-port".equals(args[i]) && i + 1 < args.length) {
                localPort = Integer.parseInt(args[++i]);
            } else if ("--remote-host".equals(args[i]) && i + 1 < args.length) {
                remoteHost = args[++i];
            } else if ("--remote-port".equals(args[i]) && i + 1 < args.length) {
                remotePort = Integer.parseInt(args[++i]);
            }
        }

        try {
            SocketUdpTransport transport = new SocketUdpTransport();
            NexusEndpoint endpoint = new NexusEndpoint(transport);
            InetSocketAddress peerAddress = new InetSocketAddress(remoteHost, remotePort);
            endpoint.setPeerAddress(peerAddress);

            endpoint.setIncomingMessageListener((session, message) -> {
                System.out.println("\n[NEXUS PEER RECEIVED]: " + message);
                System.out.print("> ");
                System.out.flush();
            });

            endpoint.start(localPort);

            System.out.println("NEXUS Standalone Peer Node Running");
            System.out.println("Local UDP Port: " + localPort);
            System.out.println("Target Remote: " + remoteHost + ":" + remotePort);
            System.out.println("Type a message and press Enter to send. Type 'exit' to quit.\n");
            System.out.print("> ");
            System.out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if ("exit".equalsIgnoreCase(trimmed)) {
                    break;
                }
                if (!trimmed.isEmpty()) {
                    endpoint.sendMessage(trimmed, status -> {
                        if (status == DeliveryStatus.PEER_ACKNOWLEDGED) {
                            System.out.println(" [ACK RECEIVED]");
                        } else if (status == DeliveryStatus.FAILED) {
                            System.out.println(" [DELIVERY FAILED]");
                        }
                    });
                }
                System.out.print("> ");
                System.out.flush();
            }

            endpoint.stop();
            System.out.println("Peer node stopped.");

        } catch (Exception e) {
            System.err.println("PeerNode error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

