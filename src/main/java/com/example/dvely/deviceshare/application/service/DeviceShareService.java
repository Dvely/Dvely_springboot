package com.example.dvely.deviceshare.application.service;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.response.ErrorCode;
import com.example.dvely.deviceshare.application.session.ShareSessionRegistry;
import com.example.dvely.deviceshare.domain.model.DeviceShareLink;
import com.example.dvely.deviceshare.domain.repository.DeviceShareLinkRepository;
import com.example.dvely.deviceshare.domain.service.ShareTokens;
import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import com.example.dvely.deviceshare.infrastructure.config.DeviceShareProperties;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 공유 링크 만들기·목록·회수와 토큰 확인(#652). 연결된 세션은 {@link ShareSessionRegistry} 가 맡는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceShareService {

    static final int LIST_LIMIT = 50;
    private static final int TARGET_LABEL_MAX = 255;

    private final DeviceShareLinkRepository repository;
    private final DeviceShareProperties properties;
    private final ShareSessionRegistry sessions;

    public CreatedDeviceShare create(Long userId, DeviceShareKind kind, String targetLabel, Integer ttlMinutes) {
        if (kind == null) {
            throw new IllegalArgumentException("공유할 화면 종류(kind)가 필요합니다.");
        }
        if (kind != DeviceShareKind.WEB) {
            throw new IllegalArgumentException("지금은 웹 화면(WEB) 공유만 지원합니다.");
        }
        String label = targetLabel == null ? "" : targetLabel.strip();
        if (label.isEmpty() || label.length() > TARGET_LABEL_MAX) {
            throw new IllegalArgumentException("공유 대상(targetLabel)은 1~" + TARGET_LABEL_MAX + "자여야 합니다.");
        }
        int ttl = ttlMinutes == null ? properties.defaultTtlMinutes() : ttlMinutes;
        if (ttl < 1 || ttl > properties.maxTtlMinutes()) {
            throw new IllegalArgumentException("공유 시간(ttlMinutes)은 1~" + properties.maxTtlMinutes() + "분이어야 합니다.");
        }
        LocalDateTime now = now();
        long active = repository.countActiveByUser(userId, now);
        if (active >= properties.maxActivePerUser()) {
            throw new UserLimitExceededException(ErrorCode.DEVICE_SHARE_LIMIT_EXCEEDED,
                    "살아 있는 공유 링크가 " + active + "개라 더 만들 수 없습니다(최대 " + properties.maxActivePerUser()
                            + "개). 쓰지 않는 공유를 끝낸 뒤 다시 시도해 주세요.");
        }
        String token = ShareTokens.newViewToken();
        String secret = ShareTokens.newControlSecret();
        DeviceShareLink saved = repository.save(DeviceShareLink.newLink(userId, kind, label,
                ShareTokens.sha256Hex(token), ShareTokens.sha256Hex(secret), token.substring(0, 6),
                now.plusMinutes(ttl)));
        log.info("[DeviceShare] 링크 생성 share={} user={} kind={} ttl={}m", saved.getId(), userId, kind, ttl);
        return new CreatedDeviceShare(saved, token, secret);
    }

    public List<DeviceShareLink> list(Long userId) {
        return repository.findRecentByUser(userId, LIST_LIMIT);
    }

    public void revoke(Long userId, Long shareId) {
        DeviceShareLink link = owned(userId, shareId);
        if (repository.markRevoked(link.getId(), now())) {
            log.info("[DeviceShare] 링크 회수 share={} user={}", shareId, userId);
        }
        sessions.end(link.getId(), "revoked");
    }

    /** 기기 터널을 열 수 있는 링크. 주인이 아니면 404, 끝난 링크면 410 으로 보이게 한다. */
    public DeviceShareLink requireTunnelAccess(Long userId, Long shareId) {
        DeviceShareLink link = owned(userId, shareId);
        if (!link.isActive(now())) {
            throw new ShareEndedException(link.status(now()));
        }
        return link;
    }

    /** 보기 토큰(하위 도메인 라벨)으로 링크를 찾는다. 형식이 틀리면 빈 값. */
    public Optional<DeviceShareLink> findByViewToken(String token) {
        if (!ShareTokens.looksLikeViewToken(token)) {
            return Optional.empty();
        }
        return repository.findByTokenHash(ShareTokens.sha256Hex(token));
    }

    public boolean isOwnerSecret(DeviceShareLink link, String secret) {
        return ShareTokens.matches(secret, link.getControlSecretHash());
    }

    public void touchViewed(Long shareId) {
        repository.touchViewed(shareId, now());
    }

    public LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private DeviceShareLink owned(Long userId, Long shareId) {
        return repository.findById(shareId)
                .filter(link -> link.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("공유를 찾을 수 없습니다."));
    }
}
