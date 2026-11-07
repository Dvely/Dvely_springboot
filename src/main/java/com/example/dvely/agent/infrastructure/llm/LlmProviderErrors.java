package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.common.exception.LlmProviderException;
import com.example.dvely.common.exception.LlmProviderException.Reason;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

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

    /** 원인 사슬을 따라갈 최대 깊이. 순환하는 사슬에서 멈추기 위한 상한이다. */
    private static final int MAX_CAUSE_DEPTH = 10;

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
                // 요청이 이미 전달된 뒤의 실패(읽기 타임아웃, 응답을 받다 끊김)는 자동으로 다시 보내지 않는다(#620).
                // 제공자는 생성 중이었으므로 다시 보내면 같은 생성을 처음부터 또 시키는 것이라 BYOK 사용자 키로
                // 과금이 겹칠 수 있고, 한 라운드가 최악 180초 × 3 으로 늘어난다. 응답을 못 받았으니 usage 도 없어
                // 태스크 토큰 예산에도 잡히지 않는다. 사용자는 실패 메시지를 보고 다시 시도할 수 있다(retryable 은 그대로).
                if (requestWasDelivered(e.getCause())) {
                    log.warn("LLM 제공자 응답을 받지 못함(요청은 전달됨) — 자동 재시도하지 않음: provider={} attempt={}/{}",
                            providerName, attempt, maxAttempts);
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
            // I/O failures while sending. Two kinds that look alike here but differ in what already
            // happened: connect timeouts, refused connections and DNS failures never reached the
            // provider; a read timeout means the request was delivered and the model was generating
            // when we stopped waiting (#620). Both become UPSTREAM_ERROR for the user; translate()
            // decides whether to send again.
            log.error("LLM 제공자 {}: provider={} reason={}",
                    isReadTimeout(e) ? "응답 시간 초과(요청은 전달됨)" : "연결 실패",
                    providerName, e.getMessage());
            throw new LlmProviderException(providerName, Reason.UPSTREAM_ERROR, e);
        } catch (RestClientException e) {
            // I/O failure while reading the response body (#620). This is where a read timeout of a
            // POST actually lands — measured: SimpleClientHttpRequestFactory + RestClient reports
            // "Read timed out" on a POST as RestClientException("Error while extracting response")
            // with the SocketTimeoutException as its cause, not as ResourceAccessException (a GET
            // gets the latter). Every LLM call here is a POST, so this escaped untranslated and the
            // CODE agent's catch-all read it as a build failure ("프로젝트 빌드가 완료되지 않았습니다")
            // and retried the whole task. Only I/O causes are taken; a body that does not parse is
            // still rethrown as before.
            if (!hasIoCause(e)) {
                throw e;
            }
            log.error("LLM 제공자 {}: provider={} reason={}",
                    isReadTimeout(e) ? "응답 시간 초과(요청은 전달됨)" : "응답 수신 실패(요청은 전달됨)",
                    providerName, rootMessage(e));
            throw new LlmProviderException(providerName, Reason.UPSTREAM_ERROR, e);
        }
    }

    /**
     * 요청이 제공자에게 전달된 뒤에 실패했는가(#620). 읽기 타임아웃이거나, 응답 본문을 읽다가 I/O 로 끊긴 경우다.
     * 이때 다시 보내면 제공자는 같은 생성을 한 번 더 한다.
     */
    static boolean requestWasDelivered(Throwable failure) {
        if (isReadTimeout(failure)) {
            return true;
        }
        // 응답 추출 단계의 실패는 ResourceAccessException 도 상태 코드 예외도 아닌 RestClientException 이다.
        return failure instanceof RestClientException
                && !(failure instanceof ResourceAccessException)
                && !(failure instanceof HttpStatusCodeException)
                && hasIoCause(failure);
    }

    /**
     * 원인 사슬에 I/O 실패가 있는가. 본문 변환 실패({@link HttpMessageConversionException})를 먼저 만나면 아니다 —
     * Jackson 2 의 파싱 예외는 {@code IOException} 의 하위라, 이 구분이 없으면 잘못된 JSON 이 네트워크 실패로 읽힌다.
     */
    private static boolean hasIoCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof HttpMessageConversionException) {
                return false;
            }
            if (current instanceof IOException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current.getCause() != null && current.getCause() != current && depth < MAX_CAUSE_DEPTH; depth++) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    /**
     * 요청을 보낸 뒤 응답을 기다리다 끊긴 것인가(#620). 연결 단계의 타임아웃과 구별한다.
     *
     * <p>JDK 는 두 경우 모두 {@link SocketTimeoutException} 을 던지고 메시지로만 가른다
     * ({@code "Connect timed out"} / {@code "Read timed out"}). 그래서 원인 사슬에서 그 예외를 찾아 메시지에
     * "connect" 가 없으면 읽기 타임아웃으로 본다. 메시지가 비어 있거나 낯선 경우도 읽기 쪽으로 판정한다 —
     * 틀려도 재시도를 한 번 덜 할 뿐이고, 반대로 틀리면 생성을 중복으로 시킨다.</p>
     */
    static boolean isReadTimeout(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                String message = current.getMessage();
                return message == null || !message.toLowerCase(Locale.ROOT).contains("connect");
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
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
