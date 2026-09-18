package com.example.dvely.auth.infrastructure.persistence.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.infrastructure.config.EncryptProperties;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 10-3: {@code Cipher}/{@code SecureRandom} 재사용으로 바뀐 뒤에도 <b>암호문 형식이 그대로</b>여야
 * 한다. 이 컨버터는 {@code @Convert} 로 여러 테이블(사용자 GitHub 토큰, 클라우드 자격, 환경변수,
 * 프로비저닝 DB 비밀번호)에 걸려 있어, 형식이 조금이라도 달라지면 이미 저장된 값이 전부 못 읽는 값이
 * 된다 — 코드가 아니라 데이터가 깨지는 종류의 사고라 되돌릴 수도 없다.
 */
class AesEncryptorTest {

    private static final String SECRET = "u10-format-pin-secret";

    /**
     * 이 상수들은 <b>U10 이전 구현</b>(호출마다 {@code Cipher.getInstance} + {@code new SecureRandom})
     * 으로 만든 실제 암호문이다. IV 만 고정해 결정적으로 뽑았고, 그 외 알고리즘·조립 순서는 당시
     * 코드 그대로다. 새 구현이 이걸 읽지 못하면 운영 DB 의 기존 행도 읽지 못한다.
     */
    private static final List<String[]> LEGACY_CIPHERTEXTS = List.of(
            new String[] {"gho_exampletoken1234567890",
                    "AQIDBAUGBwgJCgsMngdI7ir6XhJDRxKzKyVDX+4a8VwZzJ5T2KyCIthZa+QoFQmkpsIuBnIx"},
            new String[] {"",
                    "AQIDBAUGBwgJCgsMP37EZHdh/x1cTJXfy8Q1Pg=="},
            new String[] {"한글 토큰 값 🔐",
                    "AQIDBAUGBwgJCgsMFPq7W/cCH5K1i5pG9G7MgU0IMve4arOkdgA8Q3WGWF6YuKyOyEM="}
    );

    private final AesEncryptor encryptor = newEncryptor(SECRET);

    private static AesEncryptor newEncryptor(String secret) {
        try {
            return new AesEncryptor(new EncryptProperties(secret));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void decryptsCiphertextWrittenByThePreviousImplementation() {
        for (String[] pair : LEGACY_CIPHERTEXTS) {
            assertThat(encryptor.convertToEntityAttribute(pair[1]))
                    .withFailMessage("기존 형식 암호문을 복호화하지 못했습니다: %s", pair[1])
                    .isEqualTo(pair[0]);
        }
    }

    @Test
    void theWireFormatIsStillBase64Of12ByteIvFollowedByCiphertextAndTag() {
        // GCM 태그 16바이트가 뒤에 붙으므로 길이는 항상 12 + 평문길이 + 16 이다. 이 산식이 깨지면
        // IV 길이나 태그 길이가 바뀐 것이고, 그 순간 기존 행은 전부 복호화 불가가 된다.
        String plaintext = "gho_exampletoken1234567890";

        byte[] combined = Base64.getDecoder().decode(encryptor.convertToDatabaseColumn(plaintext));

        assertThat(combined).hasSize(12 + plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void roundTripsAndKeepsEveryCiphertextDistinct() {
        String plaintext = "gho_exampletoken1234567890";

        Set<String> ciphertexts = IntStream.range(0, 50)
                .mapToObj(i -> encryptor.convertToDatabaseColumn(plaintext))
                .peek(c -> assertThat(encryptor.convertToEntityAttribute(c)).isEqualTo(plaintext))
                .collect(java.util.stream.Collectors.toSet());

        // IV 를 매번 새로 뽑는지 확인한다. SecureRandom 을 공유하도록 바꾼 뒤 실수로 IV 까지 재사용하면
        // GCM 에서는 같은 키+IV 조합이 곧 평문 복원 가능성을 뜻해 치명적이다.
        assertThat(ciphertexts).hasSize(50);
    }

    @Test
    void aDifferentSecretCannotDecrypt() {
        String ciphertext = encryptor.convertToDatabaseColumn("gho_exampletoken1234567890");

        assertThat(catchThrowableOf(() -> newEncryptor("some-other-secret").convertToEntityAttribute(ciphertext)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("복호화 실패");
    }

    @Test
    void nullPassesThroughUntouchedInBothDirections() {
        assertThat(encryptor.convertToDatabaseColumn(null)).isNull();
        assertThat(encryptor.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void concurrentCallsDoNotCorruptEachOther() throws Exception {
        // ThreadLocal 로 Cipher 를 들고 있으므로 스레드 간 간섭이 없어야 한다. 하나라도 공유되면
        // GCM 상태가 섞여 복호화 결과가 어긋나거나 예외가 난다.
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Set<String> results = Collections.newSetFromMap(new ConcurrentHashMap<>());
        try {
            for (int i = 0; i < threads; i++) {
                String plaintext = "token-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 200; n++) {
                            String round = encryptor.convertToEntityAttribute(
                                    encryptor.convertToDatabaseColumn(plaintext));
                            if (!plaintext.equals(round)) {
                                results.add(round);
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failure.get()).isNull();
        assertThat(results).isEmpty();
    }

    private static Throwable catchThrowableOf(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
