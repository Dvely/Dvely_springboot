package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.preview.application.result.PreviewRuntimeConfigResult;
import com.example.dvely.preview.application.service.PreviewRuntimeConfigService;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeSetupAgentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 11L;

    private PreviewRuntimeConfigService runtimeConfigService;
    private RuntimeSetupAgentService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(PreviewRuntimeConfigService.class);
        service = new RuntimeSetupAgentService(runtimeConfigService);
    }

    private void stubPatchReturns(String runtimeType) {
        when(runtimeConfigService.patch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PreviewRuntimeConfigResult(
                        PROJECT_ID, runtimeType, null, "/api", null, "MYSQL", "STORED"));
    }

    @Test
    void patchesTheRuntimeConfigFromStepParameters() {
        stubPatchReturns("NODE_SERVER");
        AgentStep step = new AgentStep(AgentType.RUNTIME_SETUP,
                Map.of("runtimeType", "NODE_SERVER", "dbEngine", "MYSQL"));

        CodeResult result = service.execute(step, USER_ID, PROJECT_ID);

        // 부분 갱신: 제공된 필드만(runtimeType·dbEngine). apiPathPrefix·healthPath 는 patch 가 보존한다.
        verify(runtimeConfigService).patch(USER_ID, PROJECT_ID, PreviewRuntimeType.NODE_SERVER, null, "MYSQL");
        assertThat(result.previewUrl()).isNull();
        assertThat(result.summary()).contains("NODE_SERVER");
    }

    /** 런타임 설정은 프로젝트 단위다 — 프로젝트가 없으면 저장하지 않고 안내만 한다. */
    @Test
    void skipsWhenProjectIdIsNull() {
        AgentStep step = new AgentStep(AgentType.RUNTIME_SETUP, Map.of("runtimeType", "NODE_SERVER"));

        CodeResult result = service.execute(step, USER_ID, null);

        verifyNoInteractions(runtimeConfigService);
        assertThat(result.summary()).contains("프로젝트");
    }

    /**
     * 알 수 없는/빠진 runtimeType 은 null 로 넘겨 patch 가 기존 런타임 타입을 유지하게 한다 —
     * 뜻하지 않게 STATIC 으로 다운그레이드해 기존 백엔드 프리뷰를 정적으로 만들지 않는다.
     */
    @Test
    void unknownRuntimeTypeKeepsExistingViaNull() {
        stubPatchReturns("NODE_SERVER");   // 기존이 NODE_SERVER 라고 가정 — 유지돼야 한다
        AgentStep step = new AgentStep(AgentType.RUNTIME_SETUP, Map.of("runtimeType", "WAT"));

        service.execute(step, USER_ID, PROJECT_ID);

        verify(runtimeConfigService).patch(eq(USER_ID), eq(PROJECT_ID), isNull(), isNull(), isNull());
    }
}
