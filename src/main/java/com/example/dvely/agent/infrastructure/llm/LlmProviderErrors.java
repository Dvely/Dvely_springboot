package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.common.exception.LlmProviderException;
import com.example.dvely.common.exception.LlmProviderException.Reason;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns the HTTP-level failures of the Anthropic, OpenAI and OpenRouter APIs into a single
 * {@link LlmProviderException} the rest of the application can act on.
 *
 * <p>Shared by every client in this package rather than repeated in each: the providers report the
 * same conditions differently — an exhausted balance is a 429 with {@code insufficient_quota} at
 * OpenAI, a 400 mentioning the credit balance at Anthropic, and a 402 at OpenRouter — and that
 * mapping is worth having in exactly one place.</p>
 */
@Slf4j
final class LlmProviderErrors {

    /**
     * Response-body markers that mean "the account is out of credit", not "slow down".
     *
     * <p>Each provider words it differently, and two of them deliver it on a status that means
     * something else entirely — so the body is what decides. Z.ai is the sharpest case: it answers
     * an empty balance with <em>429</em>, which without the marker below reads as a rate limit and
     * is therefore retried, spending the task's whole retry budget on a call that cannot begin to
     * succeed (2026-09-03 실측: {@code {"error":{"code":"1113","message":"Insufficient balance or
     * no resource package. Please recharge."}}} with status 429).</p>
     */
    private static final String[] QUOTA_MARKERS = {
            "insufficient_quota",     // OpenAI
            "credit balance",         // Anthropic
            "insufficient credits",   // OpenRouter
            "insufficient balance",   // Z.ai
            "no resource package"     // Z.ai (잔액이 아니라 패키지 미보유일 때의 표현)
    };

    /** OpenRouter's status for an account that cannot pay for the call. */
    private static final int PAYMENT_REQUIRED = 402;

    private static final int MAX_LOGGED_BODY_CHARS = 500;

    private LlmProviderErrors() {
    }

