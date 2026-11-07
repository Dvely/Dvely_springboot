package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dvely.agent.infrastructure.config.AiProperties;
import com.example.dvely.common.exception.LlmProviderException;
import com.example.dvely.common.exception.LlmProviderException.Reason;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class LlmProviderErrorsTest {

    private static final String PROVIDER = "OpenAI";

    @Test
    void aMissingApiKeyFailsBeforeTheRequestIsEverSent() {
        assertThatThrownBy(() -> LlmProviderErrors.requireApiKey(PROVIDER, "  "))
                .isInstanceOfSatisfying(LlmProviderException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.MISSING_API_KEY);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("API 키가 설정되어 있지 않습니다");
                });
    }

    @Test
    void aConfiguredKeyPassesThrough() {
        LlmProviderErrors.requireApiKey(PROVIDER, "sk-configured");
    }

    @Test
    void openRouterReportsAnEmptyBalanceAsA402WithNoMarkerInTheBody() {
        LlmProviderException exception = translated(
                HttpClientErrorException.create(HttpStatus.PAYMENT_REQUIRED, "Payment Required",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"message\":\"Requires more credits\"}}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
        );

        // Without the status check this falls through to UPSTREAM_ERROR, which is retryable — and
        // the task would spend its whole retry budget on a call that cannot start succeeding.
        assertThat(exception.reason()).isEqualTo(Reason.QUOTA_EXCEEDED);
        assertThat(exception.retryable()).isFalse();
    }

    @Test
    void zaiReportsAnEmptyBalanceOnA429ThatWouldOtherwiseReadAsARateLimit() {
        // 2026-09-03 실측: base-url 을 Z.ai 로 돌린 배포에서 잔액이 없으면 이 응답이 온다.
        // 상태코드만 보면 RATE_LIMITED(재시도 가능)라, 마커가 없으면 성공할 수 없는 호출에
        // 태스크 재시도 예산을 전부 태운다 — #85 에서 고친 것과 같은 종류의 낭비다.
        LlmProviderException exception = translated(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY,
                        ("{\"error\":{\"code\":\"1113\",\"message\":\"Insufficient balance or no "
                                + "resource package. Please recharge.\"}}").getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
        );

        assertThat(exception.reason()).isEqualTo(Reason.QUOTA_EXCEEDED);
        assertThat(exception.retryable()).isFalse();
        assertThat(exception.getMessage()).contains("크레딧이 부족");
    }

    @Test
    void recognisesOpenRoutersInsufficientCreditsWording() {
        LlmProviderException exception = translated(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"message\":\"Insufficient credits\"}}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
        );

        assertThat(exception.reason()).isEqualTo(Reason.QUOTA_EXCEEDED);
    }

    @Test
    void openAiReportsAnExhaustedBalanceAsA429WithInsufficientQuota() {
        LlmProviderException exception = translated(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"code\":\"insufficient_quota\"}}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
        );

        // Not RATE_LIMITED despite the 429: no amount of waiting refills the balance, so this must
        // not be treated as retryable.
        assertThat(exception.reason()).isEqualTo(Reason.QUOTA_EXCEEDED);
        assertThat(exception.retryable()).isFalse();
        assertThat(exception.getMessage()).contains("크레딧이 부족");
    }

    @Test
    void anthropicReportsAnExhaustedBalanceAsA400AboutTheCreditBalance() {
        LlmProviderException exception = translated(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY,
                        ("{\"error\":{\"message\":\"Your credit balance is too low to access "
                                + "the Anthropic API\"}}").getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
        );

        // A 400 would otherwise read as a malformed request — a bug report against our own payload
        // for what is actually a billing state.
        assertThat(exception.reason()).isEqualTo(Reason.QUOTA_EXCEEDED);
    }

    @ParameterizedTest
    @CsvSource({"401,AUTH_FAILED", "403,AUTH_FAILED", "429,RATE_LIMITED", "400,UPSTREAM_ERROR"})
    void mapsStatusCodesWithoutQuotaMarkersByStatusAlone(int status, Reason expected) {
        LlmProviderException exception = translated(
                HttpClientErrorException.create(HttpStatus.valueOf(status), "error",
                        HttpHeaders.EMPTY, "{\"error\":{}}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)
        );

        assertThat(exception.reason()).isEqualTo(expected);
    }

    @Test
    void aRateLimitIsRetryableUnlikeAnAuthOrQuotaFailure() {
        LlmProviderException exception = translated(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"
                                .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
        );

        assertThat(exception.reason()).isEqualTo(Reason.RATE_LIMITED);
        assertThat(exception.retryable()).isTrue();
    }

    @Test
    void treatsAProviderOutageAsRetryable() {
        LlmProviderException exception = translated(
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        assertThat(exception.reason()).isEqualTo(Reason.UPSTREAM_ERROR);
        assertThat(exception.retryable()).isTrue();
    }

    @Test
    void treatsAConnectionFailureAsAProviderOutageRatherThanAnInternalError() {
        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, () -> {
            throw new ResourceAccessException("connect timed out");
        }))
                .isInstanceOfSatisfying(LlmProviderException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.UPSTREAM_ERROR));
    }

    @Test
    void returnsTheCallResultWhenNothingFails() {
        assertThat(LlmProviderErrors.translate(PROVIDER, () -> "응답")).isEqualTo("응답");
    }

    // ── 재시도 ──────────────────────────────────────────────────────────────

    @Test
    void retriesARateLimitAndReturnsTheAnswerOnceItClears() {
        // AgentPlanExecutor 는 제공자 예외를 받으면 재시도 없이 태스크를 닫는다. 그래서 일시적인
        // 429 한 번이 진행 중이던 작업 전체를 날렸다 — GLM 무료 티어에서는 드문 일이 아니다.
        AtomicInteger calls = new AtomicInteger();

        String answer = LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            if (calls.incrementAndGet() < 3) {
                throw rateLimit();
            }
            return "ok";
        });

        assertThat(answer).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryAnEmptyBalanceBecauseNoAmountOfWaitingRefillsIt() {
        // 재시도해도 소용없을 뿐 아니라, 사용자가 봐야 할 안내를 지연 몇 번만큼 늦춘다.
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            calls.incrementAndGet();
            throw quotaExceeded();
        })).isInstanceOfSatisfying(LlmProviderException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.QUOTA_EXCEEDED));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void doesNotRetryARejectedKey() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                    HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        })).isInstanceOfSatisfying(LlmProviderException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.AUTH_FAILED));

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void givesUpAfterTheConfiguredAttemptsAndReportsTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            calls.incrementAndGet();
            throw rateLimit();
        })).isInstanceOfSatisfying(LlmProviderException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED));

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void oneAttemptMeansNoRetrying() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(1), () -> {
            calls.incrementAndGet();
            throw rateLimit();
        })).isInstanceOf(LlmProviderException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesAnUpstreamOutageToo() {
        AtomicInteger calls = new AtomicInteger();

        String answer = LlmProviderErrors.translate(PROVIDER, fastRetry(2), () -> {
            if (calls.incrementAndGet() < 2) {
                throw new ResourceAccessException("connect timed out");
            }
            return "ok";
        });

        assertThat(answer).isEqualTo("ok");
    }

    @Test
    void theDefaultPolicyRetriesButStaysBounded() {
        // 기본값이 곧 운영 동작이다. CODE 에이전트는 라운드마다 이 예산을 쓰므로 상한이 있어야 한다.
        AiProperties.Retry defaults = new AiProperties().getRetry();

        assertThat(defaults.getMaxAttempts()).isGreaterThan(1).isLessThanOrEqualTo(5);
        assertThat(defaults.getMaxDelayMs()).isPositive().isLessThanOrEqualTo(30_000);
        assertThat(defaults.getInitialDelayMs()).isPositive()
                .isLessThanOrEqualTo(defaults.getMaxDelayMs());
    }

    @Test
    void aSingleArgumentCallDoesNotRetryAtAll() {
        // 테스트와 정책이 없는 호출부가 쓰는 경로 — 잠들면 안 된다.
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, () -> {
            calls.incrementAndGet();
            throw rateLimit();
        })).isInstanceOf(LlmProviderException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void capsRetryAfterSoAGatewayCannotStallTheTask() {
        // 제공자가 "1시간 뒤에 오라"고 해도 그대로 기다리면 호출자의 타임아웃을 넘겨버린다.
        // Retry-After 를 존중하되 max-delay-ms 로 자른다.
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "3600");
        AiProperties.Retry retry = fastRetry(2);
        retry.setMaxDelayMs(50);
        AtomicInteger calls = new AtomicInteger();

        long startedAt = System.currentTimeMillis();
        String answer = LlmProviderErrors.translate(PROVIDER, retry, () -> {
            if (calls.incrementAndGet() < 2) {
                throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        headers, new byte[0], StandardCharsets.UTF_8);
            }
            return "ok";
        });

        assertThat(answer).isEqualTo("ok");
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(3_000);
    }

    // ── 읽기 타임아웃은 자동으로 다시 보내지 않는다(#620) ─────────────────────────

    @Test
    void aReadTimeoutIsNotSentAgainBecauseTheProviderWasAlreadyGenerating() {
        AtomicInteger calls = new AtomicInteger();
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
                calls.incrementAndGet();
                throw readTimeout();
            })).isInstanceOfSatisfying(LlmProviderException.class, e -> {
                assertThat(e.reason()).isEqualTo(Reason.UPSTREAM_ERROR);
                // 사용자는 여전히 다시 시도할 수 있다 — 자동 재전송만 하지 않는다.
                assertThat(e.retryable()).isTrue();
            });
        } finally {
            releaseLogs(logs);
        }

        assertThat(calls.get()).isEqualTo(1);
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("재시도 대기"));
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("응답 시간 초과"));
    }

    @Test
    void aConnectTimeoutIsStillRetriedBecauseNothingWasSent() {
        AtomicInteger calls = new AtomicInteger();

        String answer = LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ResourceAccessException("I/O error on POST request: Connect timed out",
                        new SocketTimeoutException("Connect timed out"));
            }
            return "ok";
        });

        assertThat(answer).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void aRefusedConnectionOrUnknownHostIsStillRetried() {
        AtomicInteger calls = new AtomicInteger();

        String answer = LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                throw new ResourceAccessException("Connection refused", new ConnectException("Connection refused"));
            }
            if (call == 2) {
                throw new ResourceAccessException("api.openai.com", new UnknownHostException("api.openai.com"));
            }
            return "ok";
        });

        assertThat(answer).isEqualTo("ok");
    }

    @Test
    void aPostReadTimeoutIsTranslatedInsteadOfEscapingAsARawClientException() {
        // 실측: RestClient + SimpleClientHttpRequestFactory 에서 POST 의 읽기 타임아웃은 ResourceAccessException 이
        // 아니라 "Error while extracting response" 로 온다(GET 만 ResourceAccessException). LLM 호출은 전부 POST 라
        // 예전에는 번역되지 않고 빠져나가, CODE 에이전트가 빌드 실패로 읽고 태스크를 재시도했다.
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            calls.incrementAndGet();
            throw new RestClientException(
                    "Error while extracting response for type [java.lang.String] and content type [application/octet-stream]",
                    new SocketTimeoutException("Read timed out"));
        })).isInstanceOfSatisfying(LlmProviderException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.UPSTREAM_ERROR);
            assertThat(e.getMessage()).contains("호출에 실패했습니다");
        });

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aConnectionCutWhileReadingTheResponseIsNotSentAgainEither() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            calls.incrementAndGet();
            throw new RestClientException("Error while extracting response", new SocketException("Connection reset"));
        })).isInstanceOf(LlmProviderException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aBodyThatDoesNotParseIsLeftAsItWas() {
        // Jackson 2 의 파싱 예외는 IOException 의 하위다. 변환 실패까지 I/O 실패로 읽으면 안 된다.
        RestClientException parseFailure = new RestClientException("Error while extracting response",
                new HttpMessageNotReadableException("JSON parse error",
                        new IOException("Unexpected character"), new MockHttpInputMessage(new byte[0])));

        assertThatThrownBy(() -> LlmProviderErrors.translate(PROVIDER, fastRetry(3), () -> {
            throw parseFailure;
        })).isSameAs(parseFailure);
    }

    @Test
    void knowsWhenTheRequestHadAlreadyBeenDelivered() {
        assertThat(LlmProviderErrors.requestWasDelivered(readTimeout())).isTrue();
        assertThat(LlmProviderErrors.requestWasDelivered(
                new RestClientException("extracting", new SocketException("Connection reset")))).isTrue();
        assertThat(LlmProviderErrors.requestWasDelivered(
                new ResourceAccessException("refused", new ConnectException("Connection refused")))).isFalse();
        assertThat(LlmProviderErrors.requestWasDelivered(
                new ResourceAccessException("Connect timed out", new SocketTimeoutException("Connect timed out")))).isFalse();
        assertThat(LlmProviderErrors.requestWasDelivered(rateLimit())).isFalse();
        assertThat(LlmProviderErrors.requestWasDelivered(null)).isFalse();
    }

    @Test
    void classifiesTimeoutsByWhatAlreadyHappened() {
        assertThat(LlmProviderErrors.isReadTimeout(readTimeout())).isTrue();
        // JDK HttpClient 쪽 예외도 같은 기준으로 가른다.
        assertThat(LlmProviderErrors.isReadTimeout(new HttpTimeoutException("request timed out"))).isTrue();
        assertThat(LlmProviderErrors.isReadTimeout(new HttpConnectTimeoutException("HTTP connect timed out"))).isFalse();
        assertThat(LlmProviderErrors.isReadTimeout(new SocketTimeoutException("Connect timed out"))).isFalse();
        // 메시지가 없으면 읽기 쪽으로 본다 — 틀려도 재시도를 한 번 덜 할 뿐이다.
        assertThat(LlmProviderErrors.isReadTimeout(new SocketTimeoutException())).isTrue();
        assertThat(LlmProviderErrors.isReadTimeout(new ResourceAccessException("connect timed out"))).isFalse();
        assertThat(LlmProviderErrors.isReadTimeout(null)).isFalse();
    }

    private ResourceAccessException readTimeout() {
        return new ResourceAccessException(
                "I/O error on POST request for \"https://api.openai.com/v1/chat/completions\": Read timed out",
                new SocketTimeoutException("Read timed out"));
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmProviderErrors.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void releaseLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(LlmProviderErrors.class)).detachAppender(appender);
    }

    /** 지연을 0 으로 눌러 실제로 잠들지 않게 한다. */
    private AiProperties.Retry fastRetry(int maxAttempts) {
        AiProperties.Retry retry = new AiProperties.Retry();
        retry.setMaxAttempts(maxAttempts);
        retry.setInitialDelayMs(0);
        retry.setMaxDelayMs(0);
        return retry;
    }

    private HttpClientErrorException rateLimit() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                HttpHeaders.EMPTY,
                "{\"error\":{\"code\":\"1305\",\"message\":\"overloaded\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private HttpClientErrorException quotaExceeded() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                HttpHeaders.EMPTY,
                "{\"error\":{\"code\":\"1113\",\"message\":\"Insufficient balance\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private LlmProviderException translated(RuntimeException thrown) {
        try {
            LlmProviderErrors.translate(PROVIDER, () -> {
                throw thrown;
            });
        } catch (LlmProviderException e) {
            return e;
        }
        throw new AssertionError("LlmProviderException 이 발생하지 않았습니다");
    }
}
