package com.example.dvely.common.paging;

import java.util.List;
import org.springframework.http.ResponseEntity;

/**
 * {@link CursorPage} 를 HTTP 응답으로 내보내는 한 곳. U6(#341) 6-3·6-4.
 *
 * <p><b>본문은 예전과 똑같은 JSON 배열이다.</b> 커서는 본문에 넣지 않고 {@value #NEXT_CURSOR_HEADER}
 * 헤더로만 싣는다 — 배열을 객체로 감싸는 순간 기존 FE 가 전부 깨지기 때문이다. 커서를 모르는
 * 클라이언트는 헤더를 무시하면 되고, 그때 보이는 것은 "최신 상한 건수"다.</p>
 */
public final class CursorResponse {

    /** 다음 페이지 커서. 더 볼 것이 없으면 헤더 자체가 없다(빈 문자열이 아니다). */
    public static final String NEXT_CURSOR_HEADER = "X-Qeploy-Next-Cursor";

    private CursorResponse() {
    }

    public static <T> ResponseEntity<List<T>> of(CursorPage<T> page) {
        if (page.nextCursor() == null) {
            return ResponseEntity.ok(page.items());
        }
        return ResponseEntity.ok()
                .header(NEXT_CURSOR_HEADER, page.nextCursor())
                .body(page.items());
    }
}
