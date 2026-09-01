package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.preview.application.service.PreviewRuntimeConfigService;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RUNTIME_SETUP 스텝: 사용자가 대화로 "백엔드로 만들어줘 / API 도 / DB 붙여줘" 라고 하면, 이 스텝이
 * 프로젝트의 프리뷰 런타임 설정(runtimeType·dbEngine·startCommand)을 저장한다. 그러면 뒤이어 오는
 * CODE 스텝의 프리뷰가 정적이 아니라 그 런타임(NODE_SERVER/JAVA_FULLSTACK)으로 뜨고 DB 도 자동으로
 * 붙는다(백엔드-인-프리뷰 B).
 *
 * <p>이 스텝은 CODE 보다 <b>앞에</b> 와야 한다 — JAVA 는 컨테이너 메모리가 생성 시점의 저장된
 * runtimeType 으로 정해지므로, CODE 가 컨테이너를 만들기 전에 저장돼 있어야 2GB 를 받는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeSetupAgentService {

    private final PreviewRuntimeConfigService runtimeConfigService;

    public CodeResult execute(AgentStep step, Long userId, Long projectId) {
        if (projectId == null) {
            // 런타임 설정은 프로젝트 단위다. 프로젝트가 아직 없으면(신규 대화 등) 조용히 건너뛴다 —
            // 프로젝트가 생긴 뒤 다시 요청하면 적용된다.
            log.warn("[RUNTIME_SETUP] 프로젝트가 없어 런타임 설정을 건너뜁니다 | userId={}", userId);
            return new CodeResult(null,
                    "프로젝트가 아직 없어 런타임 설정을 건너뛰었습니다. 프로젝트를 먼저 만든 뒤 다시 요청해주세요.");
        }

        // 파싱 실패/미제공이면 null → patch 가 기존 런타임 타입을 유지한다(정적으로 뜻하지 않게
        // 다운그레이드하지 않는다). startCommand·dbEngine 도 비면 null → 기존 값 보존.
        PreviewRuntimeType runtimeType = parseRuntimeType(step.parameters().get("runtimeType"));
        String startCommand = blankToNull(step.parameters().get("startCommand"));
        String dbEngine = blankToNull(step.parameters().get("dbEngine"));

        // 부분 갱신: 제공된 필드만 덮고 인프라 탭에서 설정한 나머지는 보존한다(replace-all 금지).
        var result = runtimeConfigService.patch(userId, projectId, runtimeType, startCommand, dbEngine);

        String summary = "프리뷰 런타임을 " + result.runtimeType() + " 로 설정했습니다"
                + (dbEngine != null ? " (DB 엔진: " + dbEngine + ")" : "")
                + ". 다음 프리뷰부터 이 런타임으로 뜨고, 서버형이면 DB 가 자동으로 붙습니다.";
        log.info("[RUNTIME_SETUP] 런타임 설정 저장 | projectId={} type={} dbEngine={}",
                projectId, result.runtimeType(), dbEngine);
        return new CodeResult(null, summary);
    }

    /** 파싱 실패/빈 값이면 null 을 돌려준다 — 호출자(patch)가 기존 런타임 타입을 유지한다. */
    private PreviewRuntimeType parseRuntimeType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PreviewRuntimeType.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
