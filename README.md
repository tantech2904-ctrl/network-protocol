# NEXUS — Network Exchange and UDP Reliability System

NEXUS is an original, lightweight, reliable application-layer messaging protocol implemented in Java directly over UDP datagrams (`DatagramSocket` and `DatagramPacket`). It features a built-in Java web gateway exposing an RFC 6455 WebSocket interface and an understated, offline mobile web interface designed for direct offline communication across a Windows Mobile Hotspot without requiring an internet connection.

---

## 1. Project Purpose

Modern mobile messaging apps rely on cloud services, external servers, and permanent internet connectivity. When cellular towers or broadband internet connections are unavailable (disaster zones, remote outdoor environments, offline field operations, air-gapped facilities), local communication must rely exclusively on ad-hoc or local wireless links.

Standard web browsers cannot open arbitrary raw UDP sockets directly due to browser security restrictions. NEXUS solves this by cleanly decoupling:
1. **The Web Gateway**: Provides an offline HTTP and RFC 6455 WebSocket interface for modern mobile and desktop browsers (Android Chrome, iOS Safari, desktop browsers) without external dependencies, CDNs, or frameworks.
2. **The NEXUS Protocol Engine**: Implements binary packet framing, CRC32 error detection, monotonic sequence numbers, sliding-window duplicate suppression, acknowledgment tracking, adaptive retransmission, out-of-order packet reordering, and session lifecycle management over raw UDP datagrams.

---

## 2. Architecture Diagram

```
+-------------------------------------------------------------+
|         Mobile Web Browser (Android / iOS / Desktop)        |
+-------------------------------------------------------------+
                               |
                               | HTTP (index.html, styles.css, app.js)
                               | RFC 6455 WebSocket (/ws JSON Events)
                               v
+-------------------------------------------------------------+
|               Java Web Gateway (Port 8080)                  |
| - HTTP Static Resource Server (Zero external dependencies)  |
| - RFC 6455 WebSocket Server (Base64/SHA-1 handshake, frames)|
| - Message Service & History Buffer                          |
+-------------------------------------------------------------+
                               |
                               | Internal Java Service Interface
                               v
+-------------------------------------------------------------+
|                 NEXUS Protocol Engine                       |
| - SessionManager (Active sessions, expiration timers)       |
| - ReliabilityManager (ACK tracking, retransmission timer)   |
| - PacketCodec (26-byte binary header, CRC32 checksum)       |
| - UdpTransport (DatagramSocket / Virtual lossy seam)        |
+-------------------------------------------------------------+
                               |
                               | Custom Binary UDP Packets
                               | Magic: 0x4E585553 ("NXUS")
                               v
+-------------------------------------------------------------+
|               Remote / Local NEXUS Peer Node                |
| - UDP Port 5000 (Mode A: Loopback / Mode B: Wi-Fi LAN Peer) |
| - Packet decoding, CRC32 validation                         |
| - In-order delivery queue & duplicate filtering             |
| - Immediate UDP ACK generation & return transmission        |
+-------------------------------------------------------------+
```

---

## 3. Web Gateway and UDP Protocol Boundary

A critical architectural distinction is maintained between browser communication and NEXUS protocol communication:

| Layer | Protocol | Role | Network Boundary |
|---|---|---|---|
| **Client UI** | Browser DOM / JS | User interaction, bubble rendering | Browser sandbox |
| **Gateway Link** | HTTP / WebSocket (TCP) | Initial page load and real-time updates | Local Wi-Fi / Hotspot TCP port 8080 |
| **Protocol Engine** | Java internal API | Message submission and status notifications | In-process Java heap |
| **NEXUS Transport** | Raw UDP Datagrams | Reliable message exchange, ACKs, retransmissions | UDP socket (ports 5001 -> 5000) |

