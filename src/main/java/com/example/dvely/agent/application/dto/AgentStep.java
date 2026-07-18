package com.example.dvely.agent.application.dto;

import com.example.dvely.agent.domain.value.AgentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "에이전트 실행 단계 하나")
public record AgentStep(

        @Schema(description = "실행할 에이전트 유형", example = "CODE")
        AgentType agentType,

        @Schema(description = """
                에이전트 유형별 파라미터.
                - CODE: instruction(코딩 지시), targetFile(대상 파일)
                - DEPLOY: instruction(배포 지시), repoName(저장소명), version(버전)
                - DOMAIN_BIND: instruction(도메인 설정 지시), domain(도메인값)
                - CHAT: instruction(질문/요청 내용)
                - INFRA_OPERATE: operation(STATUS_CHECK|LOG_VIEW|FAILURE_ANALYSIS|RESTART|
                  RESOURCE_SCALING|AUTOSCALING_CHANGE|RESOURCE_CLEANUP), instruction(운영 요청 내용)
                """,
                example = "{\"instruction\": \"Create a React todo app\", \"targetFile\": \"\"}")
        Map<String, String> parameters
) {}
