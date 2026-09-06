package com.example.dvely.domainbinding.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "이 서버가 도메인 연결을 지원하는 배포 대상 목록. 어댑터가 등록된 것만 담긴다 — FE 가 이걸 "
        + "읽어 지원하지 않는 옵션을 노출하지 않게 한다(FE 하드코딩과 서버 지원이 어긋나는 드리프트 방지). "
        + "dev/운영 등 배포마다 어댑터 구성이 달라 값이 다를 수 있다.")
public record HostingTargetsResponse(
        @Schema(description = "지원 배포 대상 값(enum 이름)",
                example = "[\"GITHUB_PAGES\", \"AWS\", \"AWS_EC2_FRONTEND\", \"AWS_S3_FRONTEND\"]")
        List<String> hostingTargets) {
}
