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
    /** 서버 → 기기, JSON {@code {}}. 기기는 H.264 키 프레임을 새로 만든다(설계 §12.2). */
    public static final int KEYFRAME_REQUEST = 0x65;
    /** 서버 → 기기, JSON {@code {probeId, x, y}}. 그 점의 요소를 풀어 달라(설계 §14.4). */
    public static final int PIN_PROBE = 0x66;
    /** 기기 → 서버. {@code [JSON 길이 u32][JSON][잘라 낸 이미지 길이 u32][JPEG][화면 JPEG]}(§14.3). */
    public static final int PIN_RESULT = 0x67;
    /** 서버 → 기기, JSON. 새 피드백 알림. 손님 것({@code state: NEW})이면 CLI 가 받을지 묻는다. */
    public static final int FEEDBACK_NOTICE = 0x68;
    /** 기기 → 서버, JSON {@code {feedbackId, accepted}}. 주인의 받기·무시. */
    public static final int FEEDBACK_DECISION = 0x69;

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
