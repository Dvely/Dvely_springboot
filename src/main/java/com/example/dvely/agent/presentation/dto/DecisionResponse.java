package com.example.dvely.agent.presentation.dto;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.domain.value.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "에이전트 요청 접수 응답. taskId로 진행 상태를 폴링하세요.")
public record DecisionResponse(

        @Schema(description = "AI가 분석한 실행 단계 목록 (CODE → DEPLOY 순 등)")
        List<AgentStep> steps,

        @Schema(description = "AI의 단계 분류 근거 설명", example = "코드 생성 후 GitHub Pages 배포 요청으로 판단")
        String reasoning,

        @Schema(description = "사용된 AI 제공자", example = "ANTHROPIC")
        AiProvider aiProvider,

        @Schema(description = "비동기 태스크 ID. 상태 조회 및 입력 제출에 사용", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        String taskId,

        @Schema(description = "현재 task 상태", example = "WAITING_APPROVAL")
        String status,

        @Schema(description = "승인이 필요한 경우 생성된 approval ID 목록")
        List<Long> approvalIds
) {}
