package com.example.dvely.deviceshare.application.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 공유 링크 하나의 실시간 상태: 기기 연결, 보는 사람들, 조작권. 설계 §4·§5.
 *
 * <p>상태는 이 객체의 모니터로 지킨다. 채널 보내기는 막히지 않으므로 잠금 안에서 불러도 된다.</p>
 */
@Slf4j
public class ShareSession {

    private static final Set<String> INPUT_TYPES = Set.of("pointer", "wheel", "key", "text", "nav");
    private static final String OWNER_NAME = "주인";

    private final long shareId;
    private final LocalDateTime expiresAt;
    private final ObjectMapper json;
    private final int maxViewers;
    private final int maxFrameBytes;
    private final int maxInputsPerSecond;

    private DeviceChannel device;
    private boolean deviceReady;
    private String configMessage;
    private byte[] lastFrame;
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

    ShareSession(long shareId, LocalDateTime expiresAt, ObjectMapper json, int maxViewers, int maxFrameBytes,
                 int maxInputsPerSecond) {
        this.shareId = shareId;
        this.expiresAt = expiresAt;
        this.json = json;
        this.maxViewers = maxViewers;
        this.maxFrameBytes = maxFrameBytes;
        this.maxInputsPerSecond = maxInputsPerSecond;
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
        ObjectNode config = object("type", "config");
        config.put("width", node.path("width").asInt(0));
        config.put("height", node.path("height").asInt(0));
        config.put("url", truncate(node.path("url").asText(""), 2048));
        config.put("title", truncate(node.path("title").asText(""), 200));
        configMessage = config.toString();
        broadcast(configMessage);
    }

    private void onScreenFrame(ShareFrames.Frame frame) {
        if (!deviceReady || frame.payload().length == 0 || frame.payload().length > maxFrameBytes) {
            return;
        }
        lastFrame = frame.payload();
        for (Viewer viewer : viewers.values()) {
            viewer.channel().sendFrame(lastFrame);
        }
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
                .put("hasControl", false).toString());
        if (configMessage != null) {
            channel.sendText(configMessage);
        }
        channel.sendText(object("type", "device").put("state", deviceReady ? "online" : "offline").toString());
        if (owner) {
            grant(viewer, 0);
        } else {
            broadcastPresence();
        }
        if (deviceReady && lastFrame != null) {
            channel.sendFrame(lastFrame);
        }
        return id;
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
            presence.put("controller", controller.name());
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
