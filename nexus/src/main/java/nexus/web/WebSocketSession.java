package nexus.web;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

public class WebSocketSession {
    private final String id;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Consumer<String> messageConsumer;
    private final Consumer<WebSocketSession> closeConsumer;
    private volatile boolean open;

    public WebSocketSession(
            Socket socket,
            Consumer<String> messageConsumer,
            Consumer<WebSocketSession> closeConsumer) throws IOException {
        this.id = UUID.randomUUID().toString();
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.messageConsumer = messageConsumer;
        this.closeConsumer = closeConsumer;
        this.open = true;
    }

    public String getId() {
        return id;
    }

    public boolean isOpen() {
        return open && !socket.isClosed();
    }

    public void startReading() {
        try {
            while (open && !socket.isClosed()) {
                int firstByte = in.read();
                if (firstByte == -1) {
                    break;
                }

                int opcode = firstByte & 0x0F;

                int secondByte = in.read();
                if (secondByte == -1) {
                    break;
                }

                boolean masked = (secondByte & 0x80) != 0;
                long payloadLength = secondByte & 0x7F;

                if (payloadLength == 126) {
                    int b1 = in.read();
                    int b2 = in.read();
                    if (b1 == -1 || b2 == -1) {
                        break;
                    }
                    payloadLength = ((b1 & 0xFF) << 8) | (b2 & 0xFF);
                } else if (payloadLength == 127) {
                    byte[] lenBytes = new byte[8];
                    readFully(lenBytes);
                    payloadLength = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLength = (payloadLength << 8) | (lenBytes[i] & 0xFF);
                    }
                }

                if (payloadLength > 65536) {
                    break;
                }

                byte[] mask = new byte[4];
                if (masked) {
                    readFully(mask);
                }

                byte[] payload = new byte[(int) payloadLength];
                readFully(payload);

                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                    }
                }

                if (opcode == 0x8) {
                    sendClose();
                    break;
                } else if (opcode == 0x9) {
                    sendPong(payload);
                } else if (opcode == 0x1) {
                    String message = new String(payload, StandardCharsets.UTF_8);
                    if (messageConsumer != null) {
                        messageConsumer.accept(message);
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = in.read(buffer, offset, buffer.length - offset);
            if (count == -1) {
                throw new EOFException("Premature end of WebSocket frame");
            }
            offset += count;
        }
    }

    public synchronized void sendText(String text) {
        if (!isOpen()) {
            return;
        }
        try {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            int len = payload.length;

            out.write(0x81);

            if (len <= 125) {
                out.write(len);
            } else if (len <= 65535) {
                out.write(126);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((len >> (8 * i)) & 0xFF));
                }
            }

            out.write(payload);
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    private synchronized void sendPong(byte[] pingPayload) {
        if (!isOpen()) {
            return;
        }
        try {
            out.write(0x8A);
            out.write(pingPayload.length & 0x7F);
            out.write(pingPayload);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private synchronized void sendClose() {
        if (!isOpen()) {
            return;
        }
        try {
            out.write(0x88);
            out.write(0);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (closeConsumer != null) {
            closeConsumer.accept(this);
        }
    }
}

