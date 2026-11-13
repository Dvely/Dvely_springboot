package com.example.dvely.deviceshare.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
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

/** 공유 세션의 화면 나눠 주기·조작권 흐름(설계 §4·§5·§12). 채널은 가짜로 바꿔 끼운다. */
class ShareSessionTest {

    private static final String H264_CONFIG = "{\"format\":\"h264\",\"codec\":\"avc1.42C01F\",\"width\":720,"
            + "\"height\":1280,\"url\":\"com.example.app\",\"title\":\"Pixel_8\"}";
    private static final int MIB = 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private ShareSession session;
    private FakeDevice device;
    /** Android 세션의 키 프레임 요청 시계(나노초). */
    private long nanos = 1_000L * 1_000_000_000L;

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
        assertThat(a.type("config").path("format").asText()).isEqualTo("jpeg");
        assertThat(a.type("config").path("kind").asText()).isEqualTo("WEB");
        assertThat(a.type("config").has("codec")).isFalse();
        assertThat(a.frames).hasSize(1);
        assertThat(a.video).isEmpty();
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
        assertThat(device.lastJson(ShareFrames.PRESENCE).path("controller").path("name").asText()).isEqualTo("손님");

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

    // ------------------------------------------------------------------ 2단계: H.264 (§12)

    @Test
    void 모르는_형식은_jpeg_로_보고_코덱을_싣지_않는다() {
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG,
                "{\"format\":\"png\",\"codec\":\"avc1.42C01F\",\"width\":10,\"height\":20}"));
        JsonNode config = a.last("config");
        assertThat(config.path("format").asText()).isEqualTo("jpeg");
        assertThat(config.has("codec")).isFalse();
        assertThat(config.path("kind").asText()).isEqualTo("WEB");
        assertThat(config.path("url").asText()).isEmpty();
    }

    @Test
    void h264_설정에는_형식_코덱_링크_종류가_실린다() {
        startAndroid();
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        JsonNode config = a.type("config");
        assertThat(config.path("format").asText()).isEqualTo("h264");
        assertThat(config.path("codec").asText()).isEqualTo("avc1.42C01F");
        assertThat(config.path("kind").asText()).isEqualTo("ANDROID");
        assertThat(config.path("width").asInt()).isEqualTo(720);
        assertThat(config.path("height").asInt()).isEqualTo(1280);
        assertThat(config.path("url").asText()).isEqualTo("com.example.app");
        assertThat(config.path("title").asText()).isEqualTo("Pixel_8");
    }

    @Test
    void 코덱_문자열이_틀린_h264_설정은_통째로_버린다() {
        startAndroid();
        session.onDeviceFrame(device, video(1, true, 10));
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        int configs = a.count("config");

        for (String codec : List.of("", "avc1.42C01", "avc1.42C01FF", "avc1.42C0G1", "AVC1.42C01F",
                "hvc1.1.6.L93.B0", "avc1.42C01F\\n", "avc1.42C01F\\\"><script>")) {
            session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG,
                    "{\"format\":\"h264\",\"codec\":\"" + codec + "\",\"width\":1,\"height\":2}"));
        }
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, "{\"format\":\"h264\",\"width\":1,\"height\":2}"));
        assertThat(a.count("config")).isEqualTo(configs);

        FakeViewer late = new FakeViewer();
        session.join(late, "late", false);
        assertThat(late.type("config").path("width").asInt()).isEqualTo(720);
        assertThat(late.videoIds()).containsExactly(1); // 버린 설정은 GOP 도 건드리지 않는다
    }

    @Test
    void h264_장은_flags_바이트째_그대로_순서대로_가고_JPEG_칸은_쓰지_않는다() {
        startAndroid();
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        ShareFrames.Frame key = video(1, true, 64);
        ShareFrames.Frame delta = video(2, false, 16);
        session.onDeviceFrame(device, key);
        session.onDeviceFrame(device, delta);
        session.onDeviceFrame(device, new ShareFrames.Frame(ShareFrames.SCREEN_FRAME, 0, new byte[]{1})); // flags 만

        assertThat(a.video).containsExactly(key.payload(), delta.payload());
        assertThat(a.keyFlags).containsExactly(true, false);
        assertThat(a.frames).isEmpty();
    }

    @Test
    void 늦게_들어온_사람은_마지막_키_프레임부터의_장을_순서대로_받는다() {
        startAndroid();
        session.onDeviceFrame(device, video(1, false, 10)); // 키 프레임 앞의 장은 담지 않는다
        session.onDeviceFrame(device, video(2, true, 10));
        session.onDeviceFrame(device, video(3, false, 10));
        session.onDeviceFrame(device, video(4, false, 10));
        FakeViewer first = new FakeViewer();
        session.join(first, "first", false);
        assertThat(first.videoIds()).containsExactly(2, 3, 4);
        assertThat(first.keyFlags).containsExactly(true, false, false);

        session.onDeviceFrame(device, video(5, true, 10));
        session.onDeviceFrame(device, video(6, false, 10));
        FakeViewer second = new FakeViewer();
        session.join(second, "second", false);
        assertThat(second.videoIds()).containsExactly(5, 6);
        assertThat(second.frames).isEmpty();
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isZero();
    }

    @Test
    void GOP_가_비었으면_들어올_때_키_프레임을_요청한다() {
        startAndroid();
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        assertThat(a.video).isEmpty();
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(1);
        assertThat(device.lastJson(ShareFrames.KEYFRAME_REQUEST).isObject()).isTrue();
        assertThat(device.lastJson(ShareFrames.KEYFRAME_REQUEST).size()).isZero();
    }

    @Test
    void 기기가_준비되지_않았거나_jpeg_면_키_프레임을_요청하지_않는다() {
        session.join(new FakeViewer(), "web", false); // setUp 의 웹 세션
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isZero();

        startAndroid();
        session.detachDevice(device);
        session.join(new FakeViewer(), "offline", false);
        FakeDevice next = new FakeDevice();
        session.attachDevice(next); // HELLO 전
        session.join(new FakeViewer(), "connecting", false);
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isZero();
        assertThat(next.count(ShareFrames.KEYFRAME_REQUEST)).isZero();
    }

    @Test
    void 키_프레임_요청은_공유마다_1초에_한_번까지() {
        startAndroid();
        session.join(new FakeViewer(), "1", false);
        session.join(new FakeViewer(), "2", false);
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(1);

        nanos += 999_000_000L;
        session.join(new FakeViewer(), "3", false);
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(1);

        nanos += 1_000_000L;
        session.join(new FakeViewer(), "4", false);
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(2);
    }

    @Test
    void 키_프레임을_기다리는_사람이_있으면_장이_올_때마다_요청한다_1초에_한_번까지() {
        startAndroid();
        session.onDeviceFrame(device, video(1, true, 10));
        FakeViewer a = new FakeViewer();
        session.join(a, "a", false);
        session.onDeviceFrame(device, video(2, false, 10));
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isZero();

        a.waiting = true; // 큐가 넘쳐 비웠다
        session.onDeviceFrame(device, video(3, false, 10));
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(1);
        session.onDeviceFrame(device, video(4, false, 10));
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(1);

        nanos += 1_000_000_000L; // 걸러진 요청은 1초 뒤 다음 장에서 다시 간다
        session.onDeviceFrame(device, video(5, false, 10));
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(2);

        a.waiting = false;
        nanos += 5_000_000_000L;
        session.onDeviceFrame(device, video(6, true, 10));
        session.onDeviceFrame(device, video(7, false, 10));
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(2);
    }

    @Test
    void GOP_가_2MiB_를_넘으면_비우고_다음_키_프레임부터_다시_담는다() {
        startAndroid();
        session.onDeviceFrame(device, video(1, true, MIB));
        session.onDeviceFrame(device, video(2, false, MIB)); // 꼭 2MiB 는 둔다
        FakeViewer full = new FakeViewer();
        session.join(full, "full", false);
        assertThat(full.videoIds()).containsExactly(1, 2);

        session.onDeviceFrame(device, video(3, false, 1));
        FakeViewer overflowed = new FakeViewer();
        session.join(overflowed, "overflowed", false);
        assertThat(overflowed.video).isEmpty();
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(1);
        assertThat(full.videoIds()).containsExactly(1, 2, 3); // 이미 보는 사람에게는 그대로 간다

        session.onDeviceFrame(device, video(5, false, 10)); // 키 프레임 없이는 담지 않는다
        session.onDeviceFrame(device, video(6, true, 10));
        session.onDeviceFrame(device, video(7, false, 10));
        FakeViewer late = new FakeViewer();
        session.join(late, "late", false);
        assertThat(late.videoIds()).containsExactly(6, 7);
    }

    @Test
    void 설정이_바뀌거나_기기가_끊기거나_대체되면_GOP_를_비운다() {
        startAndroid();
        session.onDeviceFrame(device, video(1, true, 10));
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, H264_CONFIG.replace("Pixel_8", "다른 이름")));
        assertThat(joinAndTakeVideo()).containsExactly(1); // url·title 만 바뀌면 그대로

        for (String changed : List.of(H264_CONFIG.replace("1280", "1281"), H264_CONFIG.replace("720", "721"),
                H264_CONFIG.replace("42C01F", "640028"))) {
            session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, H264_CONFIG));
            session.onDeviceFrame(device, video(2, true, 10));
            assertThat(joinAndTakeVideo()).containsExactly(2);
            session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, changed));
            assertThat(joinAndTakeVideo()).as(changed).isEmpty();
        }

        nanos += 1_000_000_000L; // 위에서 보낸 요청의 1초 제한을 넘긴다
        session.onDeviceFrame(device, video(3, true, 10));
        session.detachDevice(device);
        device = new FakeDevice();
        session.attachDevice(device);
        session.onDeviceFrame(device, frame(ShareFrames.HELLO, "{\"kind\":\"ANDROID\"}"));
        assertThat(joinAndTakeVideo()).isEmpty();
        assertThat(device.count(ShareFrames.KEYFRAME_REQUEST)).isEqualTo(1);

        session.onDeviceFrame(device, video(4, true, 10));
        device = new FakeDevice();
        session.attachDevice(device); // 끊김 없이 새 연결이 대체
        session.onDeviceFrame(device, frame(ShareFrames.HELLO, "{\"kind\":\"ANDROID\"}"));
        assertThat(joinAndTakeVideo()).isEmpty();
    }

    @Test
    void 형식이_바뀌면_앞_형식의_마지막_장은_주지_않는다() {
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, "{\"format\":\"jpeg\",\"width\":10,\"height\":20}"));
        session.onDeviceFrame(device, new ShareFrames.Frame(ShareFrames.SCREEN_FRAME, 0, new byte[]{9}));
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, H264_CONFIG));
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, "{\"format\":\"jpeg\",\"width\":10,\"height\":20}"));
        FakeViewer late = new FakeViewer();
        session.join(late, "late", false);
        assertThat(late.frames).isEmpty();
        assertThat(late.video).isEmpty();
    }

    /** 2단계 세션으로 바꾼다. HELLO 와 h264 설정까지 받은 상태. 보는 사람 10명까지, 화면 한 장 2MiB. */
    private void startAndroid() {
        session = new ShareSession(8L, DeviceShareKind.ANDROID, LocalDateTime.now().plusHours(1), json, 10,
                2 * MIB, 5, () -> nanos);
        device = new FakeDevice();
        session.attachDevice(device);
        session.onDeviceFrame(device, frame(ShareFrames.HELLO, "{\"cliVersion\":\"0.3.0\",\"kind\":\"ANDROID\"}"));
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, H264_CONFIG));
    }

    /** 들어와서 받은 장의 id 를 보고 바로 나간다(자리를 남기지 않는다). */
    private List<Integer> joinAndTakeVideo() {
        FakeViewer viewer = new FakeViewer();
        session.leave(session.join(viewer, "잠깐", false));
        return viewer.videoIds();
    }

    /** {@code [flags][id][0…]}. id 로 어느 장인지 알아본다. */
    private static ShareFrames.Frame video(int id, boolean keyFrame, int size) {
        byte[] payload = new byte[Math.max(size, 2)];
        payload[0] = (byte) (keyFrame ? 1 : 0);
        payload[1] = (byte) id;
        return new ShareFrames.Frame(ShareFrames.SCREEN_FRAME, 0, payload);
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
        final List<byte[]> video = new ArrayList<>();
        final List<Boolean> keyFlags = new ArrayList<>();
        /** {@link #sendVideo} 가 돌려줄 값. 큐가 넘쳐 키 프레임을 기다리는 사람을 흉내 낸다. */
        boolean waiting;
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
        public boolean sendVideo(byte[] chunk, boolean keyFrame) {
            video.add(chunk);
            keyFlags.add(keyFrame);
            return waiting;
        }

        @Override
        public void close(String reason) {
            closed = reason;
        }

        List<Integer> videoIds() {
            return video.stream().map(chunk -> (int) chunk[1]).toList();
        }

        int count(String type) {
            return (int) types().stream().filter(type::equals).count();
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
