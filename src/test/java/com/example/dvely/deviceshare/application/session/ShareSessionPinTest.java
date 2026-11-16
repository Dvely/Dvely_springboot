package com.example.dvely.deviceshare.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 공유 세션의 짚기(설계 §14.1·§14.4). 짚기는 입력이 아니라 읽기라서 조작권 없이도 된다. */
class ShareSessionPinTest {

    private static final long SECOND = 1_000_000_000L;
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};

    private final ObjectMapper json = new ObjectMapper();
    private long nanos = 1_000 * SECOND;
    private ShareSession session;
    private FakeDevice device;
    private FakeViewer guest;
    private String guestId;

    @BeforeEach
    void setUp() {
        session = new ShareSession(9L, DeviceShareKind.WEB, LocalDateTime.now().plusHours(1), json, 5, 1024 * 1024, 120,
                () -> nanos);
        device = new FakeDevice();
        session.attachDevice(device);
        session.onDeviceFrame(device, frame(ShareFrames.HELLO, "{\"cliVersion\":\"0.3.0\",\"kind\":\"WEB\",\"features\":[\"pin\"]}"));
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, "{\"format\":\"jpeg\",\"width\":390,\"height\":844}"));
        guest = new FakeViewer();
        guestId = session.join(guest, "민지", false);
    }

    @Test
    void 기기가_짚기를_알면_config_에_pin_이_실리고_조작권_없이도_짚기가_기기로_간다() {
        assertThat(guest.last("config").path("pin").asBoolean()).isTrue();

        probe(guestId, 0.42, 0.17);

        JsonNode sent = device.lastJson(ShareFrames.PIN_PROBE);
        assertThat(sent.path("probeId").asText()).isEqualTo("p1");
        assertThat(sent.path("x").asDouble()).isEqualTo(0.42);
        assertThat(guest.last("pin").path("state").asText()).isEqualTo("pending");
        assertThat(device.count(ShareFrames.INPUT)).isZero();
    }

    @Test
    void 기기가_돌려준_결과는_테두리와_이름만_뷰어로_가고_소스_후보는_가지_않는다() {
        probe(guestId, 0.42, 0.17);
        session.onDeviceFrame(device, result("""
                {"probeId":"p1","ok":true,"element":{"type":"button","text":"저장","bounds":{"x":0.1,"y":0.2,"w":0.3,"h":0.1}},
                 "components":[{"name":"SaveButton","file":"src/SaveButton.tsx"}],
                 "sourceHints":[{"file":"src/SaveButton.tsx","line":3,"reason":"COMPONENT"}]}
                """, JPEG, JPEG));

        JsonNode pin = guest.last("pin");
        assertThat(pin.path("state").asText()).isEqualTo("resolved");
        assertThat(pin.path("label").asText()).isEqualTo("<button> \"저장\"");
        assertThat(pin.path("component").asText()).isEqualTo("SaveButton");
        assertThat(pin.path("bounds").path("w").asDouble()).isEqualTo(0.3);
        assertThat(pin.toString()).doesNotContain("src/SaveButton.tsx").doesNotContain("sourceHints");

        ClaimedPin claimed = session.claimPin(guestId, "p1").orElseThrow();
        assertThat(claimed.viewerName()).isEqualTo("민지");
        assertThat(claimed.owner()).isFalse();
        assertThat(claimed.crop()).isEqualTo(JPEG);
        assertThat(claimed.resolution().description().path("sourceHints")).hasSize(1);
        assertThat(session.claimPin(guestId, "p1")).as("한 번 가져가면 끝").isEmpty();
    }

    @Test
    void JPEG_가_아니거나_상한을_넘는_이미지는_버린다() {
        probe(guestId, 0.5, 0.5);
        session.onDeviceFrame(device, result("{\"probeId\":\"p1\",\"ok\":false}", "<svg>".getBytes(StandardCharsets.UTF_8),
                new byte[ShareSession.MAX_SCREEN_BYTES + 1]));

        ClaimedPin claimed = session.claimPin(guestId, "p1").orElseThrow();
        assertThat(claimed.crop()).isNull();
        assertThat(claimed.screen()).isNull();
        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("NO_ELEMENT");
    }

    @Test
    void 남의_짚기_결과는_가져갈_수_없고_5분이_지나면_사라진다() {
        FakeViewer other = new FakeViewer();
        String otherId = session.join(other, "남", false);
        probe(guestId, 0.5, 0.5);
        session.onDeviceFrame(device, result("{\"probeId\":\"p1\",\"ok\":false}", null, null));

        assertThat(session.claimPin(otherId, "p1")).isEmpty();
        nanos += ShareSession.RESOLVED_TTL_NANOS + SECOND;
        session.expirePins();
        assertThat(session.claimPin(guestId, "p1")).isEmpty();
    }

    @Test
    void 답이_5초_안에_없으면_timeout_이고_늦게_온_결과는_버린다() {
        probe(guestId, 0.5, 0.5);
        nanos += ShareSession.PROBE_TIMEOUT_NANOS + 1;
        session.expirePins();

        assertThat(guest.last("pin").path("state").asText()).isEqualTo("failed");
        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("timeout");
        session.onDeviceFrame(device, result("{\"probeId\":\"p1\",\"ok\":false}", null, null));
        assertThat(session.claimPin(guestId, "p1")).isEmpty();
    }

    @Test
    void 짚기는_보는_사람마다_초당_한_번_기다리는_것은_공유마다_세_개까지() {
        probe(guestId, 0.1, 0.1);
        probe(guestId, 0.2, 0.2);
        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("busy");

        List<String> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            others.add(session.join(new FakeViewer(), "v" + i, false));
        }
        others.forEach(id -> probe(id, 0.3, 0.3));
        assertThat(device.count(ShareFrames.PIN_PROBE)).isEqualTo(ShareSession.MAX_PENDING_PROBES);
    }

    @Test
    void 기기가_짚기를_모르거나_끊기면_짚을_수_없다() {
        ShareSession old = new ShareSession(10L, DeviceShareKind.WEB, LocalDateTime.now().plusHours(1), json, 5,
                1024 * 1024, 120, () -> nanos);
        FakeDevice oldCli = new FakeDevice();
        old.attachDevice(oldCli);
        old.onDeviceFrame(oldCli, frame(ShareFrames.HELLO, "{\"cliVersion\":\"0.2.0\",\"kind\":\"WEB\"}"));
        old.onDeviceFrame(oldCli, frame(ShareFrames.SCREEN_CONFIG, "{\"format\":\"jpeg\",\"width\":1,\"height\":1}"));
        FakeViewer viewer = new FakeViewer();
        String id = old.join(viewer, "a", false);
        assertThat(viewer.last("config").path("pin").asBoolean()).isFalse();
        old.onViewerMessage(id, msg("{\"type\":\"pin-probe\",\"x\":0.5,\"y\":0.5}"), Instant.now());
        assertThat(viewer.last("pin").path("reason").asText()).isEqualTo("unsupported");
        assertThat(oldCli.count(ShareFrames.PIN_PROBE)).isZero();

        probe(guestId, 0.5, 0.5);
        session.detachDevice(device);
        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("offline");
    }

    @Test
    void 지켜보는_사람에게만_상태를_알리고_나가면_지운다() {
        FakeViewer other = new FakeViewer();
        session.join(other, "남", false);
        session.watch(guestId, 12L);

        session.notifyWatchers(12L, "{\"type\":\"feedback\",\"id\":12,\"state\":\"FIXED\"}");
        assertThat(guest.last("feedback").path("state").asText()).isEqualTo("FIXED");
        assertThat(other.types()).doesNotContain("feedback");

        session.leave(guestId);
        session.notifyWatchers(12L, "{\"type\":\"feedback\",\"id\":12,\"state\":\"CONFIRMED\"}");
        assertThat(guest.count("feedback")).isEqualTo(1);
    }

    @Test
    void 짚기를_아는_기기에만_피드백_알림을_보낸다() {
        assertThat(session.sendToPinDevice(ShareFrames.FEEDBACK_NOTICE, "{}")).isTrue();
        assertThat(device.count(ShareFrames.FEEDBACK_NOTICE)).isEqualTo(1);
        session.detachDevice(device);
        assertThat(session.sendToPinDevice(ShareFrames.FEEDBACK_NOTICE, "{}")).isFalse();
    }

    // ------------------------------------------------------------------ 도우미

    private void probe(String viewerId, double x, double y) {
        session.onViewerMessage(viewerId, msg("{\"type\":\"pin-probe\",\"x\":" + x + ",\"y\":" + y + "}"), Instant.now());
        nanos += 10; // 같은 사람의 다음 짚기는 1초 안이다
    }

    private static ShareFrames.Frame result(String jsonText, byte[] crop, byte[] screen) {
        byte[] body = jsonText.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
        out.writeBytes(body);
        if (crop != null || screen != null) {
            byte[] c = crop == null ? new byte[0] : crop;
            out.writeBytes(ByteBuffer.allocate(4).putInt(c.length).array());
            out.writeBytes(c);
            if (screen != null) {
                out.writeBytes(screen);
            }
        }
        return new ShareFrames.Frame(ShareFrames.PIN_RESULT, 0, out.toByteArray());
    }

    private static ShareFrames.Frame frame(int kind, String payload) {
        return ShareFrames.decode(ShareFrames.encode(kind, payload));
    }

    private JsonNode msg(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class FakeDevice implements DeviceChannel {
        final List<ShareFrames.Frame> sent = new ArrayList<>();

        @Override
        public void sendBinary(ByteBuffer frame) {
            sent.add(ShareFrames.decode(frame));
        }

        @Override
        public void close(String reason) {
        }

        int count(int kind) {
            return (int) sent.stream().filter(f -> f.kind() == kind).count();
        }

        JsonNode lastJson(int kind) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).kind() == kind) {
                    return msg(sent.get(i).payloadText());
                }
            }
            throw new AssertionError("기기로 0x" + Integer.toHexString(kind) + " 가 가지 않았다");
        }
    }

    private final class FakeViewer implements ViewerChannel {
        final List<JsonNode> texts = new ArrayList<>();

        @Override
        public void sendText(String text) {
            texts.add(msg(text));
        }

        @Override
        public void sendFrame(byte[] jpeg) {
        }

        @Override
        public boolean sendVideo(byte[] chunk, boolean keyFrame) {
            return false;
        }

        @Override
        public void close(String reason) {
        }

        List<String> types() {
            return texts.stream().map(t -> t.path("type").asText()).toList();
        }

        int count(String type) {
            return (int) types().stream().filter(type::equals).count();
        }

        JsonNode last(String type) {
            for (int i = texts.size() - 1; i >= 0; i--) {
                if (type.equals(texts.get(i).path("type").asText())) {
                    return texts.get(i);
                }
            }
            throw new AssertionError(type + " 를 받지 못했다: " + types());
        }
    }
}
