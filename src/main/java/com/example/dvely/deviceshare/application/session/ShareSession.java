package com.example.dvely.deviceshare.application.session;

import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
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

    private record Viewer(String id, String name, boolean owner, ViewerChannel channel) {
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
                ObjectNode welcome = object("shareId", shareId).put("expiresAt", expiresAt.toString());
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
