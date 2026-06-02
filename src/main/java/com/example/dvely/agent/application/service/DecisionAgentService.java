package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.llm.LlmRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionAgentService {

    private final LlmRouter    llmRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a decision-making agent for Dvely, an automated web project deployment platform.
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

            4. CHAT — Anything that does not fit CODE, DEPLOY, or DOMAIN_BIND.
               Parameters:
               - "instruction": a clear restatement of the user's question or request

            Rules:
            - A single message may contain multiple intents — include all of them as separate steps.
            - Order the steps by logical execution sequence (e.g. CODE before DEPLOY).
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
        String prompt = projectId != null
                ? userMessage + "\n\n[Context: This is a modification request for an existing project (projectId=" + projectId + "). Do NOT scaffold a new project.]"
                : userMessage;
        List<LlmMessage> messages = List.of(new LlmMessage("user", prompt));
        String raw = llmRouter.route(provider).complete(SYSTEM_PROMPT, messages);
        log.info("의사결정 완료: provider={}, projectId={}, raw={}", provider, projectId, raw);
        return parse(raw, provider, projectId);
    }

    @SuppressWarnings("unchecked")
    private AgentPlan parse(String raw, AiProvider provider, Long projectId) {
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
            return new AgentPlan(steps, reasoning, provider, projectId);

        } catch (Exception e) {
            log.warn("AgentPlan 파싱 실패, CHAT 으로 폴백: raw={}", raw, e);
            return new AgentPlan(
                    List.of(new AgentStep(AgentType.CHAT, Map.of("instruction", raw))),
                    "parsing failed",
                    provider,
                    projectId
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
