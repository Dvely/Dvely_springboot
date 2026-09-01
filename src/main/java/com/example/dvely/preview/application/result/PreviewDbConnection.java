package com.example.dvely.preview.application.result;

/**
 * 프리뷰 백엔드에 주입할 DB 접속정보. 서버형 프리뷰가 부팅될 때 자동 프로비저닝된 LOCAL DB 의
 * 접속값이다. password 를 담으므로 toString 은 마스킹한다 — 로그로 새지 않게.
 *
 * engine 은 DATABASE_URL 스킴(mysql:// vs postgresql://)을 만들기 위해 함께 온다.
 */
public record PreviewDbConnection(
        String engine,
        String host,
        Integer port,
        String database,
        String username,
        String password
) {
    @Override
    public String toString() {
        return "PreviewDbConnection[engine=" + engine + ", host=" + host + ", port=" + port
                + ", database=" + database + ", username=" + username + ", password=***]";
    }
}
