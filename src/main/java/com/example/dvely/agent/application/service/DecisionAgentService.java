package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionAgentService {

    private final LlmRouter    llmRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a decision-making agent for Qeploy, an automated web project deployment platform.
            Analyze the user's message, identify ALL intents, and return them as an ordered list of steps.

            For each step, write the "instruction" as a complete, self-contained natural language request
            that the downstream specialist agent can act on independently — as if the original message
            did not exist. Do NOT copy fragments verbatim; instead synthesize a clear, actionable
            directive from the full context of the user's message.

            Agent types and their instruction-writing rules:

            1. CODE — User wants to create, modify, fix, or review code.
               A preview of the result is ALWAYS provided automatically after CODE completes —
               do NOT add a separate DEPLOY step just because the user wants to "see" or "preview" the result.
               Parameters:
               - "instruction": a complete coding task description written for a code-editing AI
                 (include what to change, where, and the expected outcome)
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

            3. DOMAIN_BIND — User wants to connect or configure a custom domain.
               Parameters:
               - "domain": the domain value — use one of two formats:
                 * Label only (no dots) for a managed subdomain (e.g. "my-app" → my-app.qeploy.com)
                 * Full hostname (with dots) for a custom domain (e.g. "www.mysite.com")
                 * Empty string if no domain is mentioned
               - "instruction": a complete domain-configuration directive written for a domain agent

            4. CHAT — Anything that does not fit CODE, DEPLOY, DOMAIN_BIND, INFRA_OPERATE, or RUNTIME_SETUP.
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

            Rules:
            - A single message may contain multiple intents — include all of them as separate steps.
            - Order the steps by logical execution sequence (e.g. CODE before DEPLOY).
            - When the user wants a backend/server/API/DB, put RUNTIME_SETUP BEFORE the CODE step.
            - Each step's instruction must be fully understandable on its own, without access to the
              original user message.
            - Respond ONLY with a valid JSON object. No markdown, no code blocks, no extra text.

            Response format:
            {
              "steps": [
                {
                  "agentType": "CODE",
                  "parameters": {
                    "instruction": "...",
                    "targetFile": "..."
                  }
                },
                {
                  "agentType": "DEPLOY",
                  "parameters": {
                    "instruction": "...",
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
        List<LlmMessage> messages = new ArrayList<>(conversation);
        if (projectId != null) {
            messages.add(new LlmMessage(
                    "user",
                    "[Project context: projectId=" + projectId
                            + ". Treat the latest user request as a modification of this existing project. "
                            + "Do not scaffold a new project.]"
            ));
        }
        String raw = llmRouter.route(provider).complete(SYSTEM_PROMPT, messages, modelOptions);
        log.info("의사결정 완료: provider={}, model={}, projectId={}, raw={}",
                provider, modelOptions.model(), projectId, raw);
        return parse(raw, provider, projectId, modelOptions);
    }

    @SuppressWarnings("unchecked")
    private AgentPlan parse(String raw, AiProvider provider, Long projectId, AiModelOptions modelOptions) {
        try {
            String json = extractJson(raw);
            Map<String, Object> map = objectMapper.readValue(json, Map.class);

            List<Map<String, Object>> stepsRaw =
                    (List<Map<String, Object>>) map.getOrDefault("steps", List.of());

            List<AgentStep> steps = stepsRaw.stream()
                    .map(s -> {
                        AgentType type = AgentType.valueOf(
                                ((String) s.getOrDefault("agentType", "CHAT")).toUpperCase()
                        );
                        Map<String, String> params =
                                (Map<String, String>) s.getOrDefault("parameters", Map.of());
                        return new AgentStep(type, params);
                    })
                    .toList();

            String reasoning = (String) map.getOrDefault("reasoning", "");
            log.info("의사결정 결과: steps={}, reasoning={}", steps.stream().map(AgentStep::agentType).toList(), reasoning);
            return new AgentPlan(steps, reasoning, provider, projectId, modelOptions);

        } catch (Exception e) {
            log.warn("AgentPlan 파싱 실패, CHAT 으로 폴백: raw={}", raw, e);
            return new AgentPlan(
                    List.of(new AgentStep(AgentType.CHAT, Map.of("instruction", raw))),
                    "parsing failed",
                    provider,
                    projectId,
                    modelOptions
            );
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start == -1 || end == -1 || start > end) return raw;
        return raw.substring(start, end + 1);
    }
}
