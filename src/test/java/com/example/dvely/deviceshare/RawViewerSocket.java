package com.example.dvely.deviceshare;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 공유 오리진 Host 헤더를 실어 보내는 최소 WebSocket 클라이언트(테스트 전용).
 *
 * <p>JDK HttpClient 는 Host 를 막고, Tomcat 클라이언트는 덮어쓴 Host 를 보내지 않았다. 브라우저는 주소창의 호스트를 그대로
 * 보내므로, 그 모습을 흉내 내려고 핸드셰이크를 직접 쓴다. 서버 → 클라이언트 프레임(마스크 없음)과 이어 붙이기만 읽는다.</p>
 */
final class RawViewerSocket implements AutoCloseable {

    record Message(boolean text, byte[] payload) {
        String asText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private final Socket socket;
    private final OutputStream out;
    final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
    final int status;

    RawViewerSocket(int port, String host, String path) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(0);
        out = socket.getOutputStream();
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(keyBytes) + "\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        String statusLine = readLine(in);
        status = Integer.parseInt(statusLine.split(" ")[1]);
        while (!readLine(in).isEmpty()) {
            // 응답 헤더는 쓰지 않는다
        }
        if (status == 101) {
            Thread.ofVirtual().start(() -> readLoop(in));
        } else {
            socket.close();
        }
    }

    void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else {
            frame.write(0x80 | 126);
            frame.write((payload.length >> 8) & 0xff);
            frame.write(payload.length & 0xff);
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        synchronized (out) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    private void readLoop(DataInputStream in) {
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        boolean partialText = false;
        try {
            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                long length = b1 & 0x7f;
                if (length == 126) {
                    length = in.readUnsignedShort();
                } else if (length == 127) {
                    length = in.readLong();
                }
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                if (opcode == 0x8) {
                    return; // close
                }
                if (opcode == 0x9 || opcode == 0xA) {
                    continue; // ping/pong
                }
                if (opcode != 0x0) {
                    partialText = opcode == 0x1;
                    partial.reset();
                }
                partial.writeBytes(payload);
                if (fin) {
                    messages.add(new Message(partialText, partial.toByteArray()));
                    partial.reset();
                }
            }
        } catch (IOException e) {
            // 연결 종료
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
