package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.domain.value.AgentType;
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

    @Test
    void savesTheRuntimeConfigFromStepParameters() {
        AgentStep step = new AgentStep(AgentType.RUNTIME_SETUP,
                Map.of("runtimeType", "NODE_SERVER", "dbEngine", "MYSQL"));

        CodeResult result = service.execute(step, USER_ID, PROJECT_ID);

        // 런타임 타입·엔진을 그대로 저장한다. startCommand/apiPathPrefix/healthPath 는 비면 null.
        verify(runtimeConfigService).upsert(
                USER_ID, PROJECT_ID, PreviewRuntimeType.NODE_SERVER, null, null, null, "MYSQL");
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

    /** 알 수 없는/빠진 runtimeType 은 STATIC 으로 안전하게 떨어진다(LLM 이 이상한 값을 줘도 안 깨진다). */
    @Test
    void defaultsToStaticForUnknownRuntimeType() {
        AgentStep step = new AgentStep(AgentType.RUNTIME_SETUP, Map.of("runtimeType", "WAT"));

        service.execute(step, USER_ID, PROJECT_ID);

        verify(runtimeConfigService).upsert(
                eq(USER_ID), eq(PROJECT_ID), eq(PreviewRuntimeType.STATIC), isNull(), isNull(), isNull(), isNull());
    }
}
