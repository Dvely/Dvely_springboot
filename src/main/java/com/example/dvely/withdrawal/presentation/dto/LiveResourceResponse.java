package com.example.dvely.withdrawal.presentation.dto;

import com.example.dvely.withdrawal.application.LiveResource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "탈퇴를 막는, 사용자 AWS 계정에 아직 살아 있는 자원 하나")
public record LiveResourceResponse(
        @Schema(description = "SERVER(EC2·EIP) | DATABASE(RDS·Docker DB) | CDN(CloudFront) | CDN_DELETION(정리 중인 CloudFront·인증서) | DEPLOYMENT(대기·진행 중인 배포)")
        String type,
        @Schema(description = "Qeploy 안의 식별자(서버·DB·도메인·정리 작업·배포 이력 번호)")
        long id,
        @Schema(description = "속한 프로젝트. 이미 삭제한 프로젝트일 수 있다. CDN_DELETION 은 null")
        Long projectId,
        @Schema(description = "사람이 알아볼 이름(인스턴스 id, DB 엔진, 도메인 이름, 배포 제목 등). 없으면 null")
        String label,
        @Schema(description = "Qeploy 가 기록한 상태")
        String status
) {

    public static List<LiveResourceResponse> from(List<LiveResource> resources) {
        return resources.stream()
                .map(r -> new LiveResourceResponse(r.type().name(), r.id(), r.projectId(), r.label(), r.status()))
                .toList();
    }
}
