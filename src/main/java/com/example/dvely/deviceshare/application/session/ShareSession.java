package com.example.dvely.deviceshare.application.session;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
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
    static final int MAX_PENDING_PROBES = 3;
    static final int MAX_RESOLVED_PINS = 5;
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
    /** 보는 사람마다 마지막 짚기 결과 하나. 넣은 순서대로라 가장 오래된 것부터 버린다. */
    private final Map<String, ResolvedPin> resolvedPins = new LinkedHashMap<>();
    private final Map<String, Long> lastProbeAt = new HashMap<>();
    private final Map<String, Set<Long>> watching = new HashMap<>();
    private int probeSeq;

    private record Viewer(String id, String name, boolean owner, ViewerChannel channel) {
    }

    private record PendingProbe(String probeId, String viewerId, long sentAt) {
    }

    private record ResolvedPin(PinResolution resolution, byte[] crop, byte[] screen, long resolvedAt) {
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

    synchronized void onDeviceFrame(DeviceChannel channel, ShareFrames.Frame frame) {
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
            case ShareFrames.PIN_RESULT -> onPinResult(frame);
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
        resolvedPins.remove(viewerId);
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
        boolean wholeScreen = message.path("screen").asBoolean(false);
        boolean region = message.has("rect");
        JsonNode x = message.path("x");
        JsonNode y = message.path("y");
        if (!wholeScreen && !region
                && (!x.isNumber() || !y.isNumber() || x.asDouble() < 0 || x.asDouble() > 1 || y.asDouble() < 0 || y.asDouble() > 1)) {
            viewer.channel().sendText(object("type", "error").put("message", "짚은 위치가 화면 밖입니다.").toString());
            return;
        }
        if (device == null || !deviceReady) {
            viewer.channel().sendText(pinFailed(null, "offline"));
            return;
        }
        if (!pinSupported() || region || (wholeScreen && !deviceFeatures.contains("pin-screen"))) {
            // 끌기(영역)는 §15.13 PR-4 에서 연다
            viewer.channel().sendText(pinFailed(null, "unsupported"));
            return;
        }
        long now = nanoClock.getAsLong();
        Long last = lastProbeAt.get(viewer.id());
        if ((last != null && now - last < PROBE_INTERVAL_NANOS) || probes.size() >= MAX_PENDING_PROBES) {
            viewer.channel().sendText(pinFailed(null, "busy"));
            return;
        }
        lastProbeAt.put(viewer.id(), now);
        String probeId = "p" + (++probeSeq);
        probes.put(probeId, new PendingProbe(probeId, viewer.id(), now));
        ObjectNode probe = object("probeId", probeId);
        if (wholeScreen) {
            probe.put("screen", true);
        } else {
            probe.put("x", x.asDouble()).put("y", y.asDouble());
        }
        device.sendBinary(ShareFrames.encode(ShareFrames.PIN_PROBE, probe.toString()));
        viewer.channel().sendText(object("type", "pin").put("probeId", probeId).put("state", "pending").toString());
    }

    /** {@code [JSON 길이 u32][JSON][잘라 낸 이미지 길이 u32][잘라 낸 JPEG][화면 JPEG]} (§14.3). */
    private void onPinResult(ShareFrames.Frame frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame.payload());
        if (buffer.remaining() < 4) {
            return;
        }
        long jsonLength = Integer.toUnsignedLong(buffer.getInt());
        if (jsonLength > MAX_RESULT_JSON_BYTES || jsonLength > buffer.remaining()) {
            log.debug("[DeviceShare] 짚기 결과 JSON 이 너무 크거나 잘림 share={}", shareId);
            return;
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
            return;
        }
        PendingProbe probe = probes.remove(resolution.get().probeId());
        if (probe == null) {
            return; // 시간이 지나 이미 실패로 알렸거나 모르는 짚기다
        }
        Viewer viewer = viewers.get(probe.viewerId());
        if (viewer == null) {
            return;
        }
        resolvedPins.remove(viewer.id());
        resolvedPins.put(viewer.id(), new ResolvedPin(resolution.get(), jpegOrNull(crop, MAX_CROP_BYTES),
                jpegOrNull(screenImage, MAX_SCREEN_BYTES), nanoClock.getAsLong()));
        while (resolvedPins.size() > MAX_RESOLVED_PINS) {
            Iterator<String> oldest = resolvedPins.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        PinResolution r = resolution.get();
        ObjectNode message = object("type", "pin").put("probeId", r.probeId()).put("state", "resolved");
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
        viewer.channel().sendText(message.toString());
    }

    /** JPEG 시작 표시(FF D8 FF)가 있고 상한 안이어야 둔다. 이미지로 내보낼 바이트라서다. */
    private static byte[] jpegOrNull(byte[] bytes, int max) {
        if (bytes == null || bytes.length < 3 || bytes.length > max) {
            return null;
        }
        return (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff ? bytes : null;
    }

    /** 보는 사람이 글을 붙여 보내려고 짚기 결과를 가져간다. 그 사람의 것이고 5분 안이어야 한다. */
    synchronized Optional<ClaimedPin> claimPin(String viewerId, String probeId) {
        Viewer viewer = viewers.get(viewerId);
        ResolvedPin pin = resolvedPins.get(viewerId);
        if (viewer == null || pin == null || !pin.resolution().probeId().equals(probeId)
                || nanoClock.getAsLong() - pin.resolvedAt() > RESOLVED_TTL_NANOS) {
            return Optional.empty();
        }
        resolvedPins.remove(viewerId);
        return Optional.of(new ClaimedPin(viewer.name(), viewer.owner(), pin.resolution(), pin.crop(), pin.screen()));
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
                sendToViewer(probe.viewerId(), pinFailed(probe.probeId(), "timeout"));
            }
        }
        resolvedPins.values().removeIf(pin -> now - pin.resolvedAt() > RESOLVED_TTL_NANOS);
    }

    private void failPendingProbes(String reason) {
        for (PendingProbe probe : probes.values()) {
            sendToViewer(probe.viewerId(), pinFailed(probe.probeId(), reason));
        }
        probes.clear();
    }

    private String pinFailed(String probeId, String reason) {
        ObjectNode failed = json.createObjectNode().put("type", "pin");
        if (probeId == null) {
            failed.putNull("probeId");
        } else {
            failed.put("probeId", probeId);
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
        resolvedPins.clear();
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
