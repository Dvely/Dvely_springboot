package com.example.dvely.provisioning.application.service;

import com.example.dvely.provisioning.application.port.out.DatabaseProvisioner;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사용자가 고른 방식의 DatabaseProvisioner 구현을 찾는다. DomainHostingAdapterRegistry 와 같은
 * 모양이다 — 지원하지 않는 방식이면 던져서, 아직 구현 안 된 방식을 조용히 no-op 으로 넘기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DatabaseProvisionerRegistry {

    private final List<DatabaseProvisioner> provisioners;

    public DatabaseProvisioner resolve(ProvisionMethod method) {
        return provisioners.stream()
                .filter(p -> p.method() == method)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        method + " 방식의 DB 프로비저닝은 아직 지원되지 않습니다."));
    }
}
