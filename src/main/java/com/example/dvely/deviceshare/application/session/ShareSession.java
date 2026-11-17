package com.example.dvely.deviceshare.application.session;

import com.example.dvely.deviceshare.domain.service.ShareTokens;
import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유 링크 하나의 실시간 상태: 기기 연결, 보는 사람들, 조작권. 설계 §4·§5.
 *
 * <p>화면은 기기 {@code config} 의 {@code format} 으로 나눈다. {@code jpeg}(1단계)는 "최신 한 장"만 두고,
 * {@code h264}(2단계, §12.3)는 마지막 키 프레임부터의 장(GOP)을 두어 늦게 온 사람이 지금 화면부터 풀게 한다.</p>
 *
 * <p>상태는 이 객체의 모니터로 지킨다. 채널 보내기는 막히지 않으므로 잠금 안에서 불러도 된다.</p>
 */
@Slf4j
public class ShareSession {

    private static final Set<String> INPUT_TYPES = Set.of("pointer", "wheel", "key", "text", "nav");
    private static final String OWNER_NAME = "주인";
    private static final String JPEG = "jpeg";
    private static final String H264 = "h264";
    /** WebCodecs 코덱 문자열 {@code avc1.PPCCLL}. 이것만 뷰어로 넘긴다(설계 §12.2). */
    private static final Pattern AVC_CODEC = Pattern.compile("avc1\\.[0-9A-Fa-f]{6}");
    /** 보는 사람 큐 상한({@link VideoBacklog#DEFAULT_MAX_BYTES}, 3MiB)보다 작아야 늦게 온 사람에게 GOP 를 다 다시 보낼 수 있다. */
    static final int MAX_GOP_BYTES = 2 * 1024 * 1024;
    static final long KEYFRAME_REQUEST_INTERVAL_NANOS = 1_000_000_000L;
    /** 짚기(설계 §14). 기기가 HELLO {@code features} 에 이 값을 실어야 뷰어에 짚기 버튼이 나온다. */
    static final String PIN_FEATURE = "pin";
    /** §15.9: 기기가 알리는 짚기 기능. 이 순서로 {@code config.pinCaps} 에 싣는다 */
    static final List<String> PIN_CAPS = List.of(PIN_FEATURE, "pin-scope", "pin-screen", "pin-region");
    static final long PROBE_INTERVAL_NANOS = 1_000_000_000L;
    static final long PROBE_TIMEOUT_NANOS = 5_000_000_000L;
    static final long RESOLVED_TTL_NANOS = 5 * 60 * 1_000_000_000L;
    /** §15.9: 다시 짚거나 pin-keep 을 보내면 결과 시간을 늘리되, 받은 때부터 이만큼까지다 */
    static final long RESOLVED_MAX_NANOS = 15 * 60 * 1_000_000_000L;
    static final int MAX_PENDING_PROBES = 3;
    /** §15.9: 보는 사람마다 결과 다섯 개(여러 곳 ①~⑤), 공유 하나에 스무 개 */
    static final int MAX_RESOLVED_PER_VIEWER = 5;
    static final int MAX_RESOLVED_PINS = 20;
    /** 둔 결과의 잘라 낸 그림 상한. 넘으면 줄여 둔다 */
    static final int MAX_KEPT_CROP_BYTES = 256 * 1024;
    /** 화면 지문(dHash)이 이 비트 수까지 다르면 같은 화면이다(§15.5) */
    static final int SAME_VIEW_BITS = 10;
    /** 뷰어가 짚기마다 만드는 짝 이름(§15.5). 서버는 그 짚기의 모든 답에 그대로 돌려준다 */
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9]{1,8}");
    static final int MAX_WATCHED = 50;
    /**
     * 짚기 결과 JSON 상한. §14 는 16KiB 였고, 범위 사다리(§15.9)를 싣는 기기를 위해 48KiB 로 올렸다. 기기는 WELCOME 의
     * {@code pinResultMax} 를 보고 그만큼만 쓴다. 이 값을 모르는 옛 서버에는 16KiB 로 맞춘다.
     */
    static final int MAX_RESULT_JSON_BYTES = 48 * 1024;
    static final int MAX_CROP_BYTES = 512 * 1024;
    static final int MAX_SCREEN_BYTES = 1024 * 1024;

    private final long shareId;
    private final DeviceShareKind kind;
    private final LocalDateTime expiresAt;
    private final ObjectMapper json;
    private final int maxViewers;
    private final int maxFrameBytes;
    private final int maxInputsPerSecond;
    private final LongSupplier nanoClock;

    private DeviceChannel device;
    private boolean deviceReady;
    private String configMessage;
    private ScreenFormat screen = ScreenFormat.INITIAL;
    private byte[] lastFrame;
    /** h264 일 때 마지막 키 프레임과 그 뒤의 장. 비어 있으면 늦게 온 사람에게 줄 키 프레임이 없다. */
    private final Deque<byte[]> gop = new ArrayDeque<>();
    private long gopBytes;
    private boolean keyframeRequested;
    private long lastKeyframeRequestAt;
    private final Map<String, Viewer> viewers = new LinkedHashMap<>();
    private final Set<String> pendingRequests = new LinkedHashSet<>();
    private String controllerId;
    private Instant controlUntil;
    private int viewerSeq;
    private long inputWindowSecond;
    private int inputsInWindow;
    private boolean ended;
    private final PinResultSanitizer pinSanitizer;
    private final Set<String> deviceFeatures = new HashSet<>();
    private final Map<String, PendingProbe> probes = new LinkedHashMap<>();
    /** 보는 사람마다 짚기 결과(§15.9). 다른 사람이 짚어도 내 결과가 밀려나지 않는다 */
    private final Map<String, ViewerPins> pins = new HashMap<>();
    private final Map<String, Long> lastProbeAt = new HashMap<>();
    private final Map<String, Set<Long>> watching = new HashMap<>();
    private int probeSeq;

    private record Viewer(String id, String name, boolean owner, ViewerChannel channel) {
    }

    private record PendingProbe(String probeId, String viewerId, String ref, long sentAt) {
    }

    /** 한 보는 사람의 짚기 결과. 받은 순서다 */
    private static final class ViewerPins {
        final LinkedHashMap<String, ResolvedPin> results = new LinkedHashMap<>();
        long activeAt;
    }

    /** 둔 짚기 결과. 화면은 같은 화면의 더 나중 결과가 오면 그쪽에만 둔다(보는 사람마다 화면 하나, §15.9) */
    private static final class ResolvedPin {
        final PinResolution resolution;
        final String ref;
        final byte[] crop;
        final PinView view;
        final long resolvedAt;
        byte[] screen;
        long expiresAt;

        ResolvedPin(PinResolution resolution, String ref, byte[] crop, byte[] screen, PinView view, long resolvedAt) {
            this.resolution = resolution;
            this.ref = ref;
            this.crop = crop;
            this.screen = screen;
            this.view = view;
            this.resolvedAt = resolvedAt;
            this.expiresAt = resolvedAt + RESOLVED_TTL_NANOS;
        }
    }

    /**
     * 짚은 화면의 지문(§15.5). 화면 종류(경로·Activity·번들 ID·방향)의 해시와 화면 픽셀의 dHash. 뷰어는 여러 곳을 짚는 동안 화면이
     * 바뀌었는지 이것으로 안다. 화면 이름 대신 해시를 보내 손님에게 경로가 가지 않는다.
     *
     * @param dhash 화면 JPEG 를 읽지 못했으면 null
     */
    record PinView(String screen, Long dhash) {

        boolean sameAs(PinView other) {
            return other != null && screen.equals(other.screen) && dhash != null && other.dhash != null
                    && Long.bitCount(dhash ^ other.dhash) <= SAME_VIEW_BITS;
        }
    }

    /** 잠그기 전에 만든 짚기 결과. 이미지 읽기·줄이기는 세션을 잠그지 않고 한다 */
    private record PreparedPin(PinResolution resolution, byte[] crop, byte[] screen, PinView view) {
    }

    /** 뷰어 디코더 설정을 바꾸는 값. 이 중 하나라도 바뀌면 앞 GOP 는 새 설정으로 풀 수 없다. */
    private record ScreenFormat(String format, String codec, int width, int height) {
        static final ScreenFormat INITIAL = new ScreenFormat(JPEG, null, 0, 0);

        boolean h264() {
            return H264.equals(format);
        }
    }

    /** 1단계(웹) 세션. 테스트가 쓴다. */
    ShareSession(long shareId, LocalDateTime expiresAt, ObjectMapper json, int maxViewers, int maxFrameBytes,
                 int maxInputsPerSecond) {
        this(shareId, DeviceShareKind.WEB, expiresAt, json, maxViewers, maxFrameBytes, maxInputsPerSecond,
                System::nanoTime);
    }

    /** @param nanoClock 키 프레임 요청 간격을 재는 시계({@link System#nanoTime} 과 같은 단위) */
    ShareSession(long shareId, DeviceShareKind kind, LocalDateTime expiresAt, ObjectMapper json, int maxViewers,
                 int maxFrameBytes, int maxInputsPerSecond, LongSupplier nanoClock) {
        this.shareId = shareId;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.expiresAt = expiresAt;
        this.json = json;
        this.maxViewers = maxViewers;
        this.maxFrameBytes = maxFrameBytes;
        this.maxInputsPerSecond = maxInputsPerSecond;
        this.nanoClock = nanoClock;
        this.pinSanitizer = new PinResultSanitizer(json);
    }

    public long shareId() {
        return shareId;
    }

    LocalDateTime expiresAt() {
        return expiresAt;
    }

    public synchronized boolean deviceConnected() {
        return device != null;
    }

    public synchronized int viewerCount() {
        return viewers.size();
    }

    synchronized boolean isEnded() {
        return ended;
    }

    // ------------------------------------------------------------------ 기기

    /** @return 대체된 옛 연결이 있었는지 */
    synchronized boolean attachDevice(DeviceChannel channel) {
        DeviceChannel previous = device;
        device = channel;
        deviceReady = false;
        deviceFeatures.clear();
        failPendingProbes("offline");
        clearGop(); // 새 연결은 인코더도 새것이라 옛 GOP 뒤에 이어 붙일 수 없다
        if (previous != null && previous != channel) {
            previous.sendBinary(ShareFrames.encode(ShareFrames.GOODBYE, object("reason", "replaced").toString()));
            previous.close("replaced");
            return true;
        }
        return false;
    }

    synchronized void detachDevice(DeviceChannel channel) {
        if (device != channel) {
            return; // 이미 새 연결로 대체됐다
        }
        device = null;
        deviceReady = false;
        deviceFeatures.clear();
        failPendingProbes("offline");
        lastFrame = null;
        clearGop();
        pendingRequests.clear();
        broadcast(object("type", "device").put("state", "offline").toString());
    }

    void onDeviceFrame(DeviceChannel channel, ShareFrames.Frame frame) {
        if (frame.kind() == ShareFrames.PIN_RESULT) {
            // 화면 JPEG 를 읽어 지문을 만들고 큰 그림을 줄인다. 그동안 화면 프레임이 막히지 않게 잠그지 않고 한다
            PreparedPin prepared = preparePinResult(frame);
            if (prepared != null) {
                applyPinResult(channel, prepared);
            }
            return;
        }
        onDeviceFrameLocked(channel, frame);
    }

    private synchronized void onDeviceFrameLocked(DeviceChannel channel, ShareFrames.Frame frame) {
        if (device != channel || ended) {
            return;
        }
        switch (frame.kind()) {
            case ShareFrames.HELLO -> {
                deviceReady = true;
                readFeatures(frame);
                ObjectNode welcome = object("shareId", shareId).put("expiresAt", expiresAt.toString())
                        .put("pinResultMax", MAX_RESULT_JSON_BYTES);
                channel.sendBinary(ShareFrames.encode(ShareFrames.WELCOME, welcome.toString()));
                broadcast(object("type", "device").put("state", "online").toString());
                sendPresenceToDevice();
            }
            case ShareFrames.SCREEN_CONFIG -> onScreenConfig(frame);
            case ShareFrames.SCREEN_FRAME -> onScreenFrame(frame);
            case ShareFrames.CONTROL_DECISION -> onControlDecision(frame);
            default -> log.debug("[DeviceShare] 모르는 기기 프레임 share={} kind=0x{}", shareId,
                    Integer.toHexString(frame.kind()));
        }
    }

    private void onScreenConfig(ShareFrames.Frame frame) {
        JsonNode node = parse(frame.payloadText());
        if (node == null || !node.isObject()) {
            return;
        }
        // 모르는 format 은 jpeg 로 본다. h264 인데 코덱 문자열이 틀리면 뷰어가 디코더를 만들 수 없으니 설정째 버린다.
        String format = H264.equals(node.path("format").asText("")) ? H264 : JPEG;
        String codec = null;
        if (H264.equals(format)) {
            codec = node.path("codec").asText("");
            if (!AVC_CODEC.matcher(codec).matches()) {
                log.debug("[DeviceShare] 코덱 문자열이 틀린 h264 설정을 버림 share={}", shareId);
                return;
            }
        }
        ScreenFormat next = new ScreenFormat(format, codec, node.path("width").asInt(0), node.path("height").asInt(0));
        if (!next.equals(screen)) {
            clearGop();
            if (!next.format().equals(screen.format())) {
                lastFrame = null; // 다른 형식의 마지막 장은 새 뷰어가 풀 수 없다
            }
            screen = next;
        }
        ObjectNode config = object("type", "config");
        config.put("width", next.width());
        config.put("height", next.height());
        config.put("url", truncate(node.path("url").asText(""), 2048));
        config.put("title", truncate(node.path("title").asText(""), 200));
        config.put("format", next.format());
        if (next.codec() != null) {
            config.put("codec", next.codec());
        }
        config.put("kind", kind.name());
        config.put("pin", pinSupported());
        ArrayNode caps = config.putArray("pinCaps");
        if (pinSupported()) {
            PIN_CAPS.stream().filter(deviceFeatures::contains).forEach(caps::add);
        }
        configMessage = config.toString();
        broadcast(configMessage);
    }

    private void onScreenFrame(ShareFrames.Frame frame) {
        if (!deviceReady || frame.payload().length == 0 || frame.payload().length > maxFrameBytes) {
            return;
        }
        if (screen.h264()) {
            onVideoFrame(frame.payload());
            return;
        }
        lastFrame = frame.payload();
        for (Viewer viewer : viewers.values()) {
            viewer.channel().sendFrame(lastFrame);
        }
    }

    /** {@code [flags][Annex B]} 를 그대로 나눠 주고 GOP 에 담는다. 키 프레임을 기다리는 사람이 있으면 요청한다. */
    private void onVideoFrame(byte[] chunk) {
        if (chunk.length < 2) {
            return; // flags 만 있고 영상이 없다
        }
        boolean keyFrame = isKeyFrame(chunk);
        keepInGop(chunk, keyFrame);
        boolean someoneWaiting = false;
        for (Viewer viewer : viewers.values()) {
            someoneWaiting |= viewer.channel().sendVideo(chunk, keyFrame);
        }
        if (someoneWaiting) {
            requestKeyFrame();
        }
    }

    private void keepInGop(byte[] chunk, boolean keyFrame) {
        if (keyFrame) {
            clearGop();
        } else if (gop.isEmpty()) {
            return; // 키 프레임 없이는 풀 수 없는 장이라 담아 둘 까닭이 없다
        }
        gop.addLast(chunk);
        gopBytes += chunk.length;
        if (gopBytes > MAX_GOP_BYTES) {
            clearGop(); // 늦게 온 사람은 키 프레임을 새로 요청해 받는다
        }
    }

    private void clearGop() {
        gop.clear();
        gopBytes = 0;
    }

    /** 공유마다 1초에 한 번까지. 기다리는 사람은 장마다 다시 부르므로, 걸러진 요청도 1초가 지나면 다음 장에서 다시 간다. */
    private void requestKeyFrame() {
        if (device == null || !deviceReady) {
            return;
        }
        long now = nanoClock.getAsLong();
        if (keyframeRequested && now - lastKeyframeRequestAt < KEYFRAME_REQUEST_INTERVAL_NANOS) {
            return;
        }
        keyframeRequested = true;
        lastKeyframeRequestAt = now;
        device.sendBinary(ShareFrames.encode(ShareFrames.KEYFRAME_REQUEST, "{}"));
    }

    private static boolean isKeyFrame(byte[] chunk) {
        return (chunk[0] & 0x01) != 0;
    }

    private void onControlDecision(ShareFrames.Frame frame) {
        JsonNode node = parse(frame.payloadText());
        if (node == null) {
            return;
        }
        String viewerId = node.path("viewerId").asText("");
        if (!pendingRequests.remove(viewerId)) {
            return;
        }
        Viewer viewer = viewers.get(viewerId);
        if (viewer == null) {
            return;
        }
        if (node.path("granted").asBoolean(false)) {
            int ttl = Math.max(1, Math.min(node.path("ttlSeconds").asInt(600), 3600));
            grant(viewer, ttl);
        } else {
            viewer.channel().sendText(object("type", "control").put("state", "denied").toString());
        }
    }

    // ------------------------------------------------------------------ 보는 사람

    /** @return 새 보는 사람 id, 자리가 없으면 null */
    synchronized String join(ViewerChannel channel, String requestedName, boolean owner) {
        if (ended) {
            return null;
        }
        if (viewers.size() >= maxViewers) {
            return null;
        }
        String id = "v" + (++viewerSeq);
        String name = owner ? OWNER_NAME : cleanName(requestedName, viewerSeq);
        Viewer viewer = new Viewer(id, name, owner, channel);
        viewers.put(id, viewer);
        channel.sendText(object("type", "welcome").put("viewerId", id).put("name", name)
                .put("hasControl", false).put("owner", owner).toString());
        if (configMessage != null) {
            channel.sendText(configMessage);
        }
        channel.sendText(object("type", "device").put("state", deviceReady ? "online" : "offline").toString());
        if (owner && ownerMayTakeControl()) {
            grant(viewer, 0);
        } else {
            broadcastPresence();
        }
        if (deviceReady && screen.h264()) {
            replayGop(channel);
        } else if (deviceReady && lastFrame != null) {
            channel.sendFrame(lastFrame);
        }
        return id;
    }

    /** 늦게 온 사람이 지금 화면부터 풀도록 GOP 를 순서대로 준다. 비었으면(또는 다 담지 못하면) 키 프레임을 요청한다. */
    private void replayGop(ViewerChannel channel) {
        boolean waiting = true;
        for (byte[] chunk : gop) {
            waiting = channel.sendVideo(chunk, isKeyFrame(chunk));
        }
        if (waiting) {
            requestKeyFrame();
        }
    }

    synchronized void leave(String viewerId) {
        Viewer removed = viewers.remove(viewerId);
        if (removed == null) {
            return;
        }
        pendingRequests.remove(viewerId);
        if (viewerId.equals(controllerId)) {
            controllerId = null;
            controlUntil = null;
        }
        probes.values().removeIf(probe -> probe.viewerId().equals(viewerId));
        pins.remove(viewerId);
        lastProbeAt.remove(viewerId);
        watching.remove(viewerId);
        broadcastPresence();
    }

    synchronized void onViewerMessage(String viewerId, JsonNode message, Instant now) {
        Viewer viewer = viewers.get(viewerId);
        if (viewer == null || ended) {
            return;
        }
        switch (message.path("type").asText("")) {
            case "input" -> onInput(viewer, message.path("input"), now);
            case "request-control" -> onRequestControl(viewer);
            case "pin-probe" -> onPinProbe(viewer, message);
            case "pin-keep" -> keepPins(viewer.id());
            case "release-control" -> {
                if (viewerId.equals(controllerId)) {
                    controllerId = null;
                    controlUntil = null;
                    viewer.channel().sendText(object("type", "control").put("state", "released").toString());
                    broadcastPresence();
                }
            }
            default -> viewer.channel().sendText(object("type", "error").put("message", "알 수 없는 메시지입니다.").toString());
        }
    }

    private void onInput(Viewer viewer, JsonNode input, Instant now) {
        if (!viewer.id().equals(controllerId)) {
            return; // 조작권 없는 입력은 버린다
        }
        if (controlUntil != null && !now.isBefore(controlUntil)) {
            revokeControl("revoked");
            return;
        }
        if (device == null || !deviceReady || !input.isObject() || !INPUT_TYPES.contains(input.path("type").asText(""))) {
            return;
        }
        long second = now.getEpochSecond();
        if (second != inputWindowSecond) {
            inputWindowSecond = second;
            inputsInWindow = 0;
        }
        if (++inputsInWindow > maxInputsPerSecond) {
            return;
        }
        device.sendBinary(ShareFrames.encode(ShareFrames.INPUT, input.toString()));
    }

    private void onRequestControl(Viewer viewer) {
        if (viewer.id().equals(controllerId)) {
            return;
        }
        if (viewer.owner()) {
            grant(viewer, 0);
            return;
        }
        if (device == null || !deviceReady) {
            viewer.channel().sendText(object("type", "error").put("message", "기기가 연결되어 있지 않아 조작을 요청할 수 없습니다.").toString());
            return;
        }
        pendingRequests.add(viewer.id());
        device.sendBinary(ShareFrames.encode(ShareFrames.CONTROL_REQUEST,
                object("viewerId", viewer.id()).put("name", viewer.name()).toString()));
        viewer.channel().sendText(object("type", "control").put("state", "pending").toString());
    }

    /**
     * 주인은 들어오자마자 조작권을 받는다. 다만 주인이 승인한 손님이 조작 중이면 그대로 둔다 — 주인 탭이 다시
     * 연결될 때마다 손님의 조작권을 빼앗지 않도록. 주인은 조작을 요청하면 바로 되찾는다(설계 §5).
     */
    private boolean ownerMayTakeControl() {
        Viewer controller = controllerId == null ? null : viewers.get(controllerId);
        return controller == null || controller.owner();
    }

    /** @param ttlSeconds 0 이면 시간 제한 없음(주인) */
    private void grant(Viewer viewer, int ttlSeconds) {
        if (controllerId != null && !controllerId.equals(viewer.id())) {
            Viewer previous = viewers.get(controllerId);
            if (previous != null) {
                previous.channel().sendText(object("type", "control").put("state", "revoked").toString());
            }
        }
        controllerId = viewer.id();
        controlUntil = ttlSeconds > 0 ? Instant.now().plusSeconds(ttlSeconds) : null;
        ObjectNode granted = object("type", "control").put("state", "granted");
        if (ttlSeconds > 0) {
            granted.put("ttlSeconds", ttlSeconds);
        }
        viewer.channel().sendText(granted.toString());
        broadcastPresence();
    }

    synchronized void expireControlIfDue(Instant now) {
        if (controllerId != null && controlUntil != null && !now.isBefore(controlUntil)) {
            revokeControl("revoked");
        }
    }

    private void revokeControl(String state) {
        Viewer controller = controllerId == null ? null : viewers.get(controllerId);
        controllerId = null;
        controlUntil = null;
        if (controller != null) {
            controller.channel().sendText(object("type", "control").put("state", state).toString());
        }
        broadcastPresence();
    }

    // ------------------------------------------------------------------ 짚기 (설계 §14)

    private void readFeatures(ShareFrames.Frame frame) {
        deviceFeatures.clear();
        JsonNode node = parse(frame.payloadText());
        if (node != null && node.path("features").isArray()) {
            for (JsonNode feature : node.path("features")) {
                if (feature.isTextual() && deviceFeatures.size() < 16) {
                    deviceFeatures.add(truncate(feature.asText(), 32));
                }
            }
        }
    }

    private boolean pinSupported() {
        return deviceFeatures.contains(PIN_FEATURE);
    }

    /**
     * 짚기는 입력이 아니다. 조작권 없이도 되고, 기기는 읽기만 한다.
     *
     * <p>짚기 모양은 점({@code x}·{@code y})과 화면 전체({@code screen: true}, §15.6)다. 기기가 알리지 않은 모양은 기기로 보내지
     * 않는다(§15.9). 옛 CLI 는 모르는 모양을 말없이 버려, 5초 timeout 까지 대기 자리를 잡아먹는다.</p>
     */
    private void onPinProbe(Viewer viewer, JsonNode message) {
        String ref = message.path("ref").isTextual() && REF.matcher(message.path("ref").asText()).matches()
                ? message.path("ref").asText() : null;
        boolean wholeScreen = message.path("screen").asBoolean(false);
        boolean region = message.has("rect");
        ObjectNode rect = region ? probeRect(message.path("rect")) : null;
        JsonNode x = message.path("x");
        JsonNode y = message.path("y");
        if ((region && rect == null) || (!wholeScreen && !region
                && (!x.isNumber() || !y.isNumber() || x.asDouble() < 0 || x.asDouble() > 1 || y.asDouble() < 0 || y.asDouble() > 1))) {
            viewer.channel().sendText(object("type", "error").put("message", "짚은 위치가 화면 밖입니다.").toString());
            return;
        }
        if (device == null || !deviceReady) {
            viewer.channel().sendText(pinFailed(null, "offline", ref));
            return;
        }
        if (!pinSupported() || (region && !deviceFeatures.contains("pin-region"))
                || (wholeScreen && !deviceFeatures.contains("pin-screen"))) {
            viewer.channel().sendText(pinFailed(null, "unsupported", ref));
            return;
        }
        long now = nanoClock.getAsLong();
        Long last = lastProbeAt.get(viewer.id());
        if ((last != null && now - last < PROBE_INTERVAL_NANOS) || probes.size() >= MAX_PENDING_PROBES) {
            viewer.channel().sendText(pinFailed(null, "busy", ref));
            return;
        }
        lastProbeAt.put(viewer.id(), now);
        keepPins(viewer.id()); // 다시 짚는 사람은 아직 글을 쓰는 중이다(§15.9)
        String probeId = "p" + (++probeSeq);
        probes.put(probeId, new PendingProbe(probeId, viewer.id(), ref, now));
        ObjectNode probe = object("probeId", probeId);
        if (wholeScreen) {
            probe.put("screen", true);
        } else if (region) {
            probe.set("rect", rect);
        } else {
            probe.put("x", x.asDouble()).put("y", y.asDouble());
        }
        device.sendBinary(ShareFrames.encode(ShareFrames.PIN_PROBE, probe.toString()));
        ObjectNode pending = object("type", "pin").put("probeId", probeId).put("state", "pending");
        if (ref != null) {
            pending.put("ref", ref);
        }
        viewer.channel().sendText(pending.toString());
    }

    /**
     * 이 사람의 결과 시간을 늘린다(§15.9). 다시 짚거나 시트에 글을 쓰는 동안({@code pin-keep}) 5분씩, 받은 때부터 15분까지.
     */
    private void keepPins(String viewerId) {
        ViewerPins mine = pins.get(viewerId);
        if (mine == null) {
            return;
        }
        long now = nanoClock.getAsLong();
        mine.activeAt = now;
        for (ResolvedPin pin : mine.results.values()) {
            pin.expiresAt = Math.max(pin.expiresAt, Math.min(pin.resolvedAt + RESOLVED_MAX_NANOS, now + RESOLVED_TTL_NANOS));
        }
    }

    /**
     * 끌기 짚기의 사각형(§15.4). 화면 비율이고 화면 안이어야 한다. 너무 작은 것(변이 화면의 0.5% 미만)은 탭과 다르지 않아 받지
     * 않는다 — 뷰어는 10px 넘게 움직였을 때만 끌기로 본다.
     */
    private ObjectNode probeRect(JsonNode node) {
        if (!node.path("x").isNumber() || !node.path("y").isNumber() || !node.path("w").isNumber() || !node.path("h").isNumber()) {
            return null;
        }
        double x = node.path("x").asDouble();
        double y = node.path("y").asDouble();
        double w = node.path("w").asDouble();
        double h = node.path("h").asDouble();
        double eps = 1e-6;
        if (x < 0 || y < 0 || w < 0.005 || h < 0.005 || x + w > 1 + eps || y + h > 1 + eps
                || !Double.isFinite(x + y + w + h)) {
            return null;
        }
        return json.createObjectNode().put("x", x).put("y", y).put("w", Math.min(w, 1 - x)).put("h", Math.min(h, 1 - y));
    }

    /**
     * {@code [JSON 길이 u32][JSON][잘라 낸 이미지 길이 u32][잘라 낸 JPEG][화면 JPEG]} (§14.3). 세션을 잠그지 않고 읽는다.
     *
     * @return 버릴 결과면 null
     */
    private PreparedPin preparePinResult(ShareFrames.Frame frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame.payload());
        if (buffer.remaining() < 4) {
            return null;
        }
        long jsonLength = Integer.toUnsignedLong(buffer.getInt());
        if (jsonLength > MAX_RESULT_JSON_BYTES || jsonLength > buffer.remaining()) {
            log.debug("[DeviceShare] 짚기 결과 JSON 이 너무 크거나 잘림 share={}", shareId);
            return null;
        }
        byte[] jsonBytes = new byte[(int) jsonLength];
        buffer.get(jsonBytes);
        byte[] crop = null;
        byte[] screenImage = null;
        if (buffer.remaining() >= 4) {
            long cropLength = Integer.toUnsignedLong(buffer.getInt());
            if (cropLength <= buffer.remaining()) {
                crop = new byte[(int) cropLength];
                buffer.get(crop);
                screenImage = new byte[buffer.remaining()];
                buffer.get(screenImage);
            }
        }
        Optional<PinResolution> resolution = pinSanitizer.sanitize(parse(new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8)));
        if (resolution.isEmpty()) {
            return null;
        }
        byte[] screenJpeg = jpegOrNull(screenImage, MAX_SCREEN_BYTES);
        // 다섯 곳을 짚어 두는 동안 메모리를 덜 쓰게, 큰 그림은 줄여 둔다
        byte[] keptCrop = PinImages.shrink(jpegOrNull(crop, MAX_CROP_BYTES), MAX_KEPT_CROP_BYTES);
        return new PreparedPin(resolution.get(), keptCrop, screenJpeg, view(resolution.get(), screenJpeg));
    }

    /** 화면 지문. 화면 이름도 화면 그림도 없으면 null */
    private PinView view(PinResolution resolution, byte[] screenJpeg) {
        java.awt.image.BufferedImage image = PinImages.read(screenJpeg);
        JsonNode screenNode = resolution.description().path("screen");
        if (image == null && screenNode.isEmpty()) {
            return null;
        }
        StringBuilder key = new StringBuilder(kind.name());
        for (String field : new String[] {"label", "url", "package", "activity", "bundleId"}) {
            key.append('|').append(screenNode.path(field).asText(""));
        }
        if (kind == DeviceShareKind.IOS) {
            key.append('|').append(screenNode.path("title").asText("")); // iOS 는 번들 ID 만으로는 화면을 가르지 못한다
        }
        if (image != null) {
            key.append('|').append(image.getWidth() > image.getHeight() ? "landscape" : "portrait");
        }
        String screenKey = ShareTokens.sha256Hex(key.toString()).substring(0, 12);
        java.util.OptionalLong hash = PinImages.dhash(image);
        return new PinView(screenKey, hash.isPresent() ? hash.getAsLong() : null);
    }

    private synchronized void applyPinResult(DeviceChannel channel, PreparedPin prepared) {
        if (device != channel || ended) {
            return;
        }
        PinResolution r = prepared.resolution();
        PendingProbe probe = probes.remove(r.probeId());
        if (probe == null) {
            return; // 시간이 지나 이미 실패로 알렸거나 모르는 짚기다
        }
        Viewer viewer = viewers.get(probe.viewerId());
        if (viewer == null) {
            return;
        }
        long now = nanoClock.getAsLong();
        ViewerPins mine = pins.computeIfAbsent(viewer.id(), id -> new ViewerPins());
        if (probe.ref() != null) {
            // 같은 곳을 다시 짚었다. 옛 결과는 이 결과가 대신한다
            mine.results.values().removeIf(old -> probe.ref().equals(old.ref));
        }
        if (prepared.screen() != null) {
            // 같은 화면이면 화면 그림은 이 결과에만 둔다. 앞 결과는 보낼 때 이것을 함께 쓴다
            for (ResolvedPin old : mine.results.values()) {
                if (old.screen != null && prepared.view() != null && prepared.view().sameAs(old.view)) {
                    old.screen = null;
                }
            }
        }
        mine.results.put(r.probeId(), new ResolvedPin(r, probe.ref(), prepared.crop(), prepared.screen(), prepared.view(), now));
        mine.activeAt = now;
        while (mine.results.size() > MAX_RESOLVED_PER_VIEWER) {
            removeOldest(mine);
        }
        trimShare(viewer.id());

        ObjectNode message = object("type", "pin").put("probeId", r.probeId()).put("state", "resolved");
        if (probe.ref() != null) {
            message.put("ref", probe.ref());
        }
        if (r.bounds() == null) {
            message.putNull("bounds");
        } else {
            message.set("bounds", r.bounds());
        }
        message.put("label", r.label());
        message.put("component", r.component());
        if (r.reason() != null) {
            message.put("reason", r.reason());
        }
        // §15.9: 범위 사다리. 칸 이름은 서버가 만들고, 손님에게는 바깥 칸의 컴포넌트 이름을 넣지 않는다(§15.2)
        JsonNode scopes = r.description().path("scopes");
        if (scopes.isArray() && !scopes.isEmpty()) {
            ArrayNode out = message.putArray("scopes");
            for (JsonNode scope : scopes) {
                boolean element = "element".equals(scope.path("kind").asText());
                out.addObject()
                        .put("kind", scope.path("kind").asText())
                        .put("label", PinResultSanitizer.scopeLabel(scope, viewer.owner() || element))
                        .set("bounds", scope.path("bounds"));
            }
            message.put("defaultScope", r.description().path("defaultScope").asInt(0));
        }
        // §15.4: 끈 영역 안의 요소 수와 빈 자리의 둘레. 둘레 이름에는 컴포넌트 이름이 없어 손님에게도 간다
        if (r.description().has("empty")) {
            // 개수는 영역 칸의 것이 먼저다. 목록은 크기 때문에 덜어졌을 수 있다
            JsonNode count = r.description().path("scopes").path(0).path("members");
            message.put("members", count.isInt() ? count.asInt() : r.description().path("members").size());
            message.put("empty", r.description().path("empty").asBoolean());
        }
        JsonNode near = r.description().path("near");
        if (near.isArray() && !near.isEmpty()) {
            ArrayNode out = message.putArray("near");
            for (JsonNode item : near) {
                out.addObject().put("dir", item.path("dir").asText()).put("label", PinResultSanitizer.nearLabel(item));
            }
        }
        // §15.5: 여러 곳을 짚는 동안 화면이 바뀌었는지 뷰어가 안다
        PinView view = prepared.view();
        if (view != null) {
            ObjectNode v = message.putObject("view").put("screen", view.screen());
            if (view.dhash() != null) {
                v.put("dhash", String.format("%016x", view.dhash()));
            }
        }
        viewer.channel().sendText(message.toString());
    }

    private static void removeOldest(ViewerPins mine) {
        Iterator<String> oldest = mine.results.keySet().iterator();
        oldest.next();
        oldest.remove();
    }

    /** 공유 전체 상한. 넘으면 가장 오래 쉬고 있는 사람의 가장 오래된 결과부터 버린다(방금 짚은 사람은 마지막에) */
    private void trimShare(String currentViewerId) {
        int total = pins.values().stream().mapToInt(mine -> mine.results.size()).sum();
        ViewerPins current = pins.get(currentViewerId);
        while (total > MAX_RESOLVED_PINS) {
            ViewerPins victim = null;
            for (ViewerPins mine : pins.values()) {
                if (mine != current && !mine.results.isEmpty() && (victim == null || mine.activeAt < victim.activeAt)) {
                    victim = mine;
                }
            }
            if (victim == null) {
                victim = current;
            }
            if (victim == null || victim.results.isEmpty()) {
                return;
            }
            removeOldest(victim);
            total--;
        }
        pins.values().removeIf(mine -> mine.results.isEmpty());
    }

    /** JPEG 시작 표시(FF D8 FF)가 있고 상한 안이어야 둔다. 이미지로 내보낼 바이트라서다. */
    private static byte[] jpegOrNull(byte[] bytes, int max) {
        if (bytes == null || bytes.length < 3 || bytes.length > max) {
            return null;
        }
        return (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff ? bytes : null;
    }

    /** 보는 사람이 글을 붙여 보내려고 짚기 결과 하나를 가져간다(§14). */
    synchronized Optional<ClaimedPin> claimPin(String viewerId, String probeId) {
        return claimPins(viewerId, List.of(probeId)).claimed();
    }

    /**
     * 여러 곳의 짚기 결과를 한꺼번에 가져간다(§15.9). 그 사람의 것이고 시간 안이어야 한다. 하나라도 없으면 아무것도 가져가지 않는다.
     *
     * @param probeIds ①②… 순서
     */
    synchronized PinClaim claimPins(String viewerId, List<String> probeIds) {
        if (probeIds.isEmpty()) {
            return PinClaim.missing(List.of());
        }
        Viewer viewer = viewers.get(viewerId);
        ViewerPins mine = pins.get(viewerId);
        long now = nanoClock.getAsLong();
        List<String> missing = new ArrayList<>();
        List<ResolvedPin> found = new ArrayList<>();
        for (String probeId : probeIds) {
            ResolvedPin pin = mine == null ? null : mine.results.get(probeId);
            if (viewer == null || pin == null || now > pin.expiresAt || found.contains(pin)) {
                missing.add(probeId);
            } else {
                found.add(pin);
            }
        }
        if (!missing.isEmpty()) {
            return PinClaim.missing(missing);
        }
        List<ClaimedPin.Mark> marks = new ArrayList<>();
        ResolvedPin newest = null;
        for (ResolvedPin pin : found) {
            marks.add(new ClaimedPin.Mark(pin.resolution, pin.crop, screenOf(mine, pin)));
            if (newest == null || pin.resolvedAt > newest.resolvedAt) {
                newest = pin;
            }
        }
        byte[] screen = newest == null ? null : screenOf(mine, newest);
        for (String probeId : probeIds) {
            mine.results.remove(probeId);
        }
        if (mine.results.isEmpty()) {
            pins.remove(viewerId);
        }
        return PinClaim.of(new ClaimedPin(viewer.name(), viewer.owner(), marks, screen));
    }

    /** 이 결과의 화면 그림. 같은 화면의 더 나중 결과에 넘겼으면 그것을 쓴다 */
    private static byte[] screenOf(ViewerPins mine, ResolvedPin pin) {
        if (pin.screen != null) {
            return pin.screen;
        }
        List<ResolvedPin> all = new ArrayList<>(mine.results.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            ResolvedPin other = all.get(i);
            if (other.screen != null && other.view != null && other.view.sameAs(pin.view)) {
                return other.screen;
            }
        }
        return null;
    }

    /** @return 보는 사람이 있으면 {@code [이름, 주인 여부]} */
    synchronized Optional<ViewerIdentity> viewer(String viewerId) {
        Viewer viewer = viewers.get(viewerId);
        return viewer == null ? Optional.empty() : Optional.of(new ViewerIdentity(viewer.name(), viewer.owner()));
    }

    synchronized void sendToViewer(String viewerId, String text) {
        Viewer viewer = viewers.get(viewerId);
        if (viewer != null) {
            viewer.channel().sendText(text);
        }
    }

    /** 이 보는 사람에게 피드백 상태가 바뀔 때마다 알린다. */
    synchronized void watch(String viewerId, long feedbackId) {
        if (!viewers.containsKey(viewerId)) {
            return;
        }
        Set<Long> ids = watching.computeIfAbsent(viewerId, id -> new LinkedHashSet<>());
        if (ids.size() < MAX_WATCHED) {
            ids.add(feedbackId);
        }
    }

    synchronized void notifyWatchers(long feedbackId, String text) {
        watching.forEach((viewerId, ids) -> {
            if (ids.contains(feedbackId)) {
                sendToViewer(viewerId, text);
            }
        });
    }

    /** 짚기를 아는 기기에만 보낸다. @return 보냈는지 */
    synchronized boolean sendToPinDevice(int kind, String text) {
        if (device == null || !deviceReady || !pinSupported()) {
            return false;
        }
        device.sendBinary(ShareFrames.encode(kind, text));
        return true;
    }

    /** 5초 안에 답이 없는 짚기는 실패로 알리고, 5분 지난 결과는 버린다. */
    synchronized void expirePins() {
        long now = nanoClock.getAsLong();
        Iterator<PendingProbe> pending = probes.values().iterator();
        while (pending.hasNext()) {
            PendingProbe probe = pending.next();
            if (now - probe.sentAt() > PROBE_TIMEOUT_NANOS) {
                pending.remove();
                sendToViewer(probe.viewerId(), pinFailed(probe.probeId(), "timeout", probe.ref()));
            }
        }
        for (ViewerPins mine : pins.values()) {
            mine.results.values().removeIf(pin -> now > pin.expiresAt);
        }
        pins.values().removeIf(mine -> mine.results.isEmpty());
    }

    private void failPendingProbes(String reason) {
        for (PendingProbe probe : probes.values()) {
            sendToViewer(probe.viewerId(), pinFailed(probe.probeId(), reason, probe.ref()));
        }
        probes.clear();
    }

    private String pinFailed(String probeId, String reason, String ref) {
        ObjectNode failed = json.createObjectNode().put("type", "pin");
        if (probeId == null) {
            failed.putNull("probeId");
        } else {
            failed.put("probeId", probeId);
        }
        if (ref != null) {
            failed.put("ref", ref);
        }
        return failed.put("state", "failed").put("reason", reason).toString();
    }

    /** 보는 사람 이름과 주인 여부. */
    public record ViewerIdentity(String name, boolean owner) {
    }

    // ------------------------------------------------------------------ 끝내기

    synchronized void end(String reason) {
        if (ended) {
            return;
        }
        ended = true;
        String endedMessage = object("type", "ended").put("reason", reason).toString();
        for (Viewer viewer : viewers.values()) {
            viewer.channel().sendText(endedMessage);
            viewer.channel().close(reason);
        }
        viewers.clear();
        probes.clear();
        pins.clear();
        lastProbeAt.clear();
        watching.clear();
        if (device != null) {
            device.sendBinary(ShareFrames.encode(ShareFrames.GOODBYE, object("reason", reason).toString()));
            device.close(reason);
            device = null;
        }
    }

    // ------------------------------------------------------------------ 도우미

    private void broadcastPresence() {
        ObjectNode presence = object("type", "presence").put("viewers", viewers.size());
        Viewer controller = controllerId == null ? null : viewers.get(controllerId);
        if (controller == null) {
            presence.putNull("controller");
        } else {
            presence.putObject("controller").put("viewerId", controller.id()).put("name", controller.name());
        }
        broadcast(presence.toString());
        sendPresenceToDevice();
    }

    private void sendPresenceToDevice() {
        if (device == null) {
            return;
        }
        Viewer controller = controllerId == null ? null : viewers.get(controllerId);
        ObjectNode presence = json.createObjectNode().put("viewers", viewers.size());
        if (controller == null) {
            presence.putNull("controller");
        } else {
            presence.putObject("controller").put("viewerId", controller.id()).put("name", controller.name());
        }
        device.sendBinary(ShareFrames.encode(ShareFrames.PRESENCE, presence.toString()));
    }

    private void broadcast(String message) {
        for (Viewer viewer : viewers.values()) {
            viewer.channel().sendText(message);
        }
    }

    private ObjectNode object(String key, String value) {
        return json.createObjectNode().put(key, value);
    }

    private ObjectNode object(String key, long value) {
        return json.createObjectNode().put(key, value);
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    static String cleanName(String requested, int seq) {
        String name = requested == null ? "" : requested.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || OWNER_NAME.equals(name)) {
            return "보는 사람 " + seq;
        }
        return name.codePointCount(0, name.length()) > 20
                ? name.substring(0, name.offsetByCodePoints(0, 20))
                : name;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
