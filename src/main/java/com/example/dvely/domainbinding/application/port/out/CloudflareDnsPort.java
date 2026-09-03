package com.example.dvely.domainbinding.application.port.out;

public interface CloudflareDnsPort {

    String createCnameRecord(String hostname, String target);

    /**
     * A 레코드를 만든다(백엔드 EC2 의 IP 를 가리킨다). proxied=false 면 DNS-only(EIP:8080 직결, http),
     * true 면 Cloudflare 프록시(향후 Origin Rule 로 8080 감싸 HTTPS). 관리형 서브도메인이 백엔드(AWS)
     * 를 가리킬 때 CNAME 대신 이걸 쓴다 — IP 에는 CNAME 을 걸 수 없다.
     */
    String createARecord(String hostname, String ipAddress, boolean proxied);

    boolean recordExists(String hostname, String recordId);

    void deleteRecord(String hostname, String recordId);
}