- **Browser to Gateway**: Uses standard TCP-based WebSocket frames (`ws://<ip>:8080/ws`) because web browsers cannot open arbitrary raw UDP sockets.
- **Gateway to Peer**: Uses custom binary NEXUS packets over raw UDP (`DatagramSocket` and `DatagramPacket`).
- **No Direct UI Shortcut**: The UI never directly appends an outgoing message to the chat history as delivered. Messages must traverse the protocol pipeline: `GATEWAY_ACCEPTED` -> `NEXUS_SUBMITTED` -> `UDP_SENT` -> `PEER_ACKNOWLEDGED` -> `DELIVERED_TO_APP`.

---

## 4. Binary Packet Format

NEXUS uses a fixed 26-byte binary header followed by a variable-length payload. All multibyte integer fields are serialized in **Big-Endian** (network byte order) via `java.nio.ByteBuffer`.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Magic Identifier                        |
|                  0x4E585553 (ASCII "NXUS")                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    Version    |  Packet Type  |             Flags             |
|     0x01      |  (1 to 9)     |    (0x0001: RETRANSMIT, etc.) |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Session Identifier                       |
|                   (32-bit signed integer)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        Sequence Number                        |
|                   (32-bit signed integer)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Acknowledgment Number                     |
|                   (32-bit signed integer)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|        Payload Length         |           Checksum            |
|       (16-bit unsigned)       |        (Bytes 0..1 of CRC32)  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     (Bytes 2..3 of CRC32)     |        Payload Data...        |
|      Integrity Checksum       |         (Variable length)     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Detailed Field Layout

| Offset | Width | Field Name | Type | Valid Values / Description |
|---|---|---|---|---|
| `0..3` | 4 bytes | Magic Identifier | `int` | Fixed constant `0x4E585553` (ASCII `"NXUS"`). Packets with invalid magic are rejected. |
| `4` | 1 byte | Protocol Version | `byte` | Current version is `0x01`. Mismatched versions are rejected. |
| `5` | 1 byte | Packet Type | `byte` | Packet type enum code (`0x01` to `0x09`). Unknown codes are rejected. |
| `6..7` | 2 bytes | Flags | `short` | Bitmask: `0x0001` (RETRANSMIT), `0x0002` (URGENT), `0x0000` (NONE). |
| `8..11` | 4 bytes | Session ID | `int` | Unique session identifier shared between client and peer. |
| `12..15` | 4 bytes | Sequence Number | `int` | Monotonically increasing sequence number assigned to DATA packets. |
| `16..19` | 4 bytes | Acknowledgment Number | `int` | Sequence number being acknowledged by an ACK packet. |
| `20..21` | 2 bytes | Payload Length | `short` | Unsigned 16-bit payload length (0 to 1400 bytes). Must match actual buffer length. |
| `22..25` | 4 bytes | Checksum | `int` | Standard CRC32 checksum computed over header (with checksum=0) + payload. |
| `26..N` | Variable | Payload | `byte[]` | UTF-8 application message text. Maximum size: 1400 bytes (to prevent IP fragmentation). |

### Checksum Calculation Specification
- Checksum algorithm: Standard IEEE 802.3 CRC32 via `java.util.zip.CRC32`.
- Exact calculation procedure:
  1. Header fields 0..21 are populated.
  2. Header checksum bytes (offsets 22..25) are set to `0x00000000`.
  3. Payload bytes are appended after offset 25.
  4. CRC32 is calculated over all `26 + payloadLength` bytes.
  5. The resulting 32-bit CRC value is written into bytes 22..25 in big-endian order.
- Exact verification procedure:
  1. The packet buffer length is validated (`>= 26` and `== 26 + payloadLength`).
  2. The received 32-bit checksum at offsets 22..25 is extracted.
  3. Bytes 22..25 in a working buffer are set to 0.
  4. CRC32 is calculated over the entire buffer.
  5. If calculated CRC32 does not match the received checksum, the packet is rejected immediately.
- CRC32 detects transmission errors and bit flips; it is not cryptographic authentication.

---

## 5. Packet Type Definitions

