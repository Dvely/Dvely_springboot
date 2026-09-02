package com.example.dvely.provisioning.application.port.out;

/**
 * 프로비저닝 결과 — 만들어진 리소스의 식별자와 접속정보.
 *
 * resourceId 는 방식마다 의미가 다르다: LOCAL 은 DB 컨테이너 ID, RDS 는 DB 인스턴스 식별자,
 * DOCKER 는 EC2 인스턴스 ID. 정리(deprovision)할 때 이 값으로 대상을 지목한다.
 *
 * 접속정보는 이후 PRODUCTION 스코프 환경변수로 등록돼 앱에 주입된다. password 를 이 객체에
 * 담아 옮기되, 로그·예외 메시지에는 절대 싣지 않는다.
 */
public record ProvisionResult(
        String resourceId,
        String host,
        int port,
        String database,
        String username,
        String password
) {
    @Override
    public String toString() {
        // password 가 로그로 새지 않도록 마스킹. record 기본 toString 을 덮는다.
        return "ProvisionResult[resourceId=" + resourceId + ", host=" + host
                + ", port=" + port + ", database=" + database + ", username=" + username
                + ", password=****]";
    }
}
