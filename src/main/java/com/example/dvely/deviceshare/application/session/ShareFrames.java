package com.example.dvely.deviceshare.application.session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 기기 터널 프레임: {@code [kind 1바이트][stream id 4바이트 BE][payload]}. 설계 §4.1.
 *
 * <p>1단계에서 stream id 는 늘 0 이다. 나중 HTTP 중계(0x01~0x25)가 요청마다 id 를 쓴다.</p>
 */
public final class ShareFrames {

    public static final int HEADER_BYTES = 5;

    public static final int HELLO = 0x41;
    public static final int WELCOME = 0x42;
    public static final int GOODBYE = 0x43;
    public static final int SCREEN_CONFIG = 0x51;
    public static final int SCREEN_FRAME = 0x52;
    public static final int INPUT = 0x61;
    public static final int CONTROL_REQUEST = 0x62;
    public static final int CONTROL_DECISION = 0x63;
    public static final int PRESENCE = 0x64;

    private ShareFrames() {
    }

    public record Frame(int kind, int streamId, byte[] payload) {

        public String payloadText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    public static ByteBuffer encode(int kind, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        buffer.put((byte) kind);
        buffer.putInt(0);
        buffer.put(payload);
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer encode(int kind, String json) {
        return encode(kind, json.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException 헤더보다 짧으면 */
    public static Frame decode(ByteBuffer buffer) {
        ByteBuffer b = buffer.duplicate();
        if (b.remaining() < HEADER_BYTES) {
            throw new IllegalArgumentException("프레임이 헤더보다 짧습니다: " + b.remaining() + "바이트");
        }
        int kind = b.get() & 0xff;
        int streamId = b.getInt();
        byte[] payload = new byte[b.remaining()];
        b.get(payload);
        return new Frame(kind, streamId, payload);
    }
}
