package com.example.dvely.approval.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 승인 확정 시 함께 보낼 수 있는 값. 본문 전체가 선택이며, 보내지 않으면 이 API 는 이 DTO 가 생기기
 * 전과 동일하게 동작한다 — CHANGE/DEPLOYMENT/DOMAIN_BINDING/INFRA_OPERATION/RESULT 는 이 값을
 * 읽지 않는다.
 */
@Schema(description = "승인 확정 요청 본문 (선택)")
public record ApprovalDecisionRequest(

        @Schema(
                description = "REPOSITORY_BINDING 승인에서 생성·연결할 GitHub 저장소 이름. "
                        + "소문자·숫자·하이픈만 유효하며 그 외 문자는 하이픈으로 치환된다. "
                        + "생략하거나 비우면 승인 요약에 표시된 후보 이름(프로젝트명 기반)이 쓰인다. "
                        + "다른 승인 유형에서는 무시된다.",
                example = "apgujeong-hyundai",
                nullable = true
        )
        String repositoryName
) {}
