package com.example.dvely.deployment.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "배포 실패 원인 분석 결과")
public record DeploymentFailureAnalysisResponse(
        @Schema(description = "분석 대상 배포 이력 ID") Long deploymentId,
        @Schema(description = "비개발자용 실패 원인 요약 (한국어, 3문장 이내)") String summary,
        @Schema(description = "원인 로그 발췌 (최대 12,000자)") String logExcerpt,
        @Schema(description = "가장 가능성 높은 수정 방법 1개") String suggestedFix,
        @Schema(description = "분석 방식 (LLM | RULE_BASED). 서버 키 LLM 호출을 제거해 신규 분석은 항상 RULE_BASED이며, LLM은 과거에 저장된 분석에만 나타납니다.") String analysisSource,
        @Schema(description = "분석 완료 시각") LocalDateTime analyzedAt
) {
}
