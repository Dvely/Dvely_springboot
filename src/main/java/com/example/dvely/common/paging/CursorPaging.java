package com.example.dvely.common.paging;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 커서 페이지네이션 공통 규칙. U6(#341) 6-3·6-4.
 *
 * <p>"더 있는지"를 별도 count 쿼리로 묻지 않는다 — {@code limit + 1} 건을 읽어 한 건이 남으면 더
 * 있는 것이다. count 는 같은 조건을 두 번 훑는 비용인데, 이 작업의 목적 자체가 읽는 양을 줄이는 것이다.
 */
public final class CursorPaging {

    private CursorPaging() {
    }

    /** {@code limit + 1} 건을 읽기 위한 Pageable. */
    public static Pageable probe(int limit) {
        return PageRequest.of(0, limit + 1);
    }

    /**
     * {@code limit + 1} 건 읽어온 결과를 한 페이지로 자른다. 넘치면 마지막으로 살아남은 항목에서
     * 커서를 뽑아 싣는다.
     */
    public static <T> CursorPage<T> slice(List<T> probed, int limit, Function<T, String> cursorOf) {
        if (probed.size() <= limit) {
            return CursorPage.of(probed);
        }
        List<T> page = probed.subList(0, limit);
        return new CursorPage<>(List.copyOf(page), cursorOf.apply(page.get(limit - 1)));
    }

    /**
     * 요청된 limit 를 [1, max] 로 자른다. 없거나 0 이하면 기본값. 기존 FE 는 limit 를 보내지 않으므로
     * 이 기본값이 곧 "예전 무제한 동작의 상한"이다.
     */
    public static int clamp(Integer requested, int defaultLimit, int maxLimit) {
        if (requested == null || requested <= 0) {
            return defaultLimit;
        }
        return Math.min(requested, maxLimit);
    }

    /**
     * 커서를 id 로 되돌린다. 커서는 우리가 직접 만들어 내보낸 값이라 정상 흐름에서는 늘 숫자다 —
     * 숫자가 아니면 클라이언트가 손댄 것이므로 400 으로 끊는다(조용히 첫 페이지로 되돌리면
     * 클라이언트는 자기 루프가 끝나지 않는 이유를 알 수 없다).
     */
    public static Long parseCursor(String after) {
        if (after == null || after.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(after.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("잘못된 커서입니다: " + after);
        }
    }
}
