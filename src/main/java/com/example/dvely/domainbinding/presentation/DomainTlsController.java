package com.example.dvely.domainbinding.presentation;

import com.example.dvely.domainbinding.application.query.DomainBindingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배포된 백엔드 인스턴스의 Caddy on-demand TLS 게이트(ask). 인증서 발급 전 Caddy 가 이 엔드포인트에
 * 물어(ask), 우리 DB 에 등록된 백엔드(AWS) 도메인이면 2xx 를 준다 — 남의 도메인을 우리 IP 로 겨눠도
 * 인증서가 발급되지 않게 하는 남용 방지. 인증 없음(인스턴스가 부팅 중 호출), 호스트네임 등록 여부만 반환.
 */
@RestController
@RequiredArgsConstructor
public class DomainTlsController {

    private final DomainBindingQueryService queryService;

    @GetMapping("/api/v1/tls/allow")
    public ResponseEntity<Void> allow(@RequestParam("domain") String domain) {
        return queryService.isBackendDomainRegistered(domain)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }
}
