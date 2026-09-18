package com.example.dvely.common.paging;

import java.util.List;

/**
 * 커서 페이지네이션 한 페이지. U6(#341) 6-3·6-4 에서 무제한 목록 조회에 상한을 두기 위해 도입했다.
 *
 * <p><b>응답 본문 모양은 바꾸지 않는다.</b> 기존 목록 엔드포인트는 전부 JSON 배열을 그대로 내보내므로
 * 여기에 커서를 끼워 넣으면 FE 가 즉시 깨진다. 그래서 컨트롤러는 {@link #items()} 만 본문으로 내고
 * {@link #nextCursor()} 는 응답 헤더({@code X-Qeploy-Next-Cursor})로 싣는다 — 커서를 쓰지 않는
 * 기존 FE 는 헤더를 무시하면 예전과 똑같이 동작한다.</p>
 *
 * @param items      이 페이지의 항목. 요청한(또는 기본) 상한 이하다
 * @param nextCursor 다음 페이지의 {@code after} 로 넘길 값. 더 볼 것이 없으면 null
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> of(List<T> items) {
        return new CursorPage<>(items, null);
    }

    public <R> CursorPage<R> map(java.util.function.Function<T, R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
