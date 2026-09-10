package com.example.dvely.auth.infrastructure.persistence.converter;

import com.example.dvely.auth.infrastructure.config.EncryptProperties;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@Converter
public class AesEncryptor implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    /**
     * {@code SecureRandom} 은 스레드 안전하고 내부 엔트로피 풀을 재사용하므로 인스턴스를 공유한다.
     * 암호화마다 새로 만들면 그때마다 시딩 비용을 다시 낸다 — 이 컨버터는 {@code UserEntity} 의
     * GitHub 토큰 두 개에 걸려 있어 호출 빈도가 낮지 않다.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * {@code Cipher} 는 스레드 안전하지 않아 공유할 수 없다. 반대로 {@code Cipher.getInstance} 는
     * 매번 JCA provider 탐색을 도는 비용이 있다. 스레드당 하나씩 들고 재사용해 둘 다 피한다 —
     * 매 호출 앞머리의 {@code init} 이 IV/모드까지 완전히 다시 세팅하므로 이전 호출 상태가 남지 않는다.
     * (이 앱은 가상 스레드를 쓰지 않는다. 톰캣 스레드 풀이 유한하므로 보관되는 인스턴스 수도 유한하다.)
     */
    private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES 알고리즘을 찾을 수 없습니다", e);
        }
    });

    private final SecretKeySpec keySpec;

    public AesEncryptor(EncryptProperties encryptProperties) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(encryptProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.keySpec = new SecretKeySpec(hash, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = CIPHER.get();
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 원인 예외에 평문이 실려 나갈 여지를 주지 않으려고 메시지는 고정 문자열만 쓴다.
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

            Cipher cipher = CIPHER.get();
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));

            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("복호화 실패", e);
        }
    }
}
