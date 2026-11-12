package com.example.dvely.deviceshare.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 공유 세션의 화면 나눠 주기·조작권 흐름(설계 §4·§5). 채널은 가짜로 바꿔 끼운다. */
class ShareSessionTest {

    private final ObjectMapper json = new ObjectMapper();
    private ShareSession session;
    private FakeDevice device;

    @BeforeEach
    void setUp() {
        session = new ShareSession(7L, LocalDateTime.now().plusHours(1), json, 3, 1024, 5);
        device = new FakeDevice();
        session.attachDevice(device);
        session.onDeviceFrame(device, frame(ShareFrames.HELLO, "{\"cliVersion\":\"0.2.0\",\"kind\":\"WEB\"}"));
    }

    @Test
    void HELLO_에_WELCOME_으로_답하고_화면은_모든_보는_사람에게_간다() {
        assertThat(device.kinds()).contains(ShareFrames.WELCOME);

        FakeViewer a = new FakeViewer();
        FakeViewer b = new FakeViewer();
        session.join(a, "민지", false);
        session.join(b, "", false);

        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG,
                "{\"format\":\"jpeg\",\"width\":390,\"height\":844,\"url\":\"http://localhost:5173/\",\"title\":\"App\"}"));
        session.onDeviceFrame(device, new ShareFrames.Frame(ShareFrames.SCREEN_FRAME, 0, new byte[]{1, 2, 3}));

        assertThat(a.type("welcome").path("name").asText()).isEqualTo("민지");
        assertThat(b.type("welcome").path("name").asText()).isEqualTo("보는 사람 2");
        assertThat(a.type("config").path("width").asInt()).isEqualTo(390);
        assertThat(a.frames).hasSize(1);
        assertThat(b.frames).hasSize(1);
        assertThat(a.last("presence").path("viewers").asInt()).isEqualTo(2);
    }

    @Test
    void 늦게_들어온_사람은_설정과_마지막_화면을_바로_받는다() {
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, "{\"width\":10,\"height\":20}"));
        session.onDeviceFrame(device, new ShareFrames.Frame(ShareFrames.SCREEN_FRAME, 0, new byte[]{9}));
        FakeViewer late = new FakeViewer();
        session.join(late, "늦은", false);
        assertThat(late.type("config").path("height").asInt()).isEqualTo(20);
        assertThat(late.type("device").path("state").asText()).isEqualTo("online");
        assertThat(late.frames).hasSize(1);
    }

    @Test
    void 너무_큰_화면은_버린다() {
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        session.onDeviceFrame(device, new ShareFrames.Frame(ShareFrames.SCREEN_FRAME, 0, new byte[2048]));
        assertThat(a.frames).isEmpty();
    }

    @Test
    void 조작권이_없으면_입력이_기기로_가지_않고_승인되면_간다() {
        FakeViewer guest = new FakeViewer();
        String id = session.join(guest, "손님", false);
        int before = device.count(ShareFrames.INPUT);

        session.onViewerMessage(id, input("{\"type\":\"pointer\",\"action\":\"down\",\"x\":0.5,\"y\":0.5}"), Instant.now());
        assertThat(device.count(ShareFrames.INPUT)).isEqualTo(before);

        session.onViewerMessage(id, msg("{\"type\":\"request-control\"}"), Instant.now());
        assertThat(guest.last("control").path("state").asText()).isEqualTo("pending");
        JsonNode request = device.lastJson(ShareFrames.CONTROL_REQUEST);
        assertThat(request.path("viewerId").asText()).isEqualTo(id);
        assertThat(request.path("name").asText()).isEqualTo("손님");

        session.onDeviceFrame(device, frame(ShareFrames.CONTROL_DECISION,
                "{\"viewerId\":\"" + id + "\",\"granted\":true,\"ttlSeconds\":600}"));
        assertThat(guest.last("control").path("state").asText()).isEqualTo("granted");
        assertThat(guest.last("presence").path("controller").path("name").asText()).isEqualTo("손님");

        session.onViewerMessage(id, input("{\"type\":\"text\",\"text\":\"안녕\"}"), Instant.now());
        assertThat(device.lastJson(ShareFrames.INPUT).path("text").asText()).isEqualTo("안녕");

        session.onViewerMessage(id, input("{\"type\":\"exec\",\"cmd\":\"rm\"}"), Instant.now());
        assertThat(device.lastJson(ShareFrames.INPUT).path("type").asText()).isEqualTo("text");
    }

    @Test
    void 거절되면_denied_이고_승인_요청을_하지_않은_id_의_결정은_무시한다() {
        FakeViewer guest = new FakeViewer();
        String id = session.join(guest, "손님", false);
        session.onDeviceFrame(device, frame(ShareFrames.CONTROL_DECISION, "{\"viewerId\":\"" + id + "\",\"granted\":true}"));
        assertThat(guest.types()).doesNotContain("control");

        session.onViewerMessage(id, msg("{\"type\":\"request-control\"}"), Instant.now());
        session.onDeviceFrame(device, frame(ShareFrames.CONTROL_DECISION, "{\"viewerId\":\"" + id + "\",\"granted\":false}"));
        assertThat(guest.last("control").path("state").asText()).isEqualTo("denied");
    }

    @Test
    void 주인은_들어오자마자_조작권을_받고_앞사람의_조작권은_거둔다() {
        FakeViewer guest = new FakeViewer();
        String guestId = session.join(guest, "손님", false);
        session.onViewerMessage(guestId, msg("{\"type\":\"request-control\"}"), Instant.now());
        session.onDeviceFrame(device, frame(ShareFrames.CONTROL_DECISION, "{\"viewerId\":\"" + guestId + "\",\"granted\":true}"));

        FakeViewer owner = new FakeViewer();
        session.join(owner, "아무거나", true);
        assertThat(owner.type("welcome").path("name").asText()).isEqualTo("주인");
        assertThat(owner.last("control").path("state").asText()).isEqualTo("granted");
        assertThat(owner.last("control").has("ttlSeconds")).isFalse();
        assertThat(guest.last("control").path("state").asText()).isEqualTo("revoked");
    }

    @Test
    void 이름에_주인을_쓰면_주인으로_보이지_않는다() {
        FakeViewer fake = new FakeViewer();
        session.join(fake, "주인", false);
        assertThat(fake.type("welcome").path("name").asText()).isEqualTo("보는 사람 1");
    }

    @Test
    void 조작권_시간이_지나면_거둔다() {
        FakeViewer guest = new FakeViewer();
        String id = session.join(guest, "손님", false);
        session.onViewerMessage(id, msg("{\"type\":\"request-control\"}"), Instant.now());
        session.onDeviceFrame(device, frame(ShareFrames.CONTROL_DECISION, "{\"viewerId\":\"" + id + "\",\"granted\":true,\"ttlSeconds\":1}"));
        session.expireControlIfDue(Instant.now().plusSeconds(5));
        assertThat(guest.last("control").path("state").asText()).isEqualTo("revoked");
        assertThat(guest.last("presence").path("controller").isNull()).isTrue();
    }

    @Test
    void 입력은_초당_상한을_넘으면_버린다() {
        FakeViewer owner = new FakeViewer();
        String id = session.join(owner, null, true);
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        int before = device.count(ShareFrames.INPUT);
        for (int i = 0; i < 20; i++) {
            session.onViewerMessage(id, input("{\"type\":\"wheel\",\"x\":0.1,\"y\":0.1,\"dx\":0,\"dy\":10}"), now);
        }
        assertThat(device.count(ShareFrames.INPUT) - before).isEqualTo(5);
    }

    @Test
    void 자리가_다_차면_더_들어오지_못한다() {
        assertThat(session.join(new FakeViewer(), "1", false)).isNotNull();
        assertThat(session.join(new FakeViewer(), "2", false)).isNotNull();
        assertThat(session.join(new FakeViewer(), "3", false)).isNotNull();
        assertThat(session.join(new FakeViewer(), "4", false)).isNull();
    }

    @Test
    void 기기가_끊기면_offline_새_연결은_옛_연결을_replaced_로_닫는다() {
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        FakeDevice second = new FakeDevice();
        session.attachDevice(second);
        assertThat(device.lastJson(ShareFrames.GOODBYE).path("reason").asText()).isEqualTo("replaced");
        assertThat(device.closed).isEqualTo("replaced");

        session.detachDevice(device); // 옛 연결이 늦게 닫혀도 새 연결에는 영향이 없다
        assertThat(a.types()).doesNotContain("device-offline-marker");
        session.detachDevice(second);
        assertThat(a.last("device").path("state").asText()).isEqualTo("offline");
    }

    @Test
    void 끝내면_보는_사람에게_ended_기기에_GOODBYE() {
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        session.end("revoked");
        assertThat(a.last("ended").path("reason").asText()).isEqualTo("revoked");
        assertThat(a.closed).isEqualTo("revoked");
        assertThat(device.lastJson(ShareFrames.GOODBYE).path("reason").asText()).isEqualTo("revoked");
        assertThat(session.join(new FakeViewer(), "b", false)).isNull();
    }

    // ------------------------------------------------------------------ 가짜 채널

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

    private JsonNode input(String input) {
        return msg("{\"type\":\"input\",\"input\":" + input + "}");
    }

    private final class FakeDevice implements DeviceChannel {
        final List<ShareFrames.Frame> sent = new ArrayList<>();
        String closed;

        @Override
        public void sendBinary(ByteBuffer frame) {
            sent.add(ShareFrames.decode(frame));
        }

        @Override
        public void close(String reason) {
            closed = reason;
        }

        List<Integer> kinds() {
            return sent.stream().map(ShareFrames.Frame::kind).toList();
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
            throw new AssertionError("기기로 0x" + Integer.toHexString(kind) + " 가 가지 않았다: " + kinds());
        }
    }

    private final class FakeViewer implements ViewerChannel {
        final List<JsonNode> texts = new ArrayList<>();
        final List<byte[]> frames = new ArrayList<>();
        String closed;

        @Override
        public void sendText(String text) {
            texts.add(msg(text));
        }

        @Override
        public void sendFrame(byte[] jpeg) {
            frames.add(jpeg);
        }

        @Override
        public void close(String reason) {
            closed = reason;
        }

        List<String> types() {
            return texts.stream().map(t -> t.path("type").asText()).collect(Collectors.toList());
        }

        JsonNode type(String type) {
            return texts.stream().filter(t -> type.equals(t.path("type").asText())).findFirst()
                    .orElseThrow(() -> new AssertionError(type + " 를 받지 못했다: " + types()));
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
