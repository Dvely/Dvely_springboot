package com.example.dvely.deviceshare;

import static com.example.dvely.support.JdbcRows.insert;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.apitoken.application.facade.ApiTokenFacade;
import com.example.dvely.apitoken.domain.value.ApiTokenScope;
import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
import com.example.dvely.deviceshare.application.service.ShareFeedbackService;
import com.example.dvely.deviceshare.application.session.ShareFrames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 짚기 피드백을 실제 Tomcat WebSocket·MySQL 로 끝까지 잇는다(#681, 설계 §14).
 *
 * <p>확인하는 것: 손님이 조작권 없이 짚어 보내면 {@code NEW} 로 저장되고 기기에 알림이 간다. 받기 전에는 개인 액세스 토큰으로
 * 보이지 않는다. 기기에서 받으면 보이고, 에이전트가 고쳤다고 표시하면 짚은 사람에게 간다. 다시 들어온 짚은 사람은 확인 키로
 * 확인한다. 주인이 짚은 것은 처음부터 받은 상태다. 끝난 링크의 피드백은 14일 뒤 지워지고, 탈퇴하면 함께 지워진다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShareFeedbackIntegrationTest {

    private static final String MARK = "ds681-";
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x11, 0x22};

    @LocalServerPort private int port;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ApiTokenFacade apiTokens;
    @Autowired private ShareFeedbackService feedbackService;

    private final HttpClient http = HttpClient.newHttpClient();
    private long userId;
    private String bearer;

    @BeforeEach
    void setUp() {
        userId = insert(jdbc, "insert into users (github_user_id, user_name) values (?, ?)", MARK + System.nanoTime(), MARK + "u");
        bearer = "Bearer " + jwtProvider.createToken(userId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from device_share_links where user_id = ?", userId);
        jdbc.update("delete from users where user_id = ?", userId);
    }

    @Test
    void 손님_피드백은_받기_전에는_에이전트에게_안_보이고_받으면_고침_확인까지_간다() throws Exception {
        JsonNode share = createShare();
        long shareId = share.path("shareId").asLong();
        String viewHost = URI.create(share.path("viewUrl").asText()).getHost() + ":8080";
        Device device = connectDevice(share);
        String read = "Bearer " + apiTokens.issue(userId, ApiTokenScope.READ, "read", 1).plaintext();
        String write = "Bearer " + apiTokens.issue(userId, ApiTokenScope.WRITE, "write", 1).plaintext();

        String key;
        long feedbackId;
        try (RawViewerSocket socket = new RawViewerSocket(port, viewHost, "/__qeploy/ws")) {
            Viewer guest = new Viewer(socket);
            socket.sendText("{\"type\":\"join\",\"name\":\"민지\"}");
            guest.nextOfType("welcome");
            sendConfig(device);
            assertThat(guest.nextOfType("config").path("pin").asBoolean()).isTrue();

            // 짚기 → 기기가 요소를 푼다 → 테두리·이름만 돌아온다
            socket.sendText("{\"type\":\"pin-probe\",\"x\":0.42,\"y\":0.17}");
            JsonNode probe = json.readTree(device.device.nextOfKind(ShareFrames.PIN_PROBE).payloadText());
            device.tunnel.sendBinary(result(probe.path("probeId").asText()), true).join();
            JsonNode resolved = guest.nextOfType("pin", "resolved");
            assertThat(resolved.path("label").asText()).isEqualTo("<button> \"저장\"");
            assertThat(resolved.toString()).doesNotContain("SaveButton.tsx");

            // 글을 붙여 보낸다 → NEW, 기기에 알림
            socket.sendText(json.createObjectNode().put("type", "pin-submit").put("probeId", probe.path("probeId").asText())
                    .put("comment", "더 크게, 파란색으로").toString());
            JsonNode saved = guest.nextOfType("feedback");
            assertThat(saved.path("state").asText()).isEqualTo("NEW");
            key = saved.path("key").asText();
            feedbackId = saved.path("id").asLong();
            assertThat(key).hasSize(26);
            JsonNode notice = json.readTree(device.device.nextOfKind(ShareFrames.FEEDBACK_NOTICE).payloadText());
            assertThat(notice.path("feedbackId").asLong()).isEqualTo(feedbackId);
            assertThat(notice.path("state").asText()).isEqualTo("NEW");
            assertThat(notice.path("author").path("name").asText()).isEqualTo("민지");
            assertThat(notice.path("comment").asText()).isEqualTo("더 크게, 파란색으로");

            // 받기 전: 에이전트(PAT)에게는 없다. 브라우저 로그인은 본다
            assertThat(data(get("/api/v1/device-share-feedback?status=NEW,ACCEPTED", read))).isEmpty();
            assertThat(data(get("/api/v1/device-shares/" + shareId + "/feedback", read))).isEmpty();
            assertThat(get("/api/v1/device-share-feedback/" + feedbackId, read).statusCode()).isEqualTo(404);
            assertThat(get("/api/v1/device-share-feedback/" + feedbackId + "/prompt", read).statusCode()).isEqualTo(404);
            JsonNode detail = data(get("/api/v1/device-share-feedback/" + feedbackId, bearer));
            assertThat(detail.path("feedback").path("status").asText()).isEqualTo("NEW");
            assertThat(detail.path("hasCrop").asBoolean()).isTrue();
            assertThat(detail.path("element").path("sourceHints").get(0).path("file").asText())
                    .isEqualTo("src/components/SaveButton.tsx");

            // 기기에서 받는다
            device.tunnel.sendBinary(ShareFrames.encode(ShareFrames.FEEDBACK_DECISION,
                    "{\"feedbackId\":" + feedbackId + ",\"accepted\":true}"), true).join();
            assertThat(guest.nextOfType("feedback").path("state").asText()).isEqualTo("ACCEPTED");
            assertThat(data(get("/api/v1/device-share-feedback", read))).hasSize(1);
            String prompt = data(get("/api/v1/device-share-feedback/" + feedbackId + "/prompt", read)).path("markdown").asText();
            assertThat(prompt).contains("```text\n더 크게, 파란색으로\n```").contains("src/components/SaveButton.tsx:12");
            HttpResponse<byte[]> image = http.send(request("/api/v1/device-share-feedback/" + feedbackId + "/image?kind=crop", read)
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(image.statusCode()).isEqualTo(200);
            assertThat(image.headers().firstValue("Content-Type").orElse("")).startsWith("image/jpeg");
            assertThat(image.body()).isEqualTo(JPEG);

            // 에이전트는 고쳤다고만 표시한다. 읽기 토큰·받기(PATCH)는 막힌다
            assertThat(post("/api/v1/device-share-feedback/" + feedbackId + "/fixed", read, "{}").statusCode()).isEqualTo(403);
            assertThat(patch("/api/v1/device-share-feedback/" + feedbackId, write, "{\"status\":\"DISMISSED\"}").statusCode())
                    .isEqualTo(403);
            HttpResponse<String> fixed = post("/api/v1/device-share-feedback/" + feedbackId + "/fixed", write,
                    "{\"note\":\"버튼 패딩을 늘렸습니다\"}");
            assertThat(fixed.statusCode()).isEqualTo(200);
            JsonNode fixedMessage = guest.nextOfType("feedback");
            assertThat(fixedMessage.path("state").asText()).isEqualTo("FIXED");
            assertThat(fixedMessage.path("note").asText()).isEqualTo("버튼 패딩을 늘렸습니다");
            assertThat(post("/api/v1/device-share-feedback/" + feedbackId + "/fixed", write, "{}").statusCode())
                    .as("이미 고친 것").isEqualTo(409);
        }

        // 폰이 잠들어 다시 들어왔다: 확인 키로 지금 상태를 받고 확인한다
        try (RawViewerSocket socket = new RawViewerSocket(port, viewHost, "/__qeploy/ws")) {
            Viewer again = new Viewer(socket);
            socket.sendText("{\"type\":\"join\",\"name\":\"민지\"}");
            again.nextOfType("welcome");
            socket.sendText("{\"type\":\"feedback-confirm\",\"id\":" + feedbackId + ",\"key\":\"" + "a".repeat(26) + "\"}");
            assertThat(again.nextOfType("error").path("message").asText()).contains("확인할 수 없습니다");
            socket.sendText("{\"type\":\"feedback-watch\",\"items\":[{\"id\":" + feedbackId + ",\"key\":\"" + key + "\"}]}");
            JsonNode watched = again.nextOfType("feedback");
            assertThat(watched.path("state").asText()).isEqualTo("FIXED");
            assertThat(watched.path("note").asText()).isEqualTo("버튼 패딩을 늘렸습니다");
            socket.sendText("{\"type\":\"feedback-confirm\",\"id\":" + feedbackId + ",\"key\":\"" + key + "\"}");
            assertThat(again.nextOfType("feedback").path("state").asText()).isEqualTo("CONFIRMED");
        }
        assertThat(jdbc.queryForList("select concat(author_role, ':', kind) from device_share_feedback_notes "
                + "where feedback_id = ? order by note_id", String.class, feedbackId))
                .containsExactly("AGENT:FIXED", "GUEST:CONFIRM");
        device.tunnel.abort();
    }

    @Test
    void 주인이_짚은_것은_처음부터_받은_상태이고_손님이_다시_열면_다시_받아야_한다() throws Exception {
        JsonNode share = createShare();
        String viewHost = URI.create(share.path("viewUrl").asText()).getHost() + ":8080";
        String controlUrl = share.path("controlUrl").asText();
        String secret = controlUrl.substring(controlUrl.indexOf("#c=") + 3);
        Device device = connectDevice(share);
        sendConfig(device);

        try (RawViewerSocket socket = new RawViewerSocket(port, viewHost, "/__qeploy/ws")) {
            Viewer owner = new Viewer(socket);
            socket.sendText("{\"type\":\"join\",\"controlSecret\":\"" + secret + "\"}");
            owner.nextOfType("welcome");
            socket.sendText("{\"type\":\"pin-probe\",\"x\":0.5,\"y\":0.5}");
            String probeId = json.readTree(device.device.nextOfKind(ShareFrames.PIN_PROBE).payloadText()).path("probeId").asText();
            device.tunnel.sendBinary(result(probeId), true).join();
            owner.nextOfType("pin", "resolved");
            socket.sendText("{\"type\":\"pin-submit\",\"probeId\":\"" + probeId + "\",\"comment\":\"제목을 줄여 줘\"}");
            JsonNode saved = owner.nextOfType("feedback");
            assertThat(saved.path("state").asText()).isEqualTo("ACCEPTED");
            assertThat(json.readTree(device.device.nextOfKind(ShareFrames.FEEDBACK_NOTICE).payloadText())
                    .path("author").path("role").asText()).isEqualTo("OWNER");
            long id = saved.path("id").asLong();

            // 주인이 다시 열면 글이 붙어도 주인 것이라 바로 ACCEPTED
            assertThat(post("/api/v1/device-share-feedback/" + id + "/fixed", bearer, "{}").statusCode()).isEqualTo(200);
            owner.nextOfType("feedback", "FIXED");
            socket.sendText("{\"type\":\"feedback-reopen\",\"id\":" + id + ",\"key\":\"" + saved.path("key").asText()
                    + "\",\"comment\":\"아직 길어\"}");
            assertThat(owner.nextOfType("feedback", "ACCEPTED").path("id").asLong()).isEqualTo(id);
        }

        // 손님이 다시 열면 손님 글이 새로 붙으므로 NEW 로 돌아가 주인이 다시 받아야 한다
        try (RawViewerSocket socket = new RawViewerSocket(port, viewHost, "/__qeploy/ws")) {
            Viewer guest = new Viewer(socket);
            socket.sendText("{\"type\":\"join\",\"name\":\"손님\"}");
            guest.nextOfType("welcome");
            socket.sendText("{\"type\":\"pin-probe\",\"x\":0.3,\"y\":0.3}");
            String probeId = json.readTree(device.device.nextOfKind(ShareFrames.PIN_PROBE).payloadText()).path("probeId").asText();
            device.tunnel.sendBinary(result(probeId), true).join();
            guest.nextOfType("pin", "resolved");
            socket.sendText("{\"type\":\"pin-submit\",\"probeId\":\"" + probeId + "\",\"comment\":\"버튼이 작아요\"}");
            JsonNode saved = guest.nextOfType("feedback", "NEW");
            long id = saved.path("id").asLong();
            assertThat(patch("/api/v1/device-share-feedback/" + id, bearer, "{\"status\":\"ACCEPTED\"}").statusCode()).isEqualTo(200);
            guest.nextOfType("feedback", "ACCEPTED");
            assertThat(patch("/api/v1/device-share-feedback/" + id, bearer, "{\"status\":\"FIXED\"}").statusCode())
                    .as("고침은 POST …/fixed 로만").isEqualTo(400);
            assertThat(post("/api/v1/device-share-feedback/" + id + "/fixed", bearer, "{\"note\":\"키웠어요\"}").statusCode())
                    .isEqualTo(200);
            guest.nextOfType("feedback", "FIXED");
            socket.sendText("{\"type\":\"feedback-reopen\",\"id\":" + id + ",\"key\":\"" + saved.path("key").asText()
                    + "\",\"comment\":\"아직 작아요\"}");
            guest.nextOfType("feedback", "NEW");
            JsonNode notice;
            do {
                notice = json.readTree(device.device.nextOfKind(ShareFrames.FEEDBACK_NOTICE).payloadText());
            } while (notice.path("feedbackId").asLong() != id || !"NEW".equals(notice.path("state").asText())
                    || !"아직 작아요".equals(notice.path("comment").asText()));
            assertThat(post("/api/v1/device-share-feedback/" + id + "/fixed", bearer, "{}").statusCode())
                    .as("다시 받기 전에는 고칠 수 없다").isEqualTo(409);
        }
        device.tunnel.abort();
    }

    @Test
    void 끝난_링크의_피드백은_14일_뒤_지우고_탈퇴하면_함께_지워진다() {
        long oldLink = insertLink(LocalDateTime.now().minusDays(15));
        long recentLink = insertLink(LocalDateTime.now().minusDays(13));
        long oldFeedback = insertFeedback(oldLink, "ACCEPTED");
        long recentFeedback = insertFeedback(recentLink, "ACCEPTED");
        jdbc.update("insert into device_share_feedback_images (feedback_id, crop) values (?, ?)", oldFeedback, JPEG);
        jdbc.update("insert into device_share_feedback_notes (feedback_id, author_role, kind, body) values (?, 'OWNER', 'FIXED', '')",
                oldFeedback);

        feedbackService.purgeEnded();

        assertThat(count("device_share_feedback", oldFeedback)).isZero();
        assertThat(count("device_share_feedback_images", oldFeedback)).isZero();
        assertThat(count("device_share_feedback_notes", oldFeedback)).isZero();
        assertThat(count("device_share_feedback", recentFeedback)).isEqualTo(1);

        jdbc.update("delete from users where user_id = ?", userId);
        assertThat(count("device_share_feedback", recentFeedback)).as("users → links → feedback CASCADE").isZero();
    }

    // ------------------------------------------------------------------ 도우미

    private int count(String table, long feedbackId) {
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where feedback_id = ?", Integer.class, feedbackId);
        return n == null ? 0 : n;
    }

    private long insertLink(LocalDateTime expiresAt) {
        return insert(jdbc, """
                insert into device_share_links (user_id, kind, target_label, token_hash, control_secret_hash, token_prefix, expires_at)
                values (?, 'WEB', 'x', ?, ?, 'abcdef', ?)
                """, userId, String.format("%064d", System.nanoTime()), "0".repeat(64), expiresAt);
    }

    private long insertFeedback(long shareId, String status) {
        return insert(jdbc, """
                insert into device_share_feedback (share_id, author_role, author_name, author_key_hash, kind, element_json, body, status)
                values (?, 'GUEST', '손님', ?, 'WEB', '{}', '글', ?)
                """, shareId, "0".repeat(64), status);
    }

    @Test
    void 넓힌_범위를_골라_보내면_주인_알림_목록_그림_프롬프트가_모두_그_범위다() throws Exception {
        JsonNode share = createShare();
        String viewHost = URI.create(share.path("viewUrl").asText()).getHost() + ":8080";
        Device device = connectDevice(share);
        sendConfig(device);
        byte[] screen = screenJpeg();

        try (RawViewerSocket socket = new RawViewerSocket(port, viewHost, "/__qeploy/ws")) {
            Viewer guest = new Viewer(socket);
            socket.sendText("{\"type\":\"join\",\"name\":\"민지\"}");
            guest.nextOfType("welcome");
            socket.sendText("{\"type\":\"pin-probe\",\"x\":0.2,\"y\":0.52}");
            String probeId = json.readTree(device.device.nextOfKind(ShareFrames.PIN_PROBE).payloadText()).path("probeId").asText();
            device.tunnel.sendBinary(scopedResult(probeId, screen), true).join();
            JsonNode pin = guest.nextOfType("pin", "resolved");
            assertThat(pin.path("scopes").findValuesAsText("label"))
                    .containsExactly("<button> \"저장\" · SaveButton", "폼 #profile", "화면 전체");
            socket.sendText("{\"type\":\"pin-submit\",\"probeId\":\"" + probeId + "\",\"scope\":1,\"comment\":\"폼 여백을 넓혀 주세요\"}");
            long id = guest.nextOfType("feedback", "NEW").path("id").asLong();

            // 주인이 터미널에서 받을지 고르는 근거가 손님이 고른 범위다
            JsonNode notice = json.readTree(device.device.nextOfKind(ShareFrames.FEEDBACK_NOTICE).payloadText());
            assertThat(notice.path("label").asText()).isEqualTo("컴포넌트 ProfileForm");
            assertThat(data(get("/api/v1/device-shares/" + share.path("shareId").asLong() + "/feedback", bearer))
                    .get(0).path("label").asText()).isEqualTo("컴포넌트 ProfileForm");

            // 잘라 낸 그림은 서버가 화면에서 그 범위로 자른 것이다(기기는 버튼만 잘라 보냈다)
            HttpResponse<byte[]> crop = http.send(request("/api/v1/device-share-feedback/" + id + "/image?kind=crop", bearer)
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(crop.statusCode()).isEqualTo(200);
            java.awt.image.BufferedImage cut = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(crop.body()));
            assertThat(cut.getWidth()).as("폼 테두리(화면 폭 90%%) + 여백").isBetween(350, 390);
            assertThat(cut.getHeight()).isBetween(330, 360);

            String md = data(get("/api/v1/device-share-feedback/" + id + "/prompt", bearer)).path("markdown").asText();
            assertThat(md).contains("- 고른 범위: 폼 `form#profile` · 컴포넌트 `ProfileForm` — 짚은 요소를 품은 범위를 골랐다");

            // 화면 전체를 고르면 그림은 화면 그대로다. 같은 사람의 짚기는 초당 한 번이다
            Thread.sleep(1_100);
            socket.sendText("{\"type\":\"pin-probe\",\"x\":0.2,\"y\":0.52}");
            probeId = json.readTree(device.device.nextOfKind(ShareFrames.PIN_PROBE).payloadText()).path("probeId").asText();
            device.tunnel.sendBinary(scopedResult(probeId, screen), true).join();
            guest.nextOfType("pin", "resolved");
            socket.sendText("{\"type\":\"pin-submit\",\"probeId\":\"" + probeId + "\",\"scope\":2,\"comment\":\"전체가 어두워요\"}");
            long whole = guest.nextOfType("feedback", "NEW").path("id").asLong();
            HttpResponse<byte[]> wholeCrop = http.send(request("/api/v1/device-share-feedback/" + whole + "/image?kind=crop", bearer)
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(wholeCrop.body()).isEqualTo(screen);
        }
        device.tunnel.abort();
    }

    /** 390×844 화면. 폼 자리(y 30~70%)만 파랗다 */
    private static byte[] screenJpeg() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(390, 844, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 390, 844);
        g.setColor(new java.awt.Color(37, 99, 235));
        g.fillRect(20, 253, 351, 338);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private static ByteBuffer scopedResult(String probeId, byte[] screen) {
        byte[] body = ("""
                {"probeId":"%s","ok":true,"screen":{"label":"/settings"},"point":{"x":0.2,"y":0.52},
                 "element":{"type":"button","text":"저장","bounds":{"x":0.1,"y":0.5,"w":0.2,"h":0.05}},
                 "components":[{"name":"SaveButton","file":"src/components/SaveButton.tsx","line":3}],
                 "scopes":[{"kind":"element","type":"button","text":"저장","component":"SaveButton",
                             "bounds":{"x":0.1,"y":0.5,"w":0.2,"h":0.05}},
                           {"kind":"component","type":"form","id":"profile","component":"ProfileForm","selector":"form#profile",
                             "bounds":{"x":0.05,"y":0.3,"w":0.9,"h":0.4}},
                           {"kind":"screen","type":"screen","bounds":{"x":0,"y":0,"w":1,"h":1}}],
                 "defaultScope":0}
                """.formatted(probeId)).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
        out.writeBytes(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt(JPEG.length).array());
        out.writeBytes(JPEG);
        out.writeBytes(screen);
        return ShareFrames.encode(ShareFrames.PIN_RESULT, out.toByteArray());
    }

    private static ByteBuffer result(String probeId) {
        byte[] body = ("""
                {"probeId":"%s","ok":true,"screen":{"label":"/settings"},"point":{"x":0.42,"y":0.17},
                 "element":{"type":"button","text":"저장","bounds":{"x":0.38,"y":0.15,"w":0.12,"h":0.04}},
                 "components":[{"name":"SaveButton","file":"src/components/SaveButton.tsx","line":12}],
                 "sourceHints":[{"file":"src/components/SaveButton.tsx","line":12,"reason":"COMPONENT"}]}
                """.formatted(probeId)).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
        out.writeBytes(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt(JPEG.length).array());
        out.writeBytes(JPEG);
        out.writeBytes(JPEG);
        return ShareFrames.encode(ShareFrames.PIN_RESULT, out.toByteArray());
    }

    private record Device(WebSocket tunnel, DeviceListener device) {
    }

    private Device connectDevice(JsonNode share) throws Exception {
        DeviceListener listener = new DeviceListener();
        WebSocket tunnel = http.newWebSocketBuilder().header("Authorization", bearer)
                .buildAsync(URI.create(share.path("tunnelUrl").asText()), listener).get(5, TimeUnit.SECONDS);
        tunnel.sendBinary(ShareFrames.encode(ShareFrames.HELLO,
                "{\"cliVersion\":\"test\",\"kind\":\"WEB\",\"features\":[\"pin\"]}"), true).join();
        assertThat(listener.nextOfKind(ShareFrames.WELCOME)).isNotNull();
        return new Device(tunnel, listener);
    }

    private static void sendConfig(Device device) {
        device.tunnel().sendBinary(ShareFrames.encode(ShareFrames.SCREEN_CONFIG,
                "{\"format\":\"jpeg\",\"width\":390,\"height\":844,\"url\":\"http://localhost:5173/settings\",\"title\":\"t\"}"), true).join();
    }

    private JsonNode createShare() throws Exception {
        String body = json.createObjectNode().put("kind", "WEB").put("targetLabel", "http://localhost:5173").toString();
        HttpResponse<String> created = post("/api/v1/device-shares", bearer, body);
        assertThat(created.statusCode()).isEqualTo(201);
        return json.readTree(created.body()).path("data");
    }

    private HttpRequest.Builder request(String path, String auth) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", auth);
    }

    private HttpResponse<String> get(String path, String auth) throws Exception {
        return http.send(request(path, auth).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String auth, String body) throws Exception {
        return http.send(request(path, auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String auth, String body) throws Exception {
        return http.send(request(path, auth).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    /** JDK WebSocket 으로 흉내 낸 CLI. */
    private static final class DeviceListener implements WebSocket.Listener {
        private final BlockingQueue<ShareFrames.Frame> frames = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partial.writeBytes(chunk);
            if (last) {
                frames.add(ShareFrames.decode(ByteBuffer.wrap(partial.toByteArray())));
                partial.reset();
            }
            webSocket.request(1);
            return null;
        }

        ShareFrames.Frame nextOfKind(int kind) throws InterruptedException {
            for (int i = 0; i < 30; i++) {
                ShareFrames.Frame frame = frames.poll(5, TimeUnit.SECONDS);
                assertThat(frame).as("기기 프레임 0x" + Integer.toHexString(kind)).isNotNull();
                if (frame.kind() == kind) {
                    return frame;
                }
            }
            throw new AssertionError("0x" + Integer.toHexString(kind) + " 를 받지 못했다");
        }
    }

    /** 브라우저 대신 쓰는 보는 사람. */
    private final class Viewer {
        private final RawViewerSocket socket;

        Viewer(RawViewerSocket socket) {
            this.socket = socket;
        }

        JsonNode nextOfType(String type) throws Exception {
            return nextOfType(type, null);
        }

        /** @param state 주면 {@code state} 가 이 값인 메시지까지 넘긴다 */
        JsonNode nextOfType(String type, String state) throws Exception {
            for (int i = 0; i < 30; i++) {
                RawViewerSocket.Message message = socket.messages.poll(5, TimeUnit.SECONDS);
                assertThat(message).as(type + " 메시지").isNotNull();
                if (message.text()) {
                    JsonNode node = json.readTree(message.asText());
                    if (type.equals(node.path("type").asText())
                            && (state == null || state.equals(node.path("state").asText()))) {
                        return node;
                    }
                }
            }
            throw new AssertionError(type + " 를 받지 못했다");
        }
    }
}
