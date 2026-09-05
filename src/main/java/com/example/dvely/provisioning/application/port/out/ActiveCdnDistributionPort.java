package com.example.dvely.provisioning.application.port.out;

import java.util.Set;

/**
 * DB(domain_bindings)가 현재 참조하는 CloudFront 배포 id 집합. 고아 스윕이 "우리가 만든 배포 중 이 집합에도,
 * 삭제 큐에도 없는 것"만 고아로 판정해 정리한다 — <b>활성 바인딩이 가리키는 배포는 절대 지우지 않기 위한
 * 안전 경계</b>다. 도메인바인딩 도메인이 구현한다(배포 추적이 그쪽 소관이라).
 */
public interface ActiveCdnDistributionPort {

    Set<String> trackedDistributionIds();
}
