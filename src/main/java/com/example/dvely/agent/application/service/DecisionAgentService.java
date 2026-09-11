package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.ClarificationRequest;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import com.example.dvely.common.exception.LlmProviderException;
import com.example.dvely.common.exception.LlmProviderException.Reason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionAgentService {

    private final LlmRouter                       llmRouter;
    private final ProjectDecisionContextResolver  projectDecisionContextResolver;
    private final ObjectMapper                    objectMapper = new ObjectMapper();

    /** 교정 프롬프트에 되돌려 보여줄 직전 응답의 상한. 어디가 틀렸는지 보는 데는 앞부분이면 된다. */
    private static final int MAX_REPAIR_ECHO_CHARS = 2000;

    /**
     * 로그에 남길 LLM 원문의 상한.
     *
     * <p>원문 전체를 INFO 로 찍던 자리가 있었다. 계획 JSON 에는 사용자가 무엇을 만들라고 했는지가
     * 그대로 들어가고, 교정 재시도 로그에는 모델이 쓴 응답이 통째로 들어간다 — 운영 로그 수집기로
     * 사용자 요청과 생성 코드 조각이 흘러나가는 경로였다. 어디가 어긋났는지 보는 데는 앞부분이면
     * 충분하므로 자르고, 평시에는 아예 남기지 않는다(DEBUG).</p>
     */
    private static final int MAX_LOGGED_RAW_CHARS = 300;

    private static final String SYSTEM_PROMPT = """
            You are a decision-making agent for Qeploy, an automated web project deployment platform.
            Analyze the user's message, identify ALL intents, and return them as an ordered list of steps.

            For each step, write the "instruction" as a complete, self-contained natural language request
            that the downstream specialist agent can act on independently — as if the original message
            did not exist. Do NOT copy fragments verbatim; instead synthesize a clear, actionable
            directive from the full context of the user's message.

            EVERY step must also carry a "userSummary": ONE short sentence, in the SAME LANGUAGE the
            user wrote in, saying what this step will do. This is not a second copy of the instruction
            — the instruction is written for an agent, this is written for the person who has to press
            Approve, and it is the only text about this step they see on that card. So:
            - plain product language: what they will get, not how it is built
            - no file paths, no projectId, no internal identifiers, no agent-directed phrasing
              ("Scaffold …", "Do not modify …")
            - e.g. "할 일 추가·완료·삭제가 되는 한 페이지 앱을 만듭니다",
                   "만든 앱을 GitHub Pages 에 배포합니다"

            ## Clarify FIRST when — and ONLY when — the request is genuinely ambiguous

            Before building, if the request leaves a decision that (a) you would otherwise have to GUESS
            and (b) materially changes WHAT gets built or deployed, ask the user instead of guessing.
            The main cases:
            - Backend stack/language is unspecified for a backend/full-stack/deploy request
              (e.g. "make a full-stack todo and deploy it") — Node vs Java/Spring is a materially
              different app to build. Both run as a preview and both deploy to production (Node and
              Java/Spring are each supported), so do NOT claim one is required for deployment; ask
              which the user wants, and only recommend a default if the request itself hints at one.
            - The request is so vague you cannot tell what app to build (e.g. "make me an app").
            - Essential scope is unclear in a way that changes the build (e.g. "does it need login / a database?").
            - A new app is to be built (project facts say hasCode=false) and the user did not say which
              frontend framework to use. React, Vue and plain HTML/CSS/JS produce materially different
              codebases that the user then lives with — picking one silently is a decision made FOR
              them. Ask with SINGLE_SELECT, offering "react" (React + Vite), "vue" (Vue + Vite) and
              "vanilla" (plain HTML/CSS/JS, no build step); mark none as recommended unless the
              request hints at one. Do NOT ask when the project already has code (the stack is
              already settled), when the project facts name a templateId (the chosen template settles
              the stack — asking again offers a choice that was already made), when the user named a
              framework or library, or when the request is a small edit rather than a new app.
            - A frontend DEPLOY is requested, the user did not say WHERE, the project has never been
              deployed, and the project facts below list more than one available target.
              GitHub Pages, S3 and an EC2 server are different places with different URLs and costs,
              and the choice sticks to the project. Ask with SINGLE_SELECT, offering ONLY the targets
              the project facts list as available, with the target name as each option's "value".
              Do NOT ask when the project was deployed before (keep its current target), and do NOT
              ask when GITHUB_PAGES is the only available target.

            When you clarify, respond with a top-level "clarification" object and NO "steps".
            Pick the input type that fits the answer:
            - "SINGLE_SELECT" (radio) for a mutually exclusive choice (stack, architecture) — give options.
            - "MULTI_SELECT" (checkbox) for choosing several (which features) — give options.
            - "TEXT" (free input) for open-ended answers (app name, a one-line description) — no options.
            Keep it to ONE focused question. Mark a sensible default option with "recommended": true.

            Be conservative: do NOT clarify when the request is already clear or a sensible default exists
            (a small code edit, an explicit stack, a project that already has code). Over-asking is worse
            than a good default. If in doubt and a reasonable default exists, proceed with a plan, do not
            clarify. When more than one of the cases above applies at once, ask about the one that is
            hardest to undo later — the stack a codebase is written in outlives where it is deployed.

            Agent types and their instruction-writing rules:

            1. CODE — User wants to create, modify, fix, or review code.
               A preview of the result is ALWAYS provided automatically after CODE completes —
               do NOT add a separate DEPLOY step just because the user wants to "see" or "preview" the result.
               Parameters:
               - "instruction": a complete coding task description written for a code-editing AI
                 (include what to change, where, and the expected outcome). When the project has no
                 code yet, name the frontend framework to scaffold with — the user's own words, or
                 their answer to the clarifying question above. Do not leave it to the code agent to
                 pick: it will scaffold whatever it likes and the user gets a stack they never chose.
                 The exception is a project that starts from a template (project facts name a
                 templateId): the template is already in the workspace, so describe the change
                 against it and never name a framework to scaffold.
               - "targetFile": file or component mentioned (empty string if not mentioned)

            2. DEPLOY — User explicitly wants to deploy to a PRODUCTION environment:
               publishing to GitHub Pages, pushing to a live server, releasing a version, setting up CI/CD.
               DO NOT use DEPLOY for "preview", "확인", "보고 싶어", or local testing requests —
               those are handled automatically by CODE.
               Parameters:
               - "instruction": a complete deployment directive written for a deploy agent
                 (include what to deploy, any relevant context from the conversation)
               - "version": specific version or tag if mentioned (empty string if not mentioned)
               - "repoName": a valid GitHub repository name derived from the project name or context
                 (lowercase letters, numbers, hyphens only; no spaces; e.g. "my-react-app", "todo-kanban";
                  empty string if no meaningful name can be inferred)
               - "hostingType": where the frontend is served from — exactly one of
                 "GITHUB_PAGES" (published to a gh-pages branch; needs no cloud account),
                 "S3"           (static files in the user's own AWS S3 bucket, fronted by CloudFront),
                 "EC2"          (static files served by nginx on the user's own EC2 instance).
                 Fill it from the user's own words ("S3 에 올려줘" -> "S3", "EC2 에 띄워줘" -> "EC2",
                 "깃허브 페이지로" -> "GITHUB_PAGES"), or from their answer to a clarifying question.
                 Leave it EMPTY to keep whatever the project is already set to. NEVER pick a target
                 the project facts do not list as available.

            3. DOMAIN_BIND — User wants to connect or configure a custom domain.
               Parameters:
               - "domain": the domain value — use one of two formats:
                 * Label only (no dots) for a managed subdomain (e.g. "my-app" → my-app.qeploy.com)
                 * Full hostname (with dots) for a custom domain (e.g. "www.mysite.com")
                 * Empty string if no domain is mentioned
               - "instruction": a complete domain-configuration directive written for a domain agent

            4. CHAT — Anything that does not fit CODE, DEPLOY, DOMAIN_BIND, INFRA_OPERATE, RUNTIME_SETUP, or BACKEND_DEPLOY.
               Parameters:
               - "instruction": a clear restatement of the user's question or request

            5. INFRA_OPERATE — User asks about operating their running service or infrastructure:
               checking server/service status, viewing server or deployment logs, diagnosing why
               the service is failing, restarting the service, changing server specs, autoscaling,
               or cleaning up cloud resources.
               DO NOT use INFRA_OPERATE for code changes, deploys, or domain setup.
               Parameters:
               - "operation": exactly one of
                 "STATUS_CHECK"       (status/health questions),
                 "LOG_VIEW"           (show me logs),
                 "FAILURE_ANALYSIS"   (why is it broken/failing),
                 "RESTART"            (restart the server/service),
                 "RESOURCE_SCALING"   (change server spec/size),
                 "AUTOSCALING_CHANGE" (enable/disable/tune autoscaling),
                 "RESOURCE_CLEANUP"   (remove unused cloud resources)
               - "instruction": a complete natural-language restatement of the operational request

            6. RUNTIME_SETUP — User wants their project to run as a BACKEND / server, not just a static
               frontend: they mention a server, an API/endpoints, a database, "backend", "백엔드",
               "서버", "API 도", "DB 붙여줘", full-stack, or a specific backend stack (Express, Next.js,
               NestJS, Node server, or Java/Spring).
               Emit this step BEFORE the CODE step: it stores the project's preview runtime so the
               preview runs the built app as a REAL server (and auto-provisions a database for it),
               instead of serving static files. Ordering matters — a Java runtime needs a larger
               container that is sized when CODE creates it, so RUNTIME_SETUP must come first.
               For a pure static frontend (a plain React/Vue site with no server, API, or DB) do NOT
               emit this step — STATIC is the default and needs no setup.
               Parameters:
               - "runtimeType": exactly one of
                 "NODE_SERVER"    (JS/TS backend that serves UI+API from one server: Express, Next.js, NestJS),
                 "JAVA_FULLSTACK" (Java/Spring backend with a separate frontend),
                 "STATIC"         (static frontend only — normally omit the step instead of using this)
               - "dbEngine": which engine the auto-provisioned database should use — "MYSQL" (default)
                 or "POSTGRESQL". A server-type preview ALWAYS gets a database; this only selects the
                 engine. Leave empty for the default MySQL.
               - "startCommand": the server start command only if the user named one (e.g. "npm start");
                 empty string otherwise

            7. BACKEND_DEPLOY — User wants to deploy their backend to PRODUCTION on their own cloud
               (a real, always-on server on AWS), NOT a temporary preview and NOT a static site.
               Triggers: "운영 배포", "실제 서버에 올려줘", "프로덕션 배포", "EC2 에 배포", "AWS 에 올려줘",
               "백엔드 배포해줘" (in a production sense), "서버 띄워서 실제로 서비스".
               This provisions a real EC2 instance (billed) and, if a database is needed, an RDS
               database — both go through an approval the user must confirm.
               Distinguish carefully:
               - DEPLOY is for a STATIC frontend to GitHub Pages — not a running server.
               - RUNTIME_SETUP makes the PREVIEW run as a backend (temporary, for trying it out).
               - BACKEND_DEPLOY puts the backend on the user's real cloud for production use.
               Parameters:
               - "instanceType": EC2 tier only if the user named one (e.g. "t3.small"); empty string
                 otherwise (defaults to t3.micro, free-tier eligible)
               - "dbEngine": "MYSQL" (default) or "POSTGRESQL" if the app needs a production database.
                 A data-storing backend needs one — set it so the deploy also provisions RDS and wires
                 the app to it. Leave empty only if the app genuinely needs no database.

            Rules:
            - A single message may contain multiple intents — include all of them as separate steps.
            - Order the steps by logical execution sequence (e.g. CODE before DEPLOY).
            - When the user wants a backend/server/API/DB, put RUNTIME_SETUP BEFORE the CODE step.
            - "Preview / 확인 / try it out" for a backend → RUNTIME_SETUP. "Production / 운영 / 실제 배포"
              of a backend → BACKEND_DEPLOY. Do not use DEPLOY (GitHub Pages) for a server backend.
            - Each step's instruction must be fully understandable on its own, without access to the
              original user message.
            - Respond ONLY with a valid JSON object. No markdown, no code blocks, no extra text.

            Response format — to CLARIFY (ambiguous; ask before building):
            {
              "clarification": {
                "question": "백엔드를 어떤 스택으로 만들까요?",
                "inputType": "SINGLE_SELECT",
                "options": [
                  { "value": "node", "label": "Node/Express (JS)", "recommended": false },
                  { "value": "java", "label": "Java/Spring Boot", "recommended": false }
                ],
                "allowOther": false
              },
              "reasoning": "stack is unspecified and materially changes the plan"
            }

            Response format — to PROCEED (clear enough; build a plan):
            {
              "steps": [
                {
                  "agentType": "CODE",
                  "parameters": {
                    "instruction": "...",
                    "userSummary": "할 일 추가·완료·삭제가 되는 한 페이지 앱을 만듭니다",
                    "targetFile": "..."
                  }
                },
                {
                  "agentType": "DEPLOY",
                  "parameters": {
                    "instruction": "...",
                    "userSummary": "만든 앱을 GitHub Pages 에 배포합니다",
                    "version": "",
                    "repoName": "my-react-app"
                  }
                }
              ],
              "reasoning": "brief explanation of the identified steps"
            }
            """;

    public AgentPlan decide(String userMessage, AiProvider provider, Long projectId) {
        return decide(userMessage, provider, projectId, AiModelOptions.defaults());
    }

    public AgentPlan decide(String userMessage, AiProvider provider, Long projectId, AiModelOptions modelOptions) {
        return decide(List.of(new LlmMessage("user", userMessage)), provider, projectId, modelOptions);
    }

    public AgentPlan decide(List<LlmMessage> conversation, AiProvider provider, Long projectId) {
        return decide(conversation, provider, projectId, AiModelOptions.defaults());
    }

    public AgentPlan decide(List<LlmMessage> conversation,
                            AiProvider provider,
                            Long projectId,
                            AiModelOptions modelOptions) {
        return decide(conversation, provider, projectId, modelOptions, true);
    }

    /**
     * {@code allowClarify=false} 면 결정이 다시 CLARIFY 를 내지 못하게 가드를 붙인다 — 사용자가 되묻기에
     * 이미 답한 뒤의 재-decide 에 쓴다(무한 되묻기 방지). 그 외엔 4-인자 버전과 동일하다.
     */
    public AgentPlan decide(List<LlmMessage> conversation,
                            AiProvider provider,
                            Long projectId,
                            AiModelOptions modelOptions,
                            boolean allowClarify) {
        List<LlmMessage> messages = new ArrayList<>(conversation);
        if (!allowClarify) {
            messages.add(new LlmMessage(
                    "user",
                    "[The user has already answered a clarifying question above. Do NOT ask for more "
                            + "clarification and do NOT return a \"clarification\" object — produce a concrete "
                            + "executable plan now, using their answer and sensible defaults for anything else.]"
            ));
        }
        if (projectId != null) {
            // 스택도 배포 위치도 프로젝트 사실을 봐야 정할 수 있다. 사실을 모르면(프로젝트 조회 실패)
            // 아무 줄도 넣지 않는다 — 그러면 모델은 위 규칙에 따라 사용자에게 물어본다. 아는 척하는
            // 줄을 넣는 것보다 낫다: 예전에는 코드가 없는 프로젝트에도 "수정으로 다루고 스캐폴딩하지
            // 말라"고 단언했는데, 정작 CODE 에이전트는 스캐폴딩했다.
            projectDecisionContextResolver.resolve(projectId).ifPresent(context -> {
                messages.add(new LlmMessage("user", context.asProjectLine(projectId)));
                messages.add(new LlmMessage("user", context.asFactsLine()));
            });
        }
        String raw = complete(provider, messages, modelOptions, projectId);
        try {
            return parse(raw, provider, projectId, modelOptions);
        } catch (RuntimeException failure) {
            return retryOnce(messages, raw, failure, provider, projectId, modelOptions);
        }
    }

    private String complete(AiProvider provider,
                            List<LlmMessage> messages,
                            AiModelOptions modelOptions,
                            Long projectId) {
        String raw = llmRouter.route(provider).complete(SYSTEM_PROMPT, messages, modelOptions);
        log.info("의사결정 완료: provider={}, model={}, projectId={}, rawLength={}",
                provider, modelOptions.model(), projectId, raw == null ? 0 : raw.length());
        log.debug("의사결정 응답 미리보기: {}", preview(raw));
        return raw;
    }

    /**
     * 형식만 어긋난 응답에 한 번 더 기회를 준다. 모델이 요청은 제대로 읽어놓고 따옴표 하나를 잘못
     * 찍는 일이 실제로 있었다 — 2026-09-07 dev 에서 {@code "reasoning ""} 하나 때문에 제대로 세워진
     * CODE 계획이 통째로 버려졌다. 그 한 글자 때문에 사용자의 요청을 버리는 것은 아깝고, 실패 사유를
     * 그대로 돌려주면 모델은 무엇을 고쳐야 하는지 알고 다시 쓴다.
     *
     * <p>재시도까지 실패하면 <b>던진다.</b> 예전에는 여기서 {@code CHAT} 스텝으로 폴백하면서 모델이
     * 쓴 계획 원문을 {@code instruction} 에 실었는데, 그러면 그 JSON 이 사용자의 질문으로 둔갑해
     * 채팅 에이전트가 그것을 해설하는 답을 내놓았다(실측: "보내주신 내용은 코드 생성(CODE) 파이프라인
     * 실행 계획처럼 보입니다"). CHAT 은 승인이 걸리지 않으므로 태스크는 성공으로 끝나고, 사용자가
     * 요청한 작업은 경고 하나 없이 증발했다. 실패를 실패로 닫아야 호출부가 태스크를 FAILED 로
     * 전이시키고(SSE 로 FE 가 즉시 인지) 사용자도 다시 시도할 수 있다.</p>
     */
    private AgentPlan retryOnce(List<LlmMessage> messages,
                                String failedRaw,
                                RuntimeException failure,
                                AiProvider provider,
                                Long projectId,
                                AiModelOptions modelOptions) {
        log.warn("의사결정 응답 파싱 실패 — 형식 교정을 요청해 1회 재시도합니다. provider={} projectId={} rawPreview={}",
                provider, projectId, preview(failedRaw), failure);

        List<LlmMessage> repairMessages = new ArrayList<>(messages);
        repairMessages.add(new LlmMessage("user", repairPrompt(failedRaw, failure)));

        String raw = complete(provider, repairMessages, modelOptions, projectId);
        try {
            AgentPlan plan = parse(raw, provider, projectId, modelOptions);
            log.info("의사결정 응답 재시도 성공: provider={} projectId={}", provider, projectId);
            return plan;
        } catch (RuntimeException retryFailure) {
            retryFailure.addSuppressed(failure);
            log.warn("의사결정 응답 재시도도 파싱 실패 — 요청을 실패로 닫습니다. provider={} projectId={} rawPreview={}",
                    provider, projectId, preview(raw), retryFailure);
            throw new LlmProviderException(provider.name(), Reason.MALFORMED_RESPONSE, retryFailure);
        }
    }

    /**
     * 교정 요청. 스키마는 이미 시스템 프롬프트에 있으므로 되풀이하지 않고 <b>무엇이 어긋났는지</b>만
     * 덧붙인다 — 모델에 필요한 새 정보는 실패 사유뿐이다. 직전 응답도 함께 보여주되 길면 자른다
     * (계획 JSON 은 길 수 있고, 어디가 틀렸는지 보는 데는 앞부분이면 충분하다).
     */
    private String repairPrompt(String failedRaw, RuntimeException failure) {
        String echo = failedRaw == null || failedRaw.length() <= MAX_REPAIR_ECHO_CHARS
                ? String.valueOf(failedRaw)
                : failedRaw.substring(0, MAX_REPAIR_ECHO_CHARS) + "…(truncated)";
        return """
                [Your previous answer could not be used: %s
                Answer again with ONE JSON object only, in the schema given above — no prose before
                or after it, no markdown fences, no trailing commentary. Every key must be quoted
                exactly once and followed by a colon, and every "agentType" must be one of the types
                listed above, spelled exactly.

                Your previous answer was:
                %s]""".formatted(failure.getMessage(), echo);
    }

    /**
     * 응답을 계획으로 읽는다. 어긋나면 {@link RuntimeException} 을 던져 호출부의 교정 재시도로
     * 넘긴다 — 여기서 삼키면 실패가 성공처럼 보인다.
     */
    private AgentPlan parse(String raw, AiProvider provider, Long projectId, AiModelOptions modelOptions) {
        String json = extractJson(raw);
        if (json == null) {
            throw new IllegalArgumentException(
                    "the answer contains no complete JSON object (no opening brace, or it is never closed)");
        }

        Map<String, Object> map;
        try {
            map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            // getOriginalMessage: 위치 정보("at [Source: REDACTED…]")를 뺀 사유만 — 이 문구가 교정
            // 프롬프트로 모델에 그대로 전달되므로 잡음이 적을수록 좋다.
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }

        // 되묻기: 최상위 "clarification" 이 있으면 steps 대신 CLARIFY 스텝 하나로 만든다. 구조화 질문을
        // 스텝 파라미터에 JSON 문자열로 실어(AgentStep.parameters 는 Map<String,String>) 실행기가 파싱한다.
        Object clarificationRaw = map.get("clarification");
        if (clarificationRaw instanceof Map) {
            ClarificationRequest clarification =
                    objectMapper.convertValue(clarificationRaw, ClarificationRequest.class);
            String clarificationJson;
            try {
                clarificationJson = objectMapper.writeValueAsString(clarification);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("CLARIFY 질문을 직렬화하지 못했습니다", e);
            }
            String reasoning = readReasoning(map);
            log.info("의사결정: CLARIFY(되묻기) inputType={} reasoning={}",
                    clarification.inputType(), reasoning);
            return new AgentPlan(
                    List.of(new AgentStep(AgentType.CLARIFY, Map.of("clarification", clarificationJson))),
                    reasoning, provider, projectId, modelOptions);
        }

        List<AgentStep> steps = readSteps(map.get("steps"));
        String reasoning = readReasoning(map);
        log.info("의사결정 결과: steps={}, reasoning={}", steps.stream().map(AgentStep::agentType).toList(), reasoning);
        return new AgentPlan(steps, reasoning, provider, projectId, modelOptions);
    }

    /**
     * 스텝 목록. 형태가 어긋나면 던져 교정 재시도로 넘긴다.
     *
     * <p>특히 <b>비어 있는 목록과 알 수 없는 {@code agentType} 을 통과시키지 않는다.</b> 예전에는
     * "steps" 키가 없으면 빈 목록으로 떨어져 아무것도 하지 않는 계획이 성공으로 실행됐고, 모르는
     * 유형은 계획 전체를 파싱 실패로 만들어 CHAT 폴백으로 갔다. 둘 다 사용자에게는 "요청했는데
     * 아무 일도 안 일어난다"로 똑같이 보인다. 모델에 무엇이 틀렸는지 알려주고 다시 쓰게 하는 편이
     * 낫다.</p>
     */
    private List<AgentStep> readSteps(Object stepsRaw) {
        if (stepsRaw == null) {
            throw new IllegalArgumentException(
                    "the object has neither a \"steps\" array nor a \"clarification\" object");
        }
        if (!(stepsRaw instanceof List<?> rawList)) {
            throw new IllegalArgumentException("\"steps\" must be an array");
        }
        if (rawList.isEmpty()) {
            throw new IllegalArgumentException("\"steps\" is empty — every request needs at least one step");
        }

        List<AgentStep> steps = new ArrayList<>();
        for (Object element : rawList) {
            if (!(element instanceof Map<?, ?> stepMap)) {
                throw new IllegalArgumentException("every entry of \"steps\" must be an object");
            }
            steps.add(new AgentStep(
                    readAgentType(stepMap.get("agentType")),
                    readParameters(stepMap.get("parameters"))));
        }
        return steps;
    }

    private AgentType readAgentType(Object raw) {
        String name = raw == null ? AgentType.CHAT.name() : String.valueOf(raw).trim().toUpperCase();
        try {
            return AgentType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown \"agentType\": " + name, e);
        }
    }

    /**
     * 파라미터는 문자열 맵이다({@link AgentStep}). 모델이 값에 숫자나 불리언을 넣는 일이 있는데,
     * 예전에는 {@code Map<String,String>} 무검사 캐스팅뿐이라 제네릭 소거로 여기는 통과하고 한참
     * 뒤 스텝을 읽는 곳에서 ClassCastException 으로 터졌다. 스칼라는 여기서 문자열로 바꾸고,
     * 문자열이 될 수 없는 값(객체·배열)은 던져 계획 단계에서 끝낸다.
     */
    private Map<String, String> readParameters(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("\"parameters\" must be an object");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map || value instanceof List) {
                throw new IllegalArgumentException(
                        "\"parameters." + entry.getKey() + "\" must be a string, not an object or array");
            }
            parameters.put(String.valueOf(entry.getKey()), value == null ? "" : String.valueOf(value));
        }
        return parameters;
    }

    private String readReasoning(Map<String, Object> map) {
        Object reasoning = map.get("reasoning");
        return reasoning == null ? "" : String.valueOf(reasoning);
    }

    /** 로그에 실을 만큼만 자른 원문. 잘렸다는 사실을 남겨 "이게 전부인가" 를 묻지 않게 한다. */
    private String preview(String raw) {
        if (raw == null) {
            return "(없음)";
        }
        return raw.length() <= MAX_LOGGED_RAW_CHARS
                ? raw
                : raw.substring(0, MAX_LOGGED_RAW_CHARS) + "…(" + raw.length() + "자 중 앞부분)";
    }

    /**
     * raw 에서 첫 번째로 <b>완결된</b> JSON 객체만 잘라낸다.
     *
     * <p>마지막 {@code '}'} 까지 통째로 자르던 예전 방식은, JSON 뒤에 모델이 덧붙인 산문에 중괄호가
     * 하나라도 있으면 그것까지 삼켜 파싱을 깨뜨렸다(dev 실측 9건이 전부 이 모양이었다 — "작업 계획을
     * 만들었습니다" 류의 인사말이 JSON 과 함께 왔다). 그래서 문자열 리터럴과 이스케이프를 인식하며
     * 중괄호 깊이를 세고, 0 으로 돌아오는 지점에서 끊는다.</p>
     *
     * <p>여는 중괄호가 없거나(모델이 산문만 냈다) 끝내 닫히지 않으면(출력이 잘렸다) {@code null} 을
     * 돌려준다 — 호출부가 교정 재시도로 넘긴다.</p>
     */
    private String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start == -1) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return raw.substring(start, i + 1);
            }
        }
        return null;
    }
}