| Code | Type Name | Purpose | Payload |
|---|---|---|---|
| `0x01` | `CONNECT` | Initiates a reliable session between client and peer. | Optional connection parameters. |
| `0x02` | `ACCEPT` | Confirms session establishment and synchronizes initial sequence numbers. | Empty or session attributes. |
| `0x03` | `DATA` | Encapsulates user application text messages with a sequence number. | UTF-8 encoded text message. |
| `0x04` | `ACK` | Acknowledges receipt of a specific sequence number (`ackNumber`). | Empty. |
| `0x05` | `HEARTBEAT` | Periodic keep-alive ping to maintain active session state and detect peer disconnection. | Empty. |
| `0x06` | `HEARTBEAT_ACK` | Keep-alive response confirming peer responsiveness. | Empty. |
| `0x07` | `DISCONNECT` | Graceful session termination request. Clears pending state and timers. | Empty. |
| `0x08` | `DISCONNECT_ACK` | Acknowledgment of graceful session closure. | Empty. |
| `0x09` | `ERROR` | Communicates a fatal or protocol-level exception between endpoints. | UTF-8 error description. |
| `0x0A` | `FILE_CHUNK` | Sequenced binary image/file transfer slice with reassembly metadata. | `FileChunkHeader` + chunk binary payload. |

---

## 5.1. NEXUS File Transfer Protocol (FTP) & Ephemeral Images

