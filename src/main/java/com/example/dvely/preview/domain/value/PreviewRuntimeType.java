package com.example.dvely.preview.domain.value;

/**
 * 프리뷰가 프로젝트를 어떻게 실행·서빙하는지. 모든 타입이 "프로젝트당 1컨테이너·포트 3000" 불변식을
 * 지켜, 게이트웨이(PreviewGatewayService)는 어느 타입에서도 손대지 않는다.
 *
 * <ul>
 *   <li>{@code STATIC} — 정적 프론트만. {@code npx serve -s dist} (지금까지의 유일한 동작).
 *   <li>{@code NODE_SERVER} — JS 풀스택. 앱 자체 서버가 3000 에서 UI+API 를 모두 서빙
 *       (React+Express, Next.js 등). 컨테이너 1개, 게이트웨이 무변경.
 *   <li>{@code JAVA_FULLSTACK} — 정적 FE + Java BE 를 한 컨테이너(합본 이미지)에서 돌리고
 *       내부 nginx 가 3000 에서 {@code /api}→Java, 나머지→정적 FE 로 가른다. (실행은 다음 단계)
 * </ul>
 */
public enum PreviewRuntimeType {
    STATIC,
    NODE_SERVER,
    JAVA_FULLSTACK
}
