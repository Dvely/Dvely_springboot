package com.example.dvely.domainbinding.application.port.out;

public interface CloudflareDnsPort {

    String createCnameRecord(String hostname, String target);

    /**
     * CNAME 을 만들되 프록시 여부를 명시한다. S3 프론트 HTTPS(CloudFront) 경로는 <b>proxied=false</b> 가
     * 필수다 — ACM DNS 검증 레코드를 프록시하면 검증이 깨지고, 도메인→CloudFront 최종 CNAME 을 프록시하면
     * CloudFront 가 우리 도메인용 ACM 인증서로 직접 서빙하는 흐름이 어긋난다(GitHub Pages 관리형은 프록시 기본).
     */
    String createCnameRecord(String hostname, String target, boolean proxied);

    /**
     * A 레코드를 만든다(백엔드 EC2 의 IP 를 가리킨다). proxied=false 면 DNS-only(EIP:8080 직결, http),
     * true 면 Cloudflare 프록시(향후 Origin Rule 로 8080 감싸 HTTPS). 관리형 서브도메인이 백엔드(AWS)
     * 를 가리킬 때 CNAME 대신 이걸 쓴다 — IP 에는 CNAME 을 걸 수 없다.
     */
    String createARecord(String hostname, String ipAddress, boolean proxied);

    boolean recordExists(String hostname, String recordId);

    void deleteRecord(String hostname, String recordId);
}