To support sending images and documents without exceeding the MTU or inducing IP fragmentation:
1. **Chunk Framing**: Large files are sliced into binary chunks ($\le 1000$ bytes) using [`FileChunkHeader`](file:///c:/Users/tanis/Desktop/netwrok%20proto/nexus/src/main/java/nexus/core/FileChunkHeader.java):
   - Bytes 0..3: `transferId` (32-bit unique identifier)
   - Bytes 4..5: `chunkIndex` (16-bit slice sequence)
   - Bytes 6..7: `totalChunks` (16-bit total count)
   - Bytes 8..9: `ttlSeconds` (ephemeral self-destruct countdown)
   - Bytes 10..11: `mimeTypeLength`
   - Bytes 12..N: `mimeType` UTF-8 string + raw chunk bytes
2. **Reliable Reassembly**: Every chunk is transmitted as a sequenced `FILE_CHUNK` packet, verified with CRC32, and individually acknowledged over UDP. [`FileTransferManager`](file:///c:/Users/tanis/Desktop/netwrok%20proto/nexus/src/main/java/nexus/core/FileTransferManager.java) tracks in-progress chunks and reassembles the complete file when all slices arrive.
3. **Ephemeral Self-Destruct**: Images can be sent with an optional TTL (e.g. `/image 15`). Once viewed in the browser, an active countdown begins, and the image is wiped from memory and DOM once the timer expires.

---

## 6. Sequence-Number and Acknowledgment Behavior

- **Session Initialization**: Every session establishes an independent outgoing sequence counter starting at 1 (`AtomicInteger`) and expects incoming sequence number 1.
- **Monotonic Progression**: Each outgoing `DATA` packet receives the next sequence number (`session.nextOutgoingSequenceNumber()`).
- **Immediate Acknowledgment**: When an endpoint receives a valid `DATA` packet, it immediately generates and transmits an `ACK` packet whose `ackNumber` equals the received packet's `sequenceNumber`.
- **Sender Tracking**: The sender stores unacknowledged packets in `ReliabilityManager`'s thread-safe collection (`ConcurrentHashMap<Integer, PendingPacket>`).
- **ACK Matching**: When an `ACK` packet arrives with `ackNumber = N`, the sender looks up sequence `N` in the pending collection, removes it, and resolves the message's delivery status to `PEER_ACKNOWLEDGED`.
- **Session Isolation**: Multiple concurrent sessions maintain distinct sequence states; sequence numbers in Session A do not affect or collide with Session B.

---

## 7. Retransmission Algorithm

```
Message Submitted
       │
       ▼
Track in Pending Map (seq = N)
Send UDP Packet (flags = 0)
Start Retransmission Timer (tick = 50ms)
       │
       ├─────────────────────────────────┐
       ▼                                 ▼
ACK Received within Timeout?      Timeout Expired (now - lastSent >= ackTimeoutMs)?
       │                                 │
      YES                               YES
       │                                 │
       ▼                                 ▼
Remove from Pending Map           retryCount < maxRetries?
Notify Status: PEER_ACKNOWLEDGED         │
                                  ┌──────┴──────┐
                                 YES            NO
                                  │             │
                                  ▼             ▼
                   Increment retryCount   Remove from Pending Map
                   Set FLAG_RETRANSMIT    Notify Status: FAILED
                   Recalculate CRC32      (Retry exhaustion)
                   Resend UDP Datagram
```

- **ACK Timeout**: Configurable (`DEFAULT_ACK_TIMEOUT_MS = 600ms`).
- **Max Retries**: Configurable (`DEFAULT_MAX_RETRIES = 4`).
- **Retransmit Flag**: Retransmitted packets have bit 0 (`0x0001: FLAG_RETRANSMIT`) set in the header flags field and the CRC32 checksum is recalculated.
- **Exhaustion Handling**: When `retryCount >= maxRetries`, the packet is permanently purged from pending queues and the application status is explicitly marked as `FAILED`.

---

## 8. Duplicate Suppression and Out-of-Order Policy

### Duplicate Suppression
Network delays or lost ACKs can cause the sender to retransmit a packet the receiver already accepted.
- **Sliding History Window**: Each `Session` maintains a bounded set of recently received sequence numbers (`LinkedHashSet`, bounded to `RECEIVE_WINDOW_SIZE * 2`).
- **Duplicate Check**:
  ```java
  isDuplicate = receivedSequences.contains(seq) || (seq < expectedSeq - RECEIVE_WINDOW_SIZE);
  ```
- **Action on Duplicate**:
  1. The receiver immediately re-sends an `ACK` for the duplicate sequence number so the sender can clear its retransmission timer.
  2. The payload is **not** passed to the application layer a second time, guaranteeing that duplicate UDP packets never create duplicate messages in the chat.

### Out-of-Order Handling
UDP datagrams may arrive out of order due to network routing variations.
- **Reorder Buffer**: Each `Session` contains a `TreeMap<Integer, Packet> reorderBuffer` sorted by sequence number.
- **Delivery Policy**:
  - If `seq == expectedSequenceNumber`: Deliver immediately to the application. Increment `expectedSequenceNumber`. Then inspect `reorderBuffer`: if packet `expectedSequenceNumber` is buffered, remove and deliver it, repeating until a gap is encountered.
  - If `seq > expectedSequenceNumber` and within the receive window (`expectedSeq + 64`): Buffer the packet in `reorderBuffer`. An `ACK` is sent immediately so the sender knows the packet arrived.
  - If `seq < expectedSequenceNumber`: Handled by the duplicate suppression logic.

---

## 9. Configuration Values

All protocol and network parameters are centrally managed in `nexus.protocol.ProtocolConfig` and can be overridden via command-line arguments or environment variables:

| Parameter | Default Value | Description | Command-Line Flag |
|---|---|---|---|
| `MAGIC` | `0x4E585553` (`"NXUS"`) | Protocol magic header identifier | Hardcoded protocol constant |
| `VERSION` | `0x01` | Protocol version | Hardcoded protocol constant |
| `HEADER_SIZE` | `26` bytes | Fixed header byte length | Hardcoded protocol constant |
| `MAX_PAYLOAD_SIZE` | `1400` bytes | Max UTF-8 payload per UDP datagram | Hardcoded protocol constant |
| `DEFAULT_WEB_PORT` | `8080` | HTTP & WebSocket gateway port | `--web-port <port>` / `PORT` |
| `DEFAULT_NEXUS_CLIENT_PORT`| `5001` | Local NEXUS engine UDP port | `--udp-port <port>` |
| `DEFAULT_NEXUS_SERVER_PORT`| `5000` | Peer NEXUS engine UDP port | `--peer-port <port>` |
| `PEER_HOST` | `127.0.0.1` | Target peer IP address | `--peer-ip <ip>` |
| `MODE` | `"A"` | Operating mode ("A" loopback, "B" peer) | `--mode <A\|B>` |
| `DEFAULT_ACK_TIMEOUT_MS` | `600` ms | Time to wait before UDP retransmission | Core parameter |
| `DEFAULT_MAX_RETRIES` | `4` retries | Retransmissions before failure | Core parameter |
| `DEFAULT_HEARTBEAT_INTERVAL`| `5000` ms | Keep-alive heartbeat interval | Core parameter |
| `DEFAULT_SESSION_TIMEOUT_MS`| `15000` ms | Session expiration after inactivity | Core parameter |
| `RECEIVE_WINDOW_SIZE` | `64` packets | Out-of-order reorder buffer capacity | Core parameter |

---

## 10. JDK and Build Requirements

- **JDK Version**: Java 21 or later (Developed and verified with Oracle OpenJDK 24.0.2).
- **Operating System**: Windows 10/11 (or Linux / macOS with standard POSIX shells).
- **Build Tools**:
  - No mandatory build tool required! The project compiles directly using standard `javac` and runs with `java`.
  - Windows batch scripts (`build.bat`, `run.bat`) and PowerShell scripts (`build.ps1`, `run.ps1`) are included.
  - A standard Maven `pom.xml` is also provided for users who prefer Maven.
- **External Dependencies**: Zero runtime dependencies! Pure Java standard library (`java.net`, `java.nio`, `java.util`, `java.security`).

---

## 11. Build and Test Commands

### Option A: Using the PowerShell Build Script (Recommended on Windows)
```powershell
.\build.ps1
```
This script:
1. Copies all static web files into `bin/static/`.
2. Compiles all main and test classes with `javac` into `bin/`.
3. Executes the full automated test suite via `nexus.test.TestRunner`.

### Option B: Using the Windows Batch Script
```cmd
build.bat
```

### Option C: Manual Command-Line Compilation
```powershell
# 1. Create bin directory
mkdir bin -Force; mkdir bin/static -Force
Copy-Item "nexus/src/main/resources/static/*" "bin/static/" -Force

# 2. Compile all sources
& "javac" -encoding UTF-8 -d bin (Get-ChildItem -Path "nexus/src/main/java", "nexus/src/test/java" -Filter *.java -Recurse | ForEach-Object { $_.FullName })

# 3. Execute all automated tests
& "java" -cp bin nexus.test.TestRunner
```

### Option D: Using Maven (if Maven is installed)
```bash
mvn test
```

---

## 12. Application Startup Commands

### Starting the Server in Mode A (Local Demonstration Loopback)
```powershell
.\run.ps1
```
Or with `run.bat`:
```cmd
run.bat
```

### Starting with Custom Ports
```powershell
.\run.ps1 -webPort 8080 -udpPort 5001 -peerPort 5000 -mode A
```

### Running the Live Reliability & Retransmission Demo
To demonstrate all 6 protocol scenarios directly in your console (packet loss, retransmission, duplicate suppression, out-of-order sequencing, retry exhaustion, CRC32 bit-flip rejection):
```powershell
.\demo-reliability.ps1
```
*(Or `demo-reliability.bat` in Command Prompt)*

### Demonstrating Retransmission Live in the Mobile Web Interface
1. Open the web interface at `http://localhost:8080/` (or on your Android phone).
2. **Method 1 (Interactive Showcase Buttons)**: Tap the waveform icon in the top right to expand the Protocol Inspector drawer. Tap:
   - **Drop 1 Packet (Retransmit)**: Drops the 1st transmission. The message status visibly transitions: `UDP Sent` -> `Retransmitting (attempt 1/4)...` -> `Acknowledged (after 1 retry)`.
   - **Simulate Offline Peer**: Drops all transmissions. You see the live retransmission counter increment `(attempt 1/4)... (attempt 4/4)...` until the state turns red: `Failed (retries exhausted)`.
   - **Send UDP Photo (FTP)**: Chunks an offline PNG photo into 3 binary UDP packets, reassembles it, and displays it with a 15s self-destruct timer!
   - **Simulate Bob / Sensor**: Injects real incoming UDP transmissions from peer nodes on ports 5002 and 5003.
3. **Method 2 (Interactive Slash Commands)**:
   Type `/` in the message box to open the floating command palette:
   - `/name <handle>` : Instantly changes your public display handle across the network.
   - `/drop <text>` : Drops 1st UDP packet (shows live retransmission).
   - `/fail <text>` : Drops all packets (shows retry exhaustion).
   - `/peer <alice|bob|sensor> <text>` : Targets a specific peer node over UDP.
   - `/sim <alice|bob|sensor> [msg]` : Triggers incoming UDP transmission from that peer.
   - `/image [ttlSeconds]` : Sends chunked photo over UDP with ephemeral timer.
   - `/stats` : Shows protocol and connection statistics.
   - `/clear` : Clears chat history.
   - `/help` : Displays all available protocol showcase commands.

### Multi-Peer Group Chat & Avatar Identity
- **Customizable Peer Identity**: Tap your circular avatar button on the top right to open the Profile Settings sheet. Customize your display handle and choose from 7 Apple-inspired avatar palette swatches. Identities are stored locally via `localStorage` and synchronized across the network.
- **Active Peer Roster Stack**: An overlapping circular avatar stack in the header shows all connected peers and simulated nodes in real time, displaying their unique initials, color swatches, and live online count (`2 online`).
- **Group Chat Visual Differentiation**:
  - **Your Messages (Right)**: Blue bubbles (`#007AFF`) aligned to the right with live UDP delivery receipts (`UDP Sent` -> `Acknowledged`).
  - **Peer Messages (Left)**: Aligned to the left beside each sender's circular avatar, displaying their initials, color-coded name badge, and soft gray bubble (`#E9E9EB`).
   - `/help` : Displays all available commands.

### Starting in Mode B (Two-Device Protocol Mode)
On Machine 1 (Laptop running Web Gateway and NEXUS Engine):
```powershell
.\run.ps1 -webPort 8080 -udpPort 5001 -peerHost "192.168.137.20" -peerPort 5000 -mode B
```
On Machine 2 (Second laptop running the Standalone Peer Node):
```powershell
.\run-peer.ps1 -localPort 5000 -remoteHost "192.168.137.1" -remotePort 5001
```

---

## 13. Windows Hotspot Setup

To connect an Android phone to the laptop without an internet connection:

1. On the Windows laptop, open **Windows Settings** (`Win + I`).
2. Navigate to **Network & Internet** > **Mobile Hotspot**.
3. Toggle Mobile Hotspot to **On**.
4. In **Share my internet connection over**, select **Wi-Fi**. (Even if the laptop has no internet access, Windows Mobile Hotspot will broadcast a local Wi-Fi network and assign IP addresses via local DHCP).
5. Click **Edit** to view or set the **Network Name (SSID)** and **Network Password**.
6. On your Android phone:
   - Open **Wi-Fi Settings**.
   - Select the laptop's Mobile Hotspot SSID.
   - Enter the password and connect.
   - When Android alerts "No internet connection", tap the notification and select **Stay Connected**.

---

## 14. Finding the Laptop's IPv4 Address

When `run.bat` or `run.ps1` starts, NEXUS automatically scans active network adapters and displays the exact URL to open on Android.

You can also find the IP manually:
1. Open PowerShell or Command Prompt.
2. Run:
   ```cmd
   ipconfig
   ```
3. Locate the adapter named **Wireless LAN adapter Local Area Connection\* X** or **Wi-Fi** (often `192.168.137.1` for Windows Mobile Hotspot).
4. Note the **IPv4 Address** (e.g., `192.168.137.1`).

---

## 15. Opening the Interface on Android

1. Ensure the Android phone is connected to the laptop's Mobile Hotspot.
2. Open Chrome or any browser on the Android phone.
3. In the address bar, type the URL displayed by NEXUS:
   ```
   http://192.168.137.1:8080/
   ```
   *(Replace `192.168.137.1` with your laptop's actual hotspot IPv4 address).*
4. The understated messaging interface will load instantly.
5. The status indicator at the top will turn green (`Gateway Connected • NEXUS UDP Ready`).
6. Type a message in the composer and tap Send. The message will be encoded into binary NEXUS UDP packets, acknowledged over UDP, and displayed in the conversation.

---

## 16. Windows Firewall Troubleshooting

If the Android browser fails to load the page:
1. Windows Firewall may be blocking incoming connections on port 8080 or UDP port 5000/5001.
2. Run PowerShell as Administrator and add an allow rule for port 8080 and UDP ports:
   ```powershell
   New-NetFirewallRule -DisplayName "NEXUS Web Gateway" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow
   New-NetFirewallRule -DisplayName "NEXUS UDP Protocol" -Direction Inbound -LocalPort 5000,5001 -Protocol UDP -Action Allow
   ```
3. Ensure the Mobile Hotspot network profile is set to **Private**.

---

## 17. Offline-Operation Verification

To verify that NEXUS operates 100% offline:
1. Disconnect the laptop from external Wi-Fi, Ethernet, and cellular dongles.
2. Ensure the Android phone has Mobile Data turned **OFF**.
3. Enable Windows Mobile Hotspot and connect the phone.
4. Open `http://<laptop-ip>:8080/` in Android Chrome.
5. Verify in browser DevTools (Network tab):
   - Zero external requests.
   - Zero Google Fonts or font CDN calls (uses system fonts).
   - Zero external scripts, libraries, or CDNs.
   - Only 4 local resources are requested: `index.html`, `styles.css`, `app.js`, and `ws://.../ws`.
6. Send messages: messages are transmitted reliably over UDP.

---

## 18. Local Demonstration vs. Two-Device UDP Communication

| Characteristic | Mode A: Local Demonstration | Mode B: Two-Device UDP Mode |
|---|---|---|
| **Topology** | Single Laptop (Hotspot + Browser) | Two Computers on Wi-Fi Hotspot |
| **Web Gateway** | Runs on Laptop (Port 8080) | Runs on Laptop 1 (Port 8080) |
| **Client UDP Socket** | Binds to UDP port 5001 | Binds to UDP port 5001 on Laptop 1 |
| **Peer UDP Socket** | Binds to UDP port 5000 on `127.0.0.1` | Binds to UDP port 5000 on Laptop 2 |
| **UDP Path** | Traverses OS UDP network loopback stack | Traverses actual wireless Wi-Fi radio waves |
| **Echo / Reply** | Local peer node echoes messages after 300ms | Second peer types custom replies in console |
| **Best For** | Viva demonstrations, single-laptop evaluation | Distributed networking verification |

---

## 19. Known Limitations

1. **Browser UDP Restriction**: Browsers cannot bind UDP sockets directly. The Java Web Gateway is necessary to bridge browser HTTP/WebSocket to binary UDP datagrams.
2. **Local Wi-Fi Encryption**: While WPA2/WPA3 Hotspot security encrypts the wireless link at Layer 2, application-layer UDP datagrams are in cleartext. CRC32 detects accidental corruption, not deliberate cryptographic tampering.
3. **No Distributed Crash Recovery**: Sequence numbers and session states are maintained in-memory. If the server process crashes, active sessions must re-handshake upon restart.
4. **NAT Traversal**: Designed for single-subnet local Wi-Fi / hotspot environments. Does not include STUN/TURN/ICE for symmetric NAT traversal across the public internet.

---

## 20. Future Improvements

1. **DTLS / ChaCha20-Poly1305 Security**: Implement optional cryptographic confidentiality and authentication over the UDP transport.
2. **Native Android APK**: Build a lightweight Android app using Java/Kotlin `DatagramSocket` communicating directly over UDP to eliminate the browser HTTP/WebSocket gateway completely.
3. **Selective Acknowledgment (SACK)**: Expand from single-packet ACK to bitmask selective acknowledgment for high-throughput batch transmissions.
4. **Adaptive RTT / Congestion Control**: Implement Jacobson's RTT variance estimation algorithm and Van Jacobson congestion window algorithms.

---

## 21. Viva Preparation Questions and Concise Answers

### Q1: What is NEXUS and what problem does it solve?
**Answer**: NEXUS is an original reliable messaging protocol implemented in Java directly over raw UDP datagrams. It solves the challenge of local, offline mobile messaging in emergency or air-gapped environments by pairing a custom reliable binary UDP protocol with a zero-dependency Java web gateway accessible from mobile browsers over a local hotspot.

### Q2: Why did you build reliability over UDP instead of just using TCP?
**Answer**: TCP is a rigid, kernel-level stream protocol with head-of-line blocking, high connection establishment overhead, and fixed kernel congestion algorithms. Building reliability over UDP demonstrates an understanding of fundamental transport protocols, packet framing, binary serialization, sequence numbering, duplicate detection, sliding windows, and custom retransmission policies.

### Q3: Why doesn't the phone browser connect directly using UDP?
**Answer**: Modern web browsers do not provide APIs for web pages to open arbitrary raw UDP sockets for security reasons (preventing malicious websites from launching UDP amplification attacks or scanning local networks). Therefore, an architectural bridge is necessary: the browser communicates over local HTTP/WebSocket with the Java Web Gateway, which in turn encodes and transmits binary packets over raw UDP datagrams.

### Q4: How does NEXUS detect corrupted packets?
**Answer**: NEXUS computes an IEEE 802.3 standard 32-bit CRC32 checksum over the entire 26-byte header (with the checksum field set to zero) plus the payload. When a packet is received, the receiver zeroes out the checksum field, calculates CRC32 over the buffer, and compares it with the received checksum. Mismatched packets are rejected immediately.

### Q5: How are duplicate packets handled?
**Answer**: Each session maintains expected sequence numbers and a sliding history window of recently received sequence numbers. If a packet arrives with a sequence number that has already been received, the endpoint immediately sends an ACK back to silence the sender's retransmission timer, but suppresses the payload from being delivered to the application layer again.

### Q6: How does out-of-order packet delivery work?
**Answer**: Each session maintains an in-order reorder buffer (`TreeMap<Integer, Packet>`). If packet 3 arrives before packet 2, packet 3 is buffered in the reorder buffer and an ACK is sent. When packet 2 arrives, packet 2 is delivered to the application, followed immediately by draining packet 3 from the reorder buffer, ensuring strict sequential delivery to the application.

### Q7: What are the different message delivery states?
**Answer**:
1. `GATEWAY_ACCEPTED`: Web gateway validated the input and assigned an ID.
2. `NEXUS_SUBMITTED`: Message queued into the NEXUS protocol engine.
3. `UDP_SENT`: Binary packet transmitted via `DatagramSocket.send`.
4. `PEER_ACKNOWLEDGED`: Remote endpoint verified CRC32 and returned an ACK over UDP.
5. `DELIVERED_TO_APP`: The remote peer's application layer processed the message.
6. `FAILED`: Delivery failed after maximum retransmissions were exhausted.

### Q8: Does the application require internet access?
**Answer**: No. All resources (HTML, CSS, JavaScript, and Java runtime) are 100% self-contained and hosted locally on the laptop. The Windows Mobile Hotspot provides a local Wi-Fi network and DHCP addressing without requiring an active internet connection.

---

## 22. Strict Code Quality & Style Compliance

As mandated by project specifications, **every single Java source file in this project contains exactly zero comments** (no line comments `//`, no block comments `/*`, no Javadoc comments `/**`). All architectural explanations, field layouts, and technical documentation are maintained exclusively in this `README.md`.

