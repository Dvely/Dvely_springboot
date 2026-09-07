package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import com.example.dvely.common.exception.LlmProviderException;
import com.example.dvely.project.domain.value.FrontendHostingType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 결정 응답이 스키마대로 오지 않을 때의 처리. 예전에는 이 모든 경우가 조용히 {@code CHAT} 스텝으로
 * 폴백하면서 모델의 응답 원문을 사용자의 질문인 양 실어 보냈고, 승인도 안 걸려서 요청한 작업이
 * 경고 없이 증발했다(2026-09-07 dev 실측). 지금은 한 번 교정을 요청하고, 그래도 안 되면 던진다.
 */
@ExtendWith(MockitoExtension.class)
class DecisionAgentServiceTest {

    private static final String VALID_PLAN = """
            {
              "steps": [
                { "agentType": "CODE", "parameters": { "instruction": "todo 앱을 만든다", "targetFile": "" } }
              ],
              "reasoning": "코드 한 단계"
            }
            """;

    @Mock
    private LlmRouter llmRouter;

    @Mock
    private LlmPort llmPort;

    @Mock
    private DeployTargetContextResolver deployTargetContextResolver;

    private DecisionAgentService service;

    @BeforeEach
    void setUp() {
        service = new DecisionAgentService(llmRouter, deployTargetContextResolver);
        when(llmRouter.route(AiProvider.GLM)).thenReturn(llmPort);
        when(deployTargetContextResolver.resolve(44L)).thenReturn(Optional.empty());
    }

    private AgentPlan decide() {
        return service.decide("todo 앱 만들어줘", AiProvider.GLM, 44L);
    }

    private void answers(String first, String... rest) {
        when(llmPort.complete(any(), anyList(), any())).thenReturn(first, rest);
    }

    @Test
    void 정상_응답은_계획으로_읽고_재시도하지_않는다() {
        answers(VALID_PLAN);

        AgentPlan plan = decide();

        assertThat(plan.steps()).extracting(AgentStep::agentType).containsExactly(AgentType.CODE);
        assertThat(plan.steps().getFirst().parameters()).containsEntry("instruction", "todo 앱을 만든다");
        verify(llmPort, times(1)).complete(any(), anyList(), any());
    }

    /**
     * 2026-09-07 dev 에서 실제로 나온 응답. 키 이름 뒤에 여분의 따옴표가 하나 붙어 JSON 이 깨졌는데,
     * 계획 자체는 멀쩡했다 — 한 글자 때문에 버리지 말고 다시 물어봐야 한다.
     */
    @Test
    void 형식만_깨진_응답은_교정을_요청해_다시_받는다() {
        String strayQuote = """
                {
                  "steps": [ { "agentType": "CODE", "parameters": { "instruction": "todo 앱" } } ],
                  "reasoning "": "정적 프론트"
                }
                """;
        answers(strayQuote, VALID_PLAN);

        AgentPlan plan = decide();

        assertThat(plan.steps()).extracting(AgentStep::agentType).containsExactly(AgentType.CODE);
        verify(llmPort, times(2)).complete(any(), anyList(), any());
    }

