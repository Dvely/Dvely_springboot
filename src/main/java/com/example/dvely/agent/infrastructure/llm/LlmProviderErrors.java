package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.common.exception.LlmProviderException;
import com.example.dvely.common.exception.LlmProviderException.Reason;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Turns the HTTP-level failures of the Anthropic and OpenAI APIs into a single
 * {@link LlmProviderException} the rest of the application can act on.
 *
 * <p>Shared by all four clients in this package rather than repeated in each: the two providers
 * report the same conditions differently — an exhausted balance is a 429 with
 * {@code insufficient_quota} at OpenAI and a 400 mentioning the credit balance at Anthropic — and
 * that mapping is worth having in exactly one place.</p>
 */
@Slf4j
final class LlmProviderErrors {

    /** Response-body markers that mean "the account is out of credit", not "slow down". */
    private static final String[] QUOTA_MARKERS = {"insufficient_quota", "credit balance"};

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

    static <T> T translate(String providerName, Supplier<T> call) {
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
