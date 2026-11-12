package com.example.dvely.deviceshare.infrastructure.web;

import com.example.dvely.deviceshare.domain.service.ShareTokens;
import com.example.dvely.deviceshare.infrastructure.config.DeviceShareProperties;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@code <토큰>.<base-domain>} 호스트 판별(설계 §3). */
@Component
public class ShareHost {

    private final String suffix;

    public ShareHost(DeviceShareProperties properties) {
        this.suffix = "." + properties.baseDomain();
    }

    /** 공유 기준 도메인 아래 호스트인지(토큰 형식과 상관없이). */
    public boolean isShareDomain(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    /** 공유 오리진이면 토큰 라벨. 형식이 틀리거나 공유 오리진이 아니면 빈 값. */
    public Optional<String> tokenOf(String host) {
        if (!isShareDomain(host)) {
            return Optional.empty();
        }
        String lower = host.toLowerCase(Locale.ROOT);
        String label = lower.substring(0, lower.length() - suffix.length());
        return ShareTokens.looksLikeViewToken(label) ? Optional.of(label) : Optional.empty();
    }
}
