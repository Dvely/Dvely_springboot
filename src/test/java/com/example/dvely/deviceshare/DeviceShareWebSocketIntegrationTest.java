package com.example.dvely.deviceshare;

import static com.example.dvely.support.JdbcRows.insert;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
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
import java.time.Duration;
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
 * 기기 터널과 뷰어 소켓을 실제 Tomcat WebSocket 으로 끝까지 잇는다(#652).
 *
 * <p>확인하는 것: PAT·JWT 헤더로 터널 인증, 공유 오리진 Host 로만 뷰어 소켓이 열림, 8KiB 보다 큰 화면 한 장(Tomcat 기본
 * 버퍼를 넘는 크기)이 보는 사람에게 그대로 감, 조작권 가진 입력이 기기로 감, 회수하면 양쪽이 끊김. 2단계(§12): H.264 장이
 * flags 바이트째 순서대로 가고, 늦게 온 사람이 GOP 를 받고, GOP 가 비었으면 기기에 키 프레임을 요청함.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceShareWebSocketIntegrationTest {

    private static final String MARK = "ds652w-";

    @LocalServerPort private int port;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

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
    void 기기_화면이_보는_사람에게_가고_조작권_입력이_기기로_가며_회수하면_끊긴다() throws Exception {
        JsonNode share = createShare();
        String viewHost = URI.create(share.path("viewUrl").asText()).getHost();
        String controlUrl = share.path("controlUrl").asText();
        String secret = controlUrl.substring(controlUrl.indexOf("#c=") + 3);

        // 기기 연결
        Device device = new Device();
        WebSocket tunnel = http.newWebSocketBuilder().header("Authorization", bearer)
                .buildAsync(URI.create(share.path("tunnelUrl").asText()), device).get(5, TimeUnit.SECONDS);
        tunnel.sendBinary(ShareFrames.encode(ShareFrames.HELLO, "{\"cliVersion\":\"test\",\"kind\":\"WEB\"}"), true).join();
        assertThat(device.next().kind()).isEqualTo(ShareFrames.WELCOME);

        // 주인으로 보기(브라우저처럼 공유 오리진 Host 로)
        RawViewerSocket viewerSocket = new RawViewerSocket(port, viewHost + ":8080", "/__qeploy/ws");
        assertThat(viewerSocket.status).isEqualTo(101);
        Viewer viewer = new Viewer(viewerSocket);
        viewerSocket.sendText("{\"type\":\"join\",\"name\":\"x\",\"controlSecret\":\"" + secret + "\"}");
        assertThat(viewer.nextOfType("welcome").path("name").asText()).isEqualTo("주인");
        assertThat(viewer.nextOfType("control").path("state").asText()).isEqualTo("granted");

        // 화면 설정과 큰 화면 한 장
        tunnel.sendBinary(ShareFrames.encode(ShareFrames.SCREEN_CONFIG,
                "{\"format\":\"jpeg\",\"width\":1280,\"height\":800,\"url\":\"http://localhost:5173/\",\"title\":\"t\"}"), true).join();
        assertThat(viewer.nextOfType("config").path("width").asInt()).isEqualTo(1280);
        byte[] jpeg = new byte[300 * 1024];
        jpeg[0] = (byte) 0xff;
        jpeg[jpeg.length - 1] = 0x7;
        tunnel.sendBinary(ShareFrames.encode(ShareFrames.SCREEN_FRAME, jpeg), true).join();
        byte[] received = viewer.nextFrame();
        assertThat(received).hasSize(jpeg.length);
        assertThat(received[received.length - 1]).isEqualTo((byte) 0x7);

        // 입력
        viewerSocket.sendText("{\"type\":\"input\",\"input\":{\"type\":\"text\",\"text\":\"한글 입력\"}}");
        ShareFrames.Frame input = device.nextOfKind(ShareFrames.INPUT);
        assertThat(json.readTree(input.payloadText()).path("text").asText()).isEqualTo("한글 입력");

        // 회수
        HttpResponse<String> revoked = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/device-shares/" + share.path("shareId").asLong()))
                .header("Authorization", bearer).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(revoked.statusCode()).isEqualTo(204);
        assertThat(viewer.nextOfType("ended").path("reason").asText()).isEqualTo("revoked");
        assertThat(json.readTree(device.nextOfKind(ShareFrames.GOODBYE).payloadText()).path("reason").asText())
                .isEqualTo("revoked");
    }

    @Test
    void Android_공유는_H264_장을_그대로_순서대로_주고_늦게_온_사람에게_GOP_를_준다() throws Exception {
        JsonNode share = createShare("ANDROID", "emulator-5554");
        String viewHost = URI.create(share.path("viewUrl").asText()).getHost() + ":8080";
        Device device = new Device();
        WebSocket tunnel = http.newWebSocketBuilder().header("Authorization", bearer)
                .buildAsync(URI.create(share.path("tunnelUrl").asText()), device).get(5, TimeUnit.SECONDS);
        try (RawViewerSocket firstSocket = new RawViewerSocket(port, viewHost, "/__qeploy/ws");
             RawViewerSocket secondSocket = new RawViewerSocket(port, viewHost, "/__qeploy/ws");
             RawViewerSocket lateSocket = new RawViewerSocket(port, viewHost, "/__qeploy/ws")) {
            tunnel.sendBinary(ShareFrames.encode(ShareFrames.HELLO, "{\"cliVersion\":\"test\",\"kind\":\"ANDROID\"}"), true).join();
            assertThat(device.next().kind()).isEqualTo(ShareFrames.WELCOME);

            // 설정 전에 들어온 사람
            Viewer first = new Viewer(firstSocket);
            firstSocket.sendText("{\"type\":\"join\",\"name\":\"first\"}");
            first.nextOfType("welcome");
            tunnel.sendBinary(ShareFrames.encode(ShareFrames.SCREEN_CONFIG, "{\"format\":\"h264\",\"codec\":\"avc1.42C01F\","
                    + "\"width\":720,\"height\":1280,\"url\":\"com.example.app\",\"title\":\"Pixel_8\"}"), true).join();
            JsonNode config = first.nextOfType("config");
            assertThat(config.path("format").asText()).isEqualTo("h264");
            assertThat(config.path("codec").asText()).isEqualTo("avc1.42C01F");
            assertThat(config.path("kind").asText()).isEqualTo("ANDROID");

            // 설정 뒤, 아직 장이 없을 때 들어오면 기기에 키 프레임을 요청한다
            Viewer second = new Viewer(secondSocket);
            secondSocket.sendText("{\"type\":\"join\",\"name\":\"second\"}");
            assertThat(device.nextOfKind(ShareFrames.KEYFRAME_REQUEST).payloadText()).isEqualTo("{}");

            byte[] key = new byte[200 * 1024];
            key[0] = 1; // flags: 키 프레임
            key[key.length - 1] = 0x7;
            byte[] delta = {0, 0, 0, 0, 1, 0x41, 0x9};
            tunnel.sendBinary(ShareFrames.encode(ShareFrames.SCREEN_FRAME, key), true).join();
            tunnel.sendBinary(ShareFrames.encode(ShareFrames.SCREEN_FRAME, delta), true).join();
            for (Viewer viewer : new Viewer[]{first, second}) {
                assertThat(viewer.nextFrame()).isEqualTo(key);
                assertThat(viewer.nextFrame()).isEqualTo(delta);
            }

            // 늦게 온 사람은 설정 뒤에 키 프레임부터 받는다
            Viewer late = new Viewer(lateSocket);
            lateSocket.sendText("{\"type\":\"join\",\"name\":\"late\"}");
            assertThat(late.nextOfType("config").path("codec").asText()).isEqualTo("avc1.42C01F");
            assertThat(late.nextFrame()).isEqualTo(key);
            assertThat(late.nextFrame()).isEqualTo(delta);
        } finally {
            tunnel.abort();
        }
    }

    @Test
    void 인증_없는_터널과_API_호스트의_뷰어_소켓은_열리지_않는다() throws Exception {
        JsonNode share = createShare();
        var noAuth = http.newWebSocketBuilder()
                .buildAsync(URI.create(share.path("tunnelUrl").asText()), new Device());
        assertThat(noAuth).failsWithin(Duration.ofSeconds(5));

        try (RawViewerSocket apiHost = new RawViewerSocket(port, "localhost:" + port, "/__qeploy/ws")) {
            assertThat(apiHost.status).isEqualTo(404);
        }
        try (RawViewerSocket wrongToken = new RawViewerSocket(port, "a".repeat(26) + ".share.localhost:8080", "/__qeploy/ws")) {
            assertThat(wrongToken.status).isEqualTo(404);
        }
    }

    private JsonNode createShare() throws Exception {
        return createShare("WEB", "http://localhost:5173");
    }

    private JsonNode createShare(String kind, String targetLabel) throws Exception {
        String body = json.createObjectNode().put("kind", kind).put("targetLabel", targetLabel).toString();
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/device-shares"))
                .header("Authorization", bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        return json.readTree(created.body()).path("data");
    }

    /** JDK WebSocket 으로 흉내 낸 CLI. */
    private static final class Device implements WebSocket.Listener {
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

        ShareFrames.Frame next() throws InterruptedException {
            ShareFrames.Frame frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("기기 프레임").isNotNull();
            return frame;
        }

        ShareFrames.Frame nextOfKind(int kind) throws InterruptedException {
            for (int i = 0; i < 20; i++) {
                ShareFrames.Frame frame = next();
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
            for (int i = 0; i < 20; i++) {
                RawViewerSocket.Message message = socket.messages.poll(5, TimeUnit.SECONDS);
                assertThat(message).as(type + " 메시지").isNotNull();
                if (message.text()) {
                    JsonNode node = json.readTree(message.asText());
                    if (type.equals(node.path("type").asText())) {
                        return node;
                    }
                }
            }
            throw new AssertionError(type + " 를 받지 못했다");
        }

        byte[] nextFrame() throws InterruptedException {
            for (int i = 0; i < 20; i++) {
                RawViewerSocket.Message message = socket.messages.poll(5, TimeUnit.SECONDS);
                assertThat(message).as("화면").isNotNull();
                if (!message.text()) {
                    return message.payload();
                }
            }
            throw new AssertionError("화면을 받지 못했다");
        }
    }
}
