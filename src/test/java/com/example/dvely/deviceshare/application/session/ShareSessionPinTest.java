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

    // ------------------------------------------------------------------ 짚기 범위 (§15, #694)

    @Test
    void 기기가_알린_짚기_기능만_config_pinCaps_에_싣고_WELCOME_에_결과_상한을_보낸다() {
        assertThat(guest.last("config").path("pinCaps").toString()).isEqualTo("[\"pin\"]");
        assertThat(device.lastJson(ShareFrames.WELCOME).path("pinResultMax").asInt()).isEqualTo(48 * 1024);

        announce("\"pin\",\"pin-scope\",\"pin-screen\",\"pin-telepathy\"");

        assertThat(guest.last("config").path("pinCaps").toString()).isEqualTo("[\"pin\",\"pin-scope\",\"pin-screen\"]");
    }

    @Test
    void 화면_전체_짚기는_기기가_pin_screen_을_알릴_때만_기기로_간다() {
        session.onViewerMessage(guestId, msg("{\"type\":\"pin-probe\",\"screen\":true}"), Instant.now());
        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("unsupported");
        assertThat(device.count(ShareFrames.PIN_PROBE)).as("옛 CLI 는 모르는 짚기를 말없이 버려 timeout 까지 자리를 잡는다").isZero();

        announce("\"pin\",\"pin-screen\"");
        nanos += 2 * SECOND;
        session.onViewerMessage(guestId, msg("{\"type\":\"pin-probe\",\"screen\":true}"), Instant.now());

        JsonNode sent = device.lastJson(ShareFrames.PIN_PROBE);
        assertThat(sent.path("screen").asBoolean()).isTrue();
        assertThat(sent.has("x")).isFalse();
        session.onDeviceFrame(device, result("""
                {"probeId":"p1","ok":true,"screen":{"label":"/settings"},
                 "scopes":[{"kind":"screen","type":"screen","bounds":{"x":0,"y":0,"w":1,"h":1}}],"defaultScope":0}
                """, new byte[0], JPEG));
        JsonNode pin = guest.last("pin");
        assertThat(pin.path("state").asText()).isEqualTo("resolved");
        assertThat(pin.path("label").asText()).isEqualTo("화면 전체");
        assertThat(pin.path("scopes").get(0).path("label").asText()).isEqualTo("화면 전체");
        assertThat(session.claimPin(guestId, "p1").orElseThrow().resolution().resolved()).isTrue();
    }

    @Test
    void 끌기_짚기는_pin_region_을_알리지_않은_기기로_보내지_않는다() {
        announce("\"pin\",\"pin-scope\",\"pin-screen\"");
        session.onViewerMessage(guestId, msg("{\"type\":\"pin-probe\",\"rect\":{\"x\":0.1,\"y\":0.1,\"w\":0.3,\"h\":0.2}}"),
                Instant.now());

        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("unsupported");
        assertThat(device.count(ShareFrames.PIN_PROBE)).isZero();
    }

    @Test
    void 범위_사다리는_칸_이름과_테두리만_가고_손님에게는_바깥_칸의_컴포넌트_이름을_넣지_않는다() {
        announce("\"pin\",\"pin-scope\",\"pin-screen\"");
        FakeViewer owner = new FakeViewer();
        String ownerId = session.join(owner, "주인", true);
        String ladder = """
                "ok":true,"element":{"type":"button","text":"저장","bounds":{"x":0.1,"y":0.5,"w":0.2,"h":0.05}},
                "scopes":[{"kind":"element","type":"button","text":"저장","component":"SaveButton",
                            "bounds":{"x":0.1,"y":0.5,"w":0.2,"h":0.05}},
                          {"kind":"component","type":"form","id":"profile","component":"ProfileForm",
                            "selector":"form#profile","bounds":{"x":0.05,"y":0.3,"w":0.9,"h":0.4}},
                          {"kind":"group","type":"section","text":"요금제 안내","bounds":{"x":0,"y":0.25,"w":1,"h":0.6}},
                          {"kind":"telepathy","type":"x","bounds":{"x":0,"y":0,"w":1,"h":1}},
                          {"kind":"group","type":"div","bounds":{"x":0.1,"y":0.1,"w":0,"h":0.2}},
                          {"kind":"screen","type":"screen"}],
                "defaultScope":0""";

        probe(guestId, 0.2, 0.52);
        session.onDeviceFrame(device, result("{\"probeId\":\"p1\"," + ladder + "}", JPEG, JPEG));
        probe(ownerId, 0.2, 0.52);
        session.onDeviceFrame(device, result("{\"probeId\":\"p2\"," + ladder + "}", JPEG, JPEG));

        JsonNode toGuest = guest.last("pin");
        assertThat(toGuest.path("scopes").findValuesAsText("label"))
                .as("모르는 종류·넓이 0 인 칸은 버리고, 손님에게는 가장 안쪽 컴포넌트 이름까지만(§15.2)")
                .containsExactly("<button> \"저장\" · SaveButton", "폼 #profile", "구역 \"요금제 안내\"", "화면 전체");
        assertThat(toGuest.path("scopes").get(3).path("bounds").path("w").asDouble()).isEqualTo(1.0);
        assertThat(toGuest.path("defaultScope").asInt()).isZero();
        assertThat(toGuest.path("label").asText()).as("옛 탭이 읽는 요소 이름은 그대로").isEqualTo("<button> \"저장\"");
        assertThat(toGuest.toString()).doesNotContain("ProfileForm").doesNotContain("selector");
        assertThat(owner.last("pin").path("scopes").get(1).path("label").asText()).isEqualTo("컴포넌트 ProfileForm");
    }

    @Test
    void 결과는_48KiB_까지_받는다() {
        probe(guestId, 0.5, 0.5);
        String padding = "x".repeat(40 * 1024); // 모르는 필드는 정리하며 버린다
        session.onDeviceFrame(device, result("{\"probeId\":\"p1\",\"ok\":true,\"pad\":\"" + padding
                + "\",\"element\":{\"type\":\"button\"}}", JPEG, JPEG));
        assertThat(guest.last("pin").path("state").asText()).isEqualTo("resolved");

        nanos += 2 * SECOND;
        probe(guestId, 0.5, 0.5);
        session.onDeviceFrame(device, result("{\"probeId\":\"p2\",\"ok\":true,\"pad\":\"" + "x".repeat(49 * 1024)
                + "\",\"element\":{\"type\":\"button\"}}", JPEG, JPEG));
        assertThat(guest.last("pin").path("probeId").asText()).as("넘으면 버린다").isEqualTo("p2");
        assertThat(guest.last("pin").path("state").asText()).isEqualTo("pending");
    }

    @Test
    void 끌기_짚기는_pin_region_을_알린_기기로_사각형째_가고_영역_안_요소_수와_둘레가_뷰어로_간다() {
        announce("\"pin\",\"pin-scope\",\"pin-screen\",\"pin-region\"");
        session.onViewerMessage(guestId, msg("{\"type\":\"pin-probe\",\"rect\":{\"x\":0.1,\"y\":0.2,\"w\":0.5,\"h\":0.3}}"),
                Instant.now());

        JsonNode sent = device.lastJson(ShareFrames.PIN_PROBE);
        assertThat(sent.path("rect").path("w").asDouble()).isEqualTo(0.5);
        assertThat(sent.has("x")).isFalse();
        session.onDeviceFrame(device, result("""
                {"probeId":"p1","ok":true,"point":{"x":0.35,"y":0.35},
                 "element":{"type":"p","text":"지금은 무료","bounds":{"x":0.12,"y":0.3,"w":0.4,"h":0.05}},
                 "scopes":[{"kind":"region","type":"region","members":2,"bounds":{"x":0.1,"y":0.2,"w":0.5,"h":0.3}},
                           {"kind":"component","type":"section","component":"PlanCard","bounds":{"x":0.05,"y":0.15,"w":0.9,"h":0.45}},
                           {"kind":"screen","type":"screen"}],
                 "defaultScope":0,"empty":false,
                 "members":[{"type":"h2","text":"요금제","bounds":{"x":0.12,"y":0.22,"w":0.2,"h":0.05}},
                            {"type":"p","text":"지금은 무료","bounds":{"x":0.12,"y":0.3,"w":0.4,"h":0.05}}]}
                """, JPEG, JPEG));

        JsonNode pin = guest.last("pin");
        assertThat(pin.path("scopes").findValuesAsText("label")).containsExactly("영역 · 요소 2개", "구역", "화면 전체");
        assertThat(pin.path("members").asInt()).isEqualTo(2);
        assertThat(pin.path("empty").asBoolean()).isFalse();
        assertThat(pin.toString()).doesNotContain("PlanCard").doesNotContain("요금제");
    }

    @Test
    void 빈_자리의_둘레는_방향과_이름으로_가고_빈_곳을_탭한_실패에도_간다() {
        announce("\"pin\",\"pin-scope\",\"pin-region\"");
        session.onViewerMessage(guestId, msg("{\"type\":\"pin-probe\",\"rect\":{\"x\":0.1,\"y\":0.6,\"w\":0.8,\"h\":0.1}}"),
                Instant.now());
        session.onDeviceFrame(device, result("""
                {"probeId":"p1","ok":true,"point":{"x":0.5,"y":0.65},
                 "scopes":[{"kind":"region","type":"region","members":0,"bounds":{"x":0.1,"y":0.6,"w":0.8,"h":0.1}},
                           {"kind":"screen","type":"screen"}],
                 "defaultScope":0,"empty":true,
                 "near":[{"dir":"up","type":"h2","text":"요금제","bounds":{"x":0.1,"y":0.5,"w":0.3,"h":0.05},"distance":0.05},
                         {"dir":"down","type":"footer","bounds":{"x":0,"y":0.8,"w":1,"h":0.1},"distance":0.1},
                         {"dir":"sideways","type":"x","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}}]}
                """, JPEG, JPEG));
        JsonNode pin = guest.last("pin");
        assertThat(pin.path("state").asText()).isEqualTo("resolved");
        assertThat(pin.path("label").asText()).isEqualTo("빈 자리");
        assertThat(pin.path("empty").asBoolean()).isTrue();
        assertThat(pin.path("near").findValuesAsText("label")).containsExactly("위 <h2> \"요금제\"", "아래 <footer>");

        nanos += 2 * SECOND;
        probe(guestId, 0.5, 0.95);
        session.onDeviceFrame(device, result("""
                {"probeId":"p2","ok":false,"reason":"NO_ELEMENT","point":{"x":0.5,"y":0.95},
                 "near":[{"dir":"up","type":"button","text":"저장","bounds":{"x":0.1,"y":0.8,"w":0.2,"h":0.05}}]}
                """, JPEG, JPEG));
        assertThat(guest.last("pin").path("near").get(0).path("label").asText()).isEqualTo("위 <button> \"저장\"");
    }

    @Test
    void 화면_밖이거나_너무_작은_사각형은_받지_않는다() {
        announce("\"pin\",\"pin-region\"");
        for (String rect : List.of("{\"x\":0.8,\"y\":0.1,\"w\":0.5,\"h\":0.2}", "{\"x\":0.1,\"y\":0.1,\"w\":0.001,\"h\":0.2}",
                "{\"x\":-0.1,\"y\":0.1,\"w\":0.2,\"h\":0.2}", "{\"x\":\"0.1\",\"y\":0.1,\"w\":0.2,\"h\":0.2}")) {
            session.onViewerMessage(guestId, msg("{\"type\":\"pin-probe\",\"rect\":" + rect + "}"), Instant.now());
            assertThat(guest.last("error").path("message").asText()).as(rect).contains("화면 밖");
        }
        assertThat(device.count(ShareFrames.PIN_PROBE)).isZero();
    }

    /** 기기가 다시 HELLO 를 보내 기능을 알리고, 화면 설정을 다시 보내 config 가 새로 나가게 한다 */
    private void announce(String features) {
        session.onDeviceFrame(device, frame(ShareFrames.HELLO, "{\"cliVersion\":\"0.3.0\",\"kind\":\"WEB\",\"features\":[" + features + "]}"));
        session.onDeviceFrame(device, frame(ShareFrames.SCREEN_CONFIG, "{\"format\":\"jpeg\",\"width\":390,\"height\":844,\"url\":\"/x\"}"));
    }

    @Test
    void 짚기마다의_ref_를_그_짚기의_모든_답에_돌려준다() {
        // §15.5: 여러 곳을 짚는 뷰어는 답의 개수가 아니라 ref 로 어느 곳의 답인지 안다
        ref(guestId, "a1", 0.4, 0.4);
        assertThat(guest.last("pin").path("state").asText()).isEqualTo("pending");
        assertThat(guest.last("pin").path("ref").asText()).isEqualTo("a1");
        session.onDeviceFrame(device, result(ELEMENT.formatted("p1"), JPEG, JPEG));
        assertThat(guest.last("pin").path("state").asText()).isEqualTo("resolved");
        assertThat(guest.last("pin").path("ref").asText()).isEqualTo("a1");

        ref(guestId, "b2", 0.5, 0.5); // 1초 안
        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("busy");
        assertThat(guest.last("pin").path("ref").asText()).isEqualTo("b2");

        nanos += SECOND;
        ref(guestId, "b2", 0.5, 0.5);
        nanos += ShareSession.PROBE_TIMEOUT_NANOS + 1;
        session.expirePins();
        assertThat(guest.last("pin").path("reason").asText()).isEqualTo("timeout");
        assertThat(guest.last("pin").path("ref").asText()).isEqualTo("b2");

        nanos += SECOND;
        ref(guestId, "x-1!", 0.5, 0.5);
        assertThat(guest.last("pin").has("ref")).as("모양이 틀린 ref 는 돌려주지 않는다").isFalse();
    }

    @Test
    void 결과는_보는_사람마다_다섯_개까지_두고_다른_사람이_짚어도_내_결과는_남는다() {
        String mine = pinned(guestId, "m1", JPEG);
        List<String> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String other = session.join(new FakeViewer(), "v" + i, false);
            for (int k = 0; k < 5; k++) {
                others.add(pinned(other, "o" + k, JPEG));
            }
        }
        // 공유 하나에 16개. 지금까지는 공유 전체에 다섯 개라, 남이 짚으면 내 결과가 밀려났다
        assertThat(session.claimPins(guestId, List.of(mine)).claimed()).isPresent();

        List<String> five = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            five.add(pinned(guestId, "g" + k, JPEG));
        }
        assertThat(session.claimPins(guestId, List.of(five.getFirst())).missing())
                .as("여섯 번째가 가장 오래된 것을 밀어낸다").containsExactly(five.getFirst());
        assertThat(session.claimPins(guestId, five.subList(1, 6)).claimed()).isPresent();
    }

    @Test
    void 공유_전체가_스무_개를_넘으면_가장_오래_쉬고_있는_사람의_것부터_버린다() {
        String idle = session.join(new FakeViewer(), "쉬는 사람", false);
        String idleFirst = pinned(idle, "i0", JPEG);
        String idleSecond = pinned(idle, "i1", JPEG);
        List<String> busy = new ArrayList<>();
        for (int i = 0; i < 3; i++) { // 세션의 보는 사람 상한이 다섯 명이다
            String viewer = session.join(new FakeViewer(), "v" + i, false);
            for (int k = 0; k < 5; k++) {
                busy.add(pinned(viewer, "b" + k, JPEG));
            }
        }
        for (int k = 0; k < 5; k++) {
            busy.add(pinned(guestId, "g" + k, JPEG));
        }
        // 22개 → 가장 오래 쉬고 있는 사람의 두 개가 빠진다. 방금 짚은 사람들의 것은 남는다
        assertThat(session.claimPins(idle, List.of(idleFirst)).missing()).containsExactly(idleFirst);
        assertThat(session.claimPins(idle, List.of(idleSecond)).missing()).containsExactly(idleSecond);
        assertThat(session.claimPins(guestId, busy.subList(15, 20)).claimed()).isPresent();
    }

    @Test
    void 같은_곳을_다시_짚으면_새_결과가_옛_결과를_대신한다() {
        String first = pinned(guestId, "a", JPEG);
        String again = pinned(guestId, "a", JPEG);

        assertThat(session.claimPins(guestId, List.of(first)).missing()).containsExactly(first);
        assertThat(session.claimPins(guestId, List.of(again)).claimed()).isPresent();
    }

    @Test
    void 다시_짚거나_pin_keep_을_보내면_시간이_늘지만_받은_때부터_15분까지다() {
        String kept = pinned(guestId, "a", JPEG);
        nanos += 4 * 60 * SECOND;
        session.onViewerMessage(guestId, msg("{\"type\":\"pin-keep\"}"), Instant.now());
        nanos += 4 * 60 * SECOND; // 받은 지 8분
        session.expirePins();
        assertThat(guest.types()).doesNotContain("error");
        assertThat(session.claimPins(guestId, List.of(kept)).claimed()).as("8분이어도 남는다").isPresent();

        String capped = pinned(guestId, "b", JPEG);
        for (int minute = 4; minute <= 16; minute += 4) {
            nanos += 4 * 60 * SECOND;
            session.onViewerMessage(guestId, msg("{\"type\":\"pin-keep\"}"), Instant.now());
        }
        session.expirePins();
        assertThat(session.claimPins(guestId, List.of(capped)).missing()).as("16분이면 없다").containsExactly(capped);
    }

    @Test
    void 여러_곳은_모두_있을_때만_가져가고_없는_곳을_알려_준다() {
        String a = pinned(guestId, "a", JPEG);
        String b = pinned(guestId, "b", JPEG);

        PinClaim partial = session.claimPins(guestId, List.of(a, "p99"));
        assertThat(partial.claimed()).isEmpty();
        assertThat(partial.missing()).containsExactly("p99");

        PinClaim both = session.claimPins(guestId, List.of(b, a));
        assertThat(both.claimed()).isPresent();
        assertThat(both.claimed().get().marks()).extracting(m -> m.resolution().probeId()).containsExactly(b, a);
        assertThat(session.claimPins(guestId, List.of(a)).missing()).as("가져가면 끝").containsExactly(a);
    }

    @Test
    void 화면_지문은_화면_종류의_해시와_픽셀_dHash_이고_같은_화면이면_화면_그림을_하나만_둔다() {
        byte[] top = com.example.dvely.deviceshare.support.PinTestImages.screen(0, 7);
        byte[] sameAgain = com.example.dvely.deviceshare.support.PinTestImages.screen(0, 7);
        byte[] scrolled = com.example.dvely.deviceshare.support.PinTestImages.screen(300, 7);

        String a = pinned(guestId, "a", top);
        JsonNode first = guest.last("pin").path("view");
        assertThat(first.path("screen").asText()).matches("[0-9a-f]{12}");
        assertThat(first.path("dhash").asText()).matches("[0-9a-f]{16}");
        assertThat(guest.last("pin").toString()).as("화면 이름은 손님에게 가지 않는다").doesNotContain("/settings");

        String b = pinned(guestId, "b", sameAgain);
        assertThat(guest.last("pin").path("view")).isEqualTo(first);
        String c = pinned(guestId, "c", scrolled);
        JsonNode moved = guest.last("pin").path("view");
        assertThat(moved.path("screen")).isEqualTo(first.path("screen"));
        assertThat(Long.bitCount(Long.parseUnsignedLong(moved.path("dhash").asText(), 16)
                ^ Long.parseUnsignedLong(first.path("dhash").asText(), 16))).isGreaterThan(ShareSession.SAME_VIEW_BITS);

        ClaimedPin claimed = session.claimPins(guestId, List.of(a, b)).claimed().orElseThrow();
        assertThat(claimed.marks().get(0).screen()).as("같은 화면이라 b 의 화면을 함께 쓴다").isEqualTo(sameAgain);
        assertThat(claimed.screen()).isEqualTo(sameAgain);
        assertThat(session.claimPins(guestId, List.of(c)).claimed().orElseThrow().screen()).isEqualTo(scrolled);
    }

    @Test
    void 큰_잘라_낸_그림은_256KiB_안으로_줄여_둔다() {
        byte[] big = com.example.dvely.deviceshare.support.PinTestImages.noise(700, 700, 3);
        assertThat(big.length).isBetween(ShareSession.MAX_KEPT_CROP_BYTES + 1, ShareSession.MAX_CROP_BYTES);

        String a = pinned(guestId, "a", JPEG, big);
        byte[] kept = session.claimPins(guestId, List.of(a)).claimed().orElseThrow().crop();
        assertThat(kept.length).isLessThanOrEqualTo(ShareSession.MAX_KEPT_CROP_BYTES);
        assertThat(kept[0] & 0xff).isEqualTo(0xff);
    }

    private static final String ELEMENT = """
            {"probeId":"%s","ok":true,"screen":{"label":"/settings"},
             "element":{"type":"button","text":"저장","bounds":{"x":0.1,"y":0.2,"w":0.3,"h":0.1}}}
            """;

    private void ref(String viewerId, String ref, double x, double y) {
        session.onViewerMessage(viewerId, msg("{\"type\":\"pin-probe\",\"ref\":\"" + ref + "\",\"x\":" + x + ",\"y\":" + y + "}"),
                Instant.now());
        nanos += 10;
    }

    /** 1초 뒤 짚고 바로 답을 받는다. @return 짚기 id */
    private String pinned(String viewerId, String ref, byte[] screen) {
        return pinned(viewerId, ref, screen, JPEG);
    }

    private String pinned(String viewerId, String ref, byte[] screen, byte[] crop) {
        nanos += SECOND;
        ref(viewerId, ref, 0.5, 0.5);
        String probeId = device.lastJson(ShareFrames.PIN_PROBE).path("probeId").asText();
        session.onDeviceFrame(device, result(ELEMENT.formatted(probeId), crop, screen));
        return probeId;
    }

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
