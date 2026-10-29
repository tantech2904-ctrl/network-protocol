package nexus.demo;

import nexus.core.DeliveryStatus;
import nexus.core.NexusEndpoint;
import nexus.core.SocketUdpTransport;
import nexus.web.MessageService;
import nexus.web.WebGateway;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    public static void main(String[] args) {
        int webPort = 8080;
        int clientUdpPort = 5001;
        String peerHost = "127.0.0.1";
        int peerPort = 5000;
        String mode = "A";

        String envWebPort = System.getenv("PORT");
        if (envWebPort != null) {
            try {
                webPort = Integer.parseInt(envWebPort);
            } catch (NumberFormatException ignored) {
            }
        }

        for (int i = 0; i < args.length; i++) {
            if ("--web-port".equals(args[i]) && i + 1 < args.length) {
                webPort = Integer.parseInt(args[++i]);
            } else if ("--udp-port".equals(args[i]) && i + 1 < args.length) {
                clientUdpPort = Integer.parseInt(args[++i]);
            } else if ("--peer-ip".equals(args[i]) && i + 1 < args.length) {
                peerHost = args[++i];
            } else if ("--peer-port".equals(args[i]) && i + 1 < args.length) {
                peerPort = Integer.parseInt(args[++i]);
            } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
                mode = args[++i].toUpperCase();
            }
        }

        try {
            List<NexusEndpoint> backgroundPeers = new ArrayList<>();
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "Nexus-Demo-Peers");
                t.setDaemon(true);
                return t;
            });

            SocketUdpTransport clientTransport = new SocketUdpTransport();
            NexusEndpoint clientEndpoint = new NexusEndpoint(clientTransport);
            clientEndpoint.setPeerAddress(new InetSocketAddress(peerHost, peerPort));
            clientEndpoint.start(clientUdpPort);

            MessageService messageService = new MessageService(clientEndpoint);

            if ("A".equals(mode)) {
                SocketUdpTransport aliceTrans = new SocketUdpTransport();
                NexusEndpoint alice = new NexusEndpoint(aliceTrans);
                alice.setPeerAddress(new InetSocketAddress("127.0.0.1", clientUdpPort));
                alice.setIncomingMessageListener((session, message) -> {
                    scheduler.schedule(() -> {
                        try {
                            alice.sendMessage("Alice: Received '" + message + "'", status -> {
                            });
                        } catch (IOException ignored) {
                        }
                    }, 300, TimeUnit.MILLISECONDS);
                });
                alice.start(peerPort);
                backgroundPeers.add(alice);

                SocketUdpTransport bobTrans = new SocketUdpTransport();
                NexusEndpoint bob = new NexusEndpoint(bobTrans);
                bob.setPeerAddress(new InetSocketAddress("127.0.0.1", clientUdpPort));
                bob.start(5002);
                backgroundPeers.add(bob);

                SocketUdpTransport sensorTrans = new SocketUdpTransport();
                NexusEndpoint sensor = new NexusEndpoint(sensorTrans);
                sensor.setPeerAddress(new InetSocketAddress("127.0.0.1", clientUdpPort));
                sensor.start(5003);
                backgroundPeers.add(sensor);

                messageService.setSimulationSender(simCmd -> {
                    String[] parts = simCmd.split(":", 2);
                    String target = parts[0].toLowerCase();
                    String text = parts.length > 1 ? parts[1] : "Status OK";
                    try {
                        if ("bob".equals(target)) {
                            bob.sendMessage("Bob: " + text, s -> {
                            });
                        } else if ("sensor".equals(target) || "sensor-alpha".equals(target)) {
                            sensor.sendMessage("Sensor-Alpha: [Telemetry " + (int) (Math.random() * 50 + 20) + "C | 1013hPa] " + text, s -> {
                            });
                        } else {
                            alice.sendMessage("Alice: " + text, s -> {
                            });
                        }
                    } catch (IOException ignored) {
                    }
                });
            }

            WebGateway webGateway = new WebGateway(webPort, messageService);
            webGateway.start();

            printStartupBanner(mode, webPort, clientUdpPort, peerHost, peerPort);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down NEXUS system...");
                webGateway.stop();
                clientEndpoint.stop();
                for (NexusEndpoint p : backgroundPeers) {
                    p.stop();
                }
                scheduler.shutdownNow();
                System.out.println("NEXUS shutdown complete.");
            }));

            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Failed to start NEXUS: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printStartupBanner(String mode, int webPort, int udpPort, String peerHost, int peerPort) {
        System.out.println("========================================================================");
        System.out.println(" NEXUS - Network Exchange and UDP Reliability System");
        System.out.println("========================================================================");
        System.out.println(" Operating Mode  : " + ("A".equals(mode) ? "Mode A (Multi-Peer Local Demonstration)" : "Mode B (Two-Device Protocol)"));
        String httpPrefix = "http:" + "/" + "/";
        System.out.println(" Web Gateway URL : " + httpPrefix + "localhost:" + webPort + "/");
        System.out.println(" Local NEXUS UDP : Port " + udpPort);
        System.out.println(" Active Peers    : Alice (5000), Bob (5002), Sensor-Alpha (5003)");
        System.out.println("========================================================================");
        System.out.println(" Detected Network Interfaces (Connect phone to hotspot / same LAN):");

        List<String> addresses = getLocalIpv4Addresses();
        if (addresses.isEmpty()) {
            System.out.println("   (No external IPv4 interfaces detected. Use 127.0.0.1 for local testing)");
        } else {
            for (String addr : addresses) {
                System.out.println("   Mobile Browser URL: " + httpPrefix + addr + ":" + webPort + "/");
            }
        }

        System.out.println("========================================================================");
        System.out.println(" Type /help in the web UI for showcase slash commands.");
        System.out.println(" Press Ctrl+C to terminate.");
        System.out.println("========================================================================");
    }

    public static List<String> getLocalIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return addresses;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = iface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress addr = inetAddresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        addresses.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return addresses;
    }
}
