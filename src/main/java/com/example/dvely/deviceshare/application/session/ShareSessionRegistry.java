package com.example.dvely.deviceshare.application.session;

import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.example.dvely.deviceshare.infrastructure.config.DeviceShareProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 연결된 공유 세션들. 서버 한 대 전제라 메모리에만 둔다(설계 §6.1).
 *
 * <p>15초마다 만료된 링크를 끝내고, 시간이 다 된 조작권을 거둔다. 회수는 {@link #end} 로 바로 끝낸다.</p>
 */
@Slf4j
@Component
public class ShareSessionRegistry {

    private final Map<Long, ShareSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final DeviceShareProperties properties;
    private final ScheduledExecutorService ticker;

    public ShareSessionRegistry(ObjectMapper json, DeviceShareProperties properties) {
        this.json = json;
        this.properties = properties;
        this.ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("device-share-ticker").daemon(true).factory());
        this.ticker.scheduleWithFixedDelay(this::tick, 15, 15, TimeUnit.SECONDS);
        this.ticker.scheduleWithFixedDelay(this::tickPins, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
        for (ShareSession session : List.copyOf(sessions.values())) {
            session.end("server-shutdown");
        }
        sessions.clear();
    }

    /** 기기 터널을 붙인다. 서버 전체 터널 상한을 넘으면 빈 값. */
    public synchronized Optional<ShareSession> attachDevice(DeviceShareLink link, DeviceChannel channel) {
        ShareSession session = sessionFor(link);
        if (!session.deviceConnected() && connectedTunnels() >= properties.maxTunnels()) {
            return Optional.empty();
        }
        if (session.attachDevice(channel)) {
            log.info("[DeviceShare] 기기 연결 대체 share={}", link.getId());
        } else {
            log.info("[DeviceShare] 기기 연결 share={}", link.getId());
        }
        return Optional.of(session);
    }

    public void detachDevice(long shareId, DeviceChannel channel) {
        ShareSession session = sessions.get(shareId);
        if (session != null) {
            session.detachDevice(channel);
            log.info("[DeviceShare] 기기 연결 끊김 share={}", shareId);
            dropIfIdle(session);
        }
    }

    public void onDeviceFrame(long shareId, DeviceChannel channel, ShareFrames.Frame frame) {
        ShareSession session = sessions.get(shareId);
        if (session != null) {
            session.onDeviceFrame(channel, frame);
        }
    }

    /** @return 보는 사람 id. 자리가 없으면 빈 값 */
    public Optional<String> join(DeviceShareLink link, ViewerChannel channel, String name, boolean owner) {
        ShareSession session = sessionFor(link);
        String viewerId = session.join(channel, name, owner);
        if (viewerId != null) {
            log.info("[DeviceShare] 보는 사람 참가 share={} viewer={} owner={} viewers={}",
                    link.getId(), viewerId, owner, session.viewerCount());
        }
        return Optional.ofNullable(viewerId);
    }

    public void leave(long shareId, String viewerId) {
        ShareSession session = sessions.get(shareId);
        if (session != null) {
            session.leave(viewerId);
            dropIfIdle(session);
        }
    }

    public void onViewerMessage(long shareId, String viewerId, JsonNode message) {
        ShareSession session = sessions.get(shareId);
        if (session != null) {
            session.onViewerMessage(viewerId, message, Instant.now());
        }
    }

    public void end(long shareId, String reason) {
        ShareSession session = sessions.remove(shareId);
        if (session != null) {
            session.end(reason);
            log.info("[DeviceShare] 공유 종료 share={} reason={}", shareId, reason);
        }
    }

    public boolean deviceConnected(long shareId) {
        ShareSession session = sessions.get(shareId);
        return session != null && session.deviceConnected();
    }

    public int viewerCount(long shareId) {
        ShareSession session = sessions.get(shareId);
        return session == null ? 0 : session.viewerCount();
    }

    // ------------------------------------------------------------------ 짚기 (설계 §14)

    /** 보는 사람이 글을 붙여 보내려고 짚기 결과를 가져간다. */
    /** 여러 곳의 짚기 결과를 한꺼번에(§15.9). 하나라도 없으면 아무것도 가져가지 않는다 */
    public PinClaim claimPins(long shareId, String viewerId, java.util.List<String> probeIds) {
        ShareSession session = sessions.get(shareId);
        return session == null ? PinClaim.missing(probeIds) : session.claimPins(viewerId, probeIds);
    }

    public Optional<ShareSession.ViewerIdentity> viewer(long shareId, String viewerId) {
        ShareSession session = sessions.get(shareId);
        return session == null ? Optional.empty() : session.viewer(viewerId);
    }

    public void sendToViewer(long shareId, String viewerId, String text) {
        ShareSession session = sessions.get(shareId);
        if (session != null) {
            session.sendToViewer(viewerId, text);
        }
    }

    public void watch(long shareId, String viewerId, long feedbackId) {
        ShareSession session = sessions.get(shareId);
        if (session != null) {
            session.watch(viewerId, feedbackId);
        }
    }

    /** 이 피드백을 지켜보는 보는 사람(남긴 사람)에게 알린다. 세션이 없으면 아무 일도 하지 않는다. */
    public void notifyWatchers(long shareId, long feedbackId, String text) {
        ShareSession session = sessions.get(shareId);
        if (session != null) {
            session.notifyWatchers(feedbackId, text);
        }
    }

    /** @return 짚기를 아는 기기가 연결돼 있어 보냈는지 */
    public boolean sendToPinDevice(long shareId, int kind, String text) {
        ShareSession session = sessions.get(shareId);
        return session != null && session.sendToPinDevice(kind, text);
    }

    void tickPins() {
        try {
            for (ShareSession session : List.copyOf(sessions.values())) {
                session.expirePins();
            }
        } catch (RuntimeException e) {
            log.warn("[DeviceShare] 짚기 만료 점검 실패: {}", e.getMessage());
        }
    }

    /** 지금 메모리에 있는 공유 id. 링크가 DB 에서 사라졌는지 점검할 때 쓴다. */
    public List<Long> activeShareIds() {
        return List.copyOf(sessions.keySet());
    }

    int connectedTunnels() {
        return (int) sessions.values().stream().filter(ShareSession::deviceConnected).count();
    }

    void tick() {
        try {
            LocalDateTime now = LocalDateTime.now();
            Instant instant = Instant.now();
            for (ShareSession session : List.copyOf(sessions.values())) {
                if (!now.isBefore(session.expiresAt())) {
                    end(session.shareId(), "expired");
                } else {
                    session.expireControlIfDue(instant);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[DeviceShare] 만료 점검 실패: {}", e.getMessage());
        }
    }

    private ShareSession sessionFor(DeviceShareLink link) {
        return sessions.compute(link.getId(), (id, existing) -> existing != null && !existing.isEnded()
                ? existing
                : new ShareSession(id, link.getKind(), link.getExpiresAt(), json, properties.maxViewersPerShare(),
                        properties.maxFrameBytes(), properties.maxInputsPerSecond(), System::nanoTime));
    }

    private void dropIfIdle(ShareSession session) {
        sessions.computeIfPresent(session.shareId(), (id, s) ->
                s == session && !s.deviceConnected() && s.viewerCount() == 0 ? null : s);
    }
}
