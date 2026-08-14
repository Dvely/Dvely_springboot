package com.example.dvely.common.exception;

/**
 * An LLM provider could not be called, or refused the call for a reason that is about the account
 * rather than the request: no key configured, a rejected key, exhausted credit, a rate limit, or an
 * outage.
 *
 * <p>These used to surface as whatever the HTTP client threw. On the synchronous path that meant a
 * bare 500 from the catch-all handler; on the agent's asynchronous path it was swallowed by
 * CodeAgentService's build-failure branch and reported as "프로젝트 빌드가 완료되지 않았습니다",
 * a diagnosis about the user's project for a problem that has nothing to do with it — and then
 * retried three times, since a build failure is retryable and a missing credit balance is not.</p>
 *
 * <p>The provider is carried as a display name rather than the agent module's {@code AiProvider}
 * enum: this lives in {@code common} precisely because more than one module calls these APIs, and
 * it should not drag a dependency on the agent module along with it.</p>
 */
public class LlmProviderException extends RuntimeException {

    public enum Reason {
        MISSING_API_KEY,
        AUTH_FAILED,
        QUOTA_EXCEEDED,
        RATE_LIMITED,
        UPSTREAM_ERROR
    }

    private final String providerName;
    private final Reason reason;

    public LlmProviderException(String providerName, Reason reason, Throwable cause) {
        super(userMessage(providerName, reason), cause);
        this.providerName = providerName;
        this.reason = reason;
    }

    public String providerName() {
        return providerName;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * Whether trying the exact same call again could plausibly succeed. A rate limit clears and an
     * outage ends; a missing key, a rejected key, and an empty credit balance do not resolve
     * themselves between retries, so retrying those only delays the message the user needs to see.
     */
    public boolean retryable() {
        return reason == Reason.RATE_LIMITED || reason == Reason.UPSTREAM_ERROR;
    }

    private static String userMessage(String providerName, Reason reason) {
        return switch (reason) {
            case MISSING_API_KEY -> providerName + " API 키가 설정되어 있지 않습니다. "
                    + "다른 AI 제공자를 선택하거나 관리자에게 문의해주세요.";
            case AUTH_FAILED -> providerName + " API 인증에 실패했습니다. "
                    + "다른 AI 제공자를 선택하거나 관리자에게 문의해주세요.";
            case QUOTA_EXCEEDED -> providerName + " 크레딧이 부족해 요청을 처리할 수 없습니다. "
                    + "다른 AI 제공자를 선택하거나 결제 상태를 확인해주세요.";
            case RATE_LIMITED -> providerName + " 요청량 한도를 초과했습니다. 잠시 후 다시 시도해주세요.";
            case UPSTREAM_ERROR -> providerName + " 호출에 실패했습니다. 잠시 후 다시 시도해주세요.";
        };
    }
}
