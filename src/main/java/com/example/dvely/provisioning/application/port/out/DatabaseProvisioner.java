package com.example.dvely.provisioning.application.port.out;

import com.example.dvely.provisioning.domain.value.ProvisionMethod;

/**
 * DB를 마련하는 방식 하나를 구현한다. 배포 대상을 DomainHostingAdapter 로 어댑터화한 것과 같은
 * 패턴이다 — 방식(LOCAL·RDS·DOCKER)마다 구현체를 두고, DatabaseProvisionerRegistry 가 사용자가
 * 고른 방식의 구현 하나를 골라 발동한다. 새 방식을 붙이는 일이 기존 경로를 건드리지 않는다.
 */
public interface DatabaseProvisioner {

    /** 이 구현이 담당하는 방식. 레지스트리가 이 값으로 구현을 찾는다. */
    ProvisionMethod method();

    /**
     * DB를 만든다. 접속정보를 담은 결과를 돌려준다.
     *
     * @param spec      무엇을 어느 프로젝트에 만들지
     * @param containerId LOCAL 방식이 DB 컨테이너를 띄울 프리뷰 컨테이너 ID. RDS·DOCKER 는 무시한다.
     */
    ProvisionResult provision(ProvisionSpec spec, String containerId);

    /**
     * 만든 리소스를 정리한다. 중간 실패 시 반쪽 리소스가 사용자 계정에 남아 과금되지 않도록,
     * 그리고 프로젝트가 지워질 때 리소스도 함께 회수하도록 반드시 구현한다. LOCAL 은 컨테이너가
     * 세션과 함께 사라지므로 사실상 no-op 에 가깝다.
     */
    void deprovision(String resourceId, String containerId);
}