    @Test
    void 교정_요청에는_실패_사유와_직전_응답이_함께_실린다() {
        answers("죄송합니다. 계획을 세우겠습니다.", VALID_PLAN);

        decide();

        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmPort, times(2)).complete(any(), captor.capture(), any());
        String repairTurn = captor.getAllValues().get(1).getLast().content();
        assertThat(repairTurn)
                .contains("ONE JSON object only")
                .contains("죄송합니다. 계획을 세우겠습니다.");
    }

    /**
     * JSON 뒤에 모델이 덧붙인 인사말에 중괄호가 섞여 있어도 계획은 읽혀야 한다. 마지막 '}' 까지
     * 통째로 자르던 예전 방식은 여기서 깨졌다(dev 실측 9건이 이 모양이었다).
     */
    @Test
    void JSON_뒤에_산문이_붙어도_첫_객체만_잘라_읽는다() {
        answers(VALID_PLAN + "\n작업 계획을 만들었습니다. 승인하시면 {즉시} 실행합니다.");

        AgentPlan plan = decide();

        assertThat(plan.steps()).extracting(AgentStep::agentType).containsExactly(AgentType.CODE);
        verify(llmPort, times(1)).complete(any(), anyList(), any());
    }

    @Test
    void 두_번_다_실패하면_CHAT_으로_폴백하지_않고_던진다() {
        answers("계획을 세웠습니다.", "네, 다시 정리하면 이렇습니다.");

        assertThatThrownBy(this::decide)
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("응답을 이해하지 못해")
                .extracting(e -> ((LlmProviderException) e).reason())
                .isEqualTo(LlmProviderException.Reason.MALFORMED_RESPONSE);

        verify(llmPort, times(2)).complete(any(), anyList(), any());
    }

    /** 모르는 유형을 CHAT 으로 슬쩍 바꾸면 "배포해줘"가 채팅 답변으로 끝난다 — 다시 쓰게 한다. */
    @Test
    void 알_수_없는_agentType_은_교정을_요청한다() {
        answers("""
                { "steps": [ { "agentType": "DEPLOY_TO_S3", "parameters": {} } ], "reasoning": "" }
                """, VALID_PLAN);

        AgentPlan plan = decide();

        assertThat(plan.steps()).extracting(AgentStep::agentType).containsExactly(AgentType.CODE);
        verify(llmPort, times(2)).complete(any(), anyList(), any());
    }

    /** steps 키가 없으면 예전에는 빈 계획이 성공으로 실행돼 아무 일도 일어나지 않았다. */
    @Test
    void steps_가_없거나_비면_빈_계획을_통과시키지_않는다() {
        answers("""
                { "plan": [ { "agentType": "CODE" } ], "reasoning": "키 이름을 틀렸다" }
                """, VALID_PLAN);

        AgentPlan plan = decide();

        assertThat(plan.steps()).hasSize(1);
        verify(llmPort, times(2)).complete(any(), anyList(), any());
    }

    /** 파라미터 값이 문자열이 아니면 예전에는 여기를 통과하고 스텝을 읽는 곳에서 터졌다. */
    @Test
    void 문자열이_아닌_파라미터_값은_문자열로_읽는다() {
        answers("""
                {
                  "steps": [ { "agentType": "DEPLOY",
                               "parameters": { "instruction": "배포", "version": 2, "repoName": "todo" } } ],
                  "reasoning": ""
                }
                """);

        AgentPlan plan = decide();

        assertThat(plan.steps().getFirst().parameters()).containsEntry("version", "2");
        verify(llmPort, times(1)).complete(any(), anyList(), any());
    }

    @Test
    void 되묻기_응답은_CLARIFY_스텝_하나로_읽는다() {
        answers("""
                {
                  "clarification": {
                    "question": "백엔드 스택을 무엇으로 할까요?",
                    "inputType": "SINGLE_SELECT",
                    "options": [ { "value": "node", "label": "Node/Express", "recommended": false } ],
                    "allowOther": false
                  },
                  "reasoning": "스택 미지정"
                }
                """);

        AgentPlan plan = decide();

        assertThat(plan.steps()).extracting(AgentStep::agentType).containsExactly(AgentType.CLARIFY);
        assertThat(plan.steps().getFirst().parameters().get("clarification")).contains("SINGLE_SELECT");
    }

    /**
     * 배포 위치를 물어보려면 모델이 먼저 "고를 수 있는 곳이 어디인지" 를 알아야 한다. 클라우드 연결이
     * 없으면 S3·EC2 는 고를 수 없고, 그런데도 선택지로 내밀면 사용자가 고른 뒤 배포에서 거부당한다.
     */
    @Test
    void 프로젝트의_배포_위치_사실을_프롬프트에_실어_보낸다() {
        when(deployTargetContextResolver.resolve(44L)).thenReturn(Optional.of(
                new DeployTargetContextResolver.DeployTargetContext(
                        FrontendHostingType.GITHUB_PAGES, false, false)));
        answers(VALID_PLAN);

        decide();

        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmPort).complete(any(), captor.capture(), any());
        assertThat(captor.getValue().getLast().content())
                .contains("current=GITHUB_PAGES")
                .contains("everDeployed=false")
                .contains("availableTargets=GITHUB_PAGES")
                .contains("no cloud connection selected");
    }

    @Test
    void 배포_위치를_모르면_아무_줄도_붙이지_않는다() {
        answers(VALID_PLAN);

        decide();

        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmPort).complete(any(), captor.capture(), any());
        assertThat(captor.getValue()).noneMatch(m -> m.content().contains("Frontend hosting context"));
    }

    @Test
    void 결정이_고른_배포_위치는_스텝_파라미터로_넘어간다() {
        answers("""
                {
                  "steps": [ { "agentType": "DEPLOY",
                               "parameters": { "instruction": "배포", "version": "",
                                               "repoName": "todo", "hostingType": "S3" } } ],
                  "reasoning": "사용자가 S3 를 지목했다"
                }
                """);

        AgentPlan plan = decide();

        assertThat(plan.steps().getFirst().parameters()).containsEntry("hostingType", "S3");
    }
}
