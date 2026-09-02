package com.example.dvely.provisioning.domain.value;

/**
 * 지원하는 DB 엔진. 세 프로비저닝 방식(LOCAL·RDS·DOCKER)이 공통으로 쓴다.
 * 방식마다 이미지 이름·포트가 다르므로 그 매핑은 각 Provisioner 구현이 갖는다.
 */
public enum DatabaseEngine {
    POSTGRESQL,
    MYSQL
}
