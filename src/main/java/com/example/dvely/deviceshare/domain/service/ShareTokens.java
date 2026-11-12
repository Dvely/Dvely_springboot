package com.example.dvely.deviceshare.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 공유 링크의 보기 토큰과 내 제어 링크 비밀.
 *
 * <ul>
 *   <li>보기 토큰: 128비트 난수를 소문자 base32 로 쓴 26자. 공유 오리진의 하위 도메인 라벨이 된다(DNS 라벨 63자 이하,
 *       대소문자 구분 없음).</li>
 *   <li>제어 비밀: 256비트 난수의 base64url. URL 조각(#c=…)에만 실린다.</li>
 * </ul>
 * 서버는 둘 다 SHA-256 hex 만 저장한다.
 */
public final class ShareTokens {

    public static final int TOKEN_LENGTH = 26;
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final Pattern TOKEN = Pattern.compile("[a-z2-7]{" + TOKEN_LENGTH + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareTokens() {
    }

    public static String newViewToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(TOKEN_LENGTH);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }

    public static String newControlSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean looksLikeViewToken(String label) {
        return label != null && TOKEN.matcher(label).matches();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    /** 해시를 상수 시간으로 비교한다. 비밀이 없거나 틀리면 false. */
    public static boolean matches(String secret, String expectedHash) {
        if (secret == null || secret.isBlank() || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sha256Hex(secret).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
