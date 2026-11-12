package com.example.dvely.deviceshare.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 내 기기 프리뷰 공유 설정({@code qeploy.device-share.*}). 설계: docs/device-preview-share-design.md
 *
 * @param baseDomain       공유 오리진의 기준 도메인. 링크마다 {@code <토큰>.<baseDomain>}
 * @param publicScheme     보기 링크의 스킴(로컬 http, 운영 https)
 * @param publicPort       보기 링크에 붙일 포트. 0 이면 붙이지 않는다
 * @param apiPublicUrl     기기 터널 주소를 만들 API 공개 주소(예: https://api.qeploy.com). 비우면 요청에서 만든다
 * @param defaultTtlMinutes 링크 기본 수명
 * @param maxTtlMinutes    링크 최대 수명
 * @param maxActivePerUser 사용자당 살아 있는 링크 수
 * @param maxTunnels       서버 전체 연결 터널 수
 * @param maxViewersPerShare 링크당 보는 사람 수
 * @param maxFrameBytes    화면 한 장 최대 크기
 * @param maxInputsPerSecond 조작권 가진 사람의 초당 입력 수
 * @param controlTtlSeconds 승인으로 받은 조작권 기본 시간
 */
@ConfigurationProperties(prefix = "qeploy.device-share")
public record DeviceShareProperties(
        String baseDomain,
        String publicScheme,
        int publicPort,
        String apiPublicUrl,
        int defaultTtlMinutes,
        int maxTtlMinutes,
        int maxActivePerUser,
        int maxTunnels,
        int maxViewersPerShare,
        int maxFrameBytes,
        int maxInputsPerSecond,
        int controlTtlSeconds
) {

    public DeviceShareProperties {
        baseDomain = (baseDomain == null || baseDomain.isBlank()) ? "share.localhost" : baseDomain.trim().toLowerCase();
        publicScheme = (publicScheme == null || publicScheme.isBlank()) ? "http" : publicScheme.trim().toLowerCase();
        defaultTtlMinutes = defaultTtlMinutes > 0 ? defaultTtlMinutes : 60;
        maxTtlMinutes = maxTtlMinutes > 0 ? maxTtlMinutes : 1440;
        maxActivePerUser = maxActivePerUser > 0 ? maxActivePerUser : 5;
        maxTunnels = maxTunnels > 0 ? maxTunnels : 50;
        maxViewersPerShare = maxViewersPerShare > 0 ? maxViewersPerShare : 20;
        maxFrameBytes = maxFrameBytes > 0 ? maxFrameBytes : 2 * 1024 * 1024;
        maxInputsPerSecond = maxInputsPerSecond > 0 ? maxInputsPerSecond : 120;
        controlTtlSeconds = controlTtlSeconds > 0 ? controlTtlSeconds : 600;
    }

    /** 보기 링크 기준 주소(끝 슬래시 포함). */
    public String viewUrl(String token) {
        String port = publicPort > 0 ? ":" + publicPort : "";
        return publicScheme + "://" + token + "." + baseDomain + port + "/";
    }
}
