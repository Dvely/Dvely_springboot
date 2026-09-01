package com.example.dvely.provisioning.application.port.out;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;

/**
 * 프로비저닝 요청 명세. 무엇을(engine) 어느 프로젝트에(projectId) 만들지를 담는다.
 *
 * 티어·인스턴스 크기 같은 실배포 전용 파라미터는 초기에 서버가 정한 기본값을 쓰므로 여기 두지
 * 않는다(설계 결정 8). 필요해지면 RDS 구현이 참조할 필드를 이 명세에 더한다.
 */
public record ProvisionSpec(
        Long projectId,
        DatabaseEngine engine
) {}
