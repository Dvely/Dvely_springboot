package com.example.dvely.domainbinding.domain.value;

public enum DomainHostingTarget {
    GITHUB_PAGES,
    // AWS = 백엔드(EC2). A 레코드로 EIP 를 가리키고 HTTPS 는 인스턴스 Caddy 가 종단한다.
    AWS,
    // AWS_EC2_FRONTEND = 독립 프론트(웹 전용 EC2). 백엔드와 같은 EC2 기계(EIP·Caddy·A레코드)를 쓰되
    // 대상 서버가 webOnly 프론트라는 점만 다르다. 프론트 오리진 판정(!= AWS)엔 자연히 프론트로 잡힌다.
    AWS_EC2_FRONTEND,
    GCP
}