    /**
     * Fails before the request is built when no key is configured. Sending the call anyway would
     * come back as a 401 that reads like a rejected key rather than an absent one — the same
     * message for a deployment problem and a configuration problem.
     */
    static void requireApiKey(String providerName, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmProviderException(providerName, Reason.MISSING_API_KEY, null);
        }
    }

    /**
     * Calls without retrying — one attempt, translated. Kept for call sites that have no policy to
     * hand and for tests, which must not sleep.
     */
    static <T> T translate(String providerName, Supplier<T> call) {
        return translate(providerName, noRetry(), call);
    }

    /**
     * Calls, translating failures, and tries again while the failure is one that could clear on its
     * own and attempts remain.
     *
     * <p>Retrying here rather than in the orchestrator is deliberate: this is the one place that
     * already knows <em>why</em> a call failed, and the distinction that matters — a 429 that
     * clears versus a 429 that means an empty balance — exists nowhere above it. Above it, in
     * {@code AgentPlanExecutor}, every provider failure closes the task, so a transient blip would
     * otherwise cost a whole run.</p>
     */
    static <T> T translate(String providerName, AiProperties.Retry retry, Supplier<T> call) {
        int maxAttempts = Math.max(1, retry.getMaxAttempts());
        long delayMs = Math.max(0, retry.getInitialDelayMs());
        LlmProviderException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return attempt(providerName, call);
            } catch (LlmProviderException e) {
                // A non-retryable reason is the answer, not a setback — surfacing it now is what
                // keeps "크레딧이 부족합니다" from arriving three delays late.
                if (!e.retryable() || attempt == maxAttempts) {
                    throw e;
                }
                lastFailure = e;
                long waitMs = waitMillis(e, delayMs, retry.getMaxDelayMs());
                log.warn("LLM 제공자 재시도 대기: provider={} reason={} attempt={}/{} waitMs={}",
                        providerName, e.reason(), attempt, maxAttempts, waitMs);
                sleep(waitMs);
                delayMs = Math.min(Math.max(delayMs * 2, 1), Math.max(0, retry.getMaxDelayMs()));
            }
        }
        // Unreachable: the loop either returns or throws on its final attempt.
        throw lastFailure;
    }

    private static AiProperties.Retry noRetry() {
        AiProperties.Retry retry = new AiProperties.Retry();
        retry.setMaxAttempts(1);
        return retry;
    }

    /**
     * How long to wait before the next attempt. The provider's own {@code Retry-After} wins when it
     * sends one — it knows when the limit clears and the backoff schedule only guesses — but it is
     * still capped, since a gateway asking for a minute would otherwise stall the task past any
     * timeout the caller is waiting on.
     */
    private static long waitMillis(LlmProviderException failure, long backoffMs, long maxDelayMs) {
        long cap = Math.max(0, maxDelayMs);
        Long retryAfterMs = retryAfterMillis(failure);
        long chosen = retryAfterMs == null ? backoffMs : retryAfterMs;
        return Math.min(Math.max(chosen, 0), cap);
    }

    /** Parses {@code Retry-After}, which HTTP allows as either seconds or an HTTP date. */
    private static Long retryAfterMillis(LlmProviderException failure) {
        if (!(failure.getCause() instanceof HttpStatusCodeException http)) {
            return null;
        }
        try {
            String header = http.getResponseHeaders() == null
                    ? null
                    : http.getResponseHeaders().getFirst("Retry-After");
            if (header == null || header.isBlank()) {
                return null;
            }
            String value = header.trim();
            if (value.chars().allMatch(Character::isDigit)) {
                return Long.parseLong(value) * 1_000;
            }
            long millis = java.time.Duration.between(
                    java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC),
                    java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            ).toMillis();
            return millis > 0 ? millis : 0L;
        } catch (RuntimeException e) {
            // A malformed Retry-After is the provider's problem, not a reason to fail the call —
            // fall back to the backoff schedule.
            return null;
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // The task was cancelled while waiting. Restore the flag and stop retrying rather than
            // swallowing it — an agent run that was cancelled must not keep calling the provider.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 제공자 재시도 대기 중 중단되었습니다", e);
        }
    }

    private static <T> T attempt(String providerName, Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpStatusCodeException e) {
            Reason reason = reasonOf(e);
            // The body is logged (truncated) because it carries the provider's own explanation,
            // which is what an operator needs; it is never put in the user-facing message, which
            // stays a fixed sentence per reason.
            log.error("LLM 제공자 호출 실패: provider={} status={} reason={} body={}",
                    providerName, e.getStatusCode().value(), reason, truncatedBody(e));
            throw new LlmProviderException(providerName, reason, e);
        } catch (ResourceAccessException e) {
            // Connect/read timeouts and DNS failures — the provider was never reached.
            log.error("LLM 제공자 연결 실패: provider={} reason={}", providerName, e.getMessage());
            throw new LlmProviderException(providerName, Reason.UPSTREAM_ERROR, e);
        }
    }

    private static Reason reasonOf(HttpStatusCodeException e) {
        String body = safeBody(e).toLowerCase();
        // Checked before the status codes: Anthropic reports an empty balance as a 400, which
        // would otherwise be indistinguishable from a malformed request.
        for (String marker : QUOTA_MARKERS) {
            if (body.contains(marker)) {
                return Reason.QUOTA_EXCEEDED;
            }
        }
        int status = e.getStatusCode().value();
        // OpenRouter answers an empty balance with 402 and no marker in the body; without this it
        // would fall through to UPSTREAM_ERROR, which is retryable — three more paid-for attempts
        // at a call that cannot start succeeding.
        if (status == PAYMENT_REQUIRED) {
            return Reason.QUOTA_EXCEEDED;
        }
        if (status == 401 || status == 403) {
            return Reason.AUTH_FAILED;
        }
        if (status == 429) {
            return Reason.RATE_LIMITED;
        }
        return Reason.UPSTREAM_ERROR;
    }

    private static String safeBody(HttpStatusCodeException e) {
        try {
            String body = e.getResponseBodyAsString();
            return body == null ? "" : body;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String truncatedBody(HttpStatusCodeException e) {
        String body = safeBody(e);
        return body.length() <= MAX_LOGGED_BODY_CHARS
                ? body
                : body.substring(0, MAX_LOGGED_BODY_CHARS) + "...";
    }
}
