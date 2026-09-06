package com.example.dvely.domainbinding.domain.value;

public enum DomainHostingTarget {
    GITHUB_PAGES,
    // AWS = 백엔드(EC2). A 레코드로 EIP 를 가리키고 HTTPS 는 인스턴스 Caddy 가 종단한다.
    AWS,
    // AWS_EC2_FRONTEND = 독립 프론트(웹 전용 EC2). 백엔드와 같은 EC2 기계(EIP·Caddy·A레코드)를 쓰되
    // 대상 서버가 webOnly 프론트라는 점만 다르다. 프론트 오리진 판정(!= AWS)엔 자연히 프론트로 잡힌다.
    AWS_EC2_FRONTEND,
    // AWS_S3_FRONTEND = S3 정적 호스팅 프론트의 HTTPS. S3 website 엔드포인트는 http-only 라 CloudFront
    // (+ us-east-1 ACM 인증서)를 앞단에 둬 HTTPS 를 종단한다. EC2 가 아니라 A레코드/Caddy 를 안 쓰고,
    // 최종 DNS 는 CloudFront 도메인으로의 CNAME(proxied=false)이다. 프로비저닝이 비동기(수 분).
    AWS_S3_FRONTEND,
    GCP
}
