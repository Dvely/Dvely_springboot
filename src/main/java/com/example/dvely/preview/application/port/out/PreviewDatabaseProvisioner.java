package com.example.dvely.preview.application.port.out;

import com.example.dvely.preview.application.result.PreviewDbConnection;
import java.util.Optional;

/**
 * 서버형 프리뷰가 부팅될 때 그 컨테이너 옆에 DB 를 자동 프로비저닝하기 위한 포트. 구현은
 * provisioning 도메인이 제공한다(방향은 provisioning→preview 로 유지돼 순환이 없다).
 *
 * 인프라 탭의 공개 프로비저닝과 달리 ACTIVE 프리뷰 게이트를 타지 않는다 — 아직 PROVISIONING
 * 단계(activate 전)의 컨테이너 ID 를 그대로 받아 형제 DB 를 띄운다. 기본 엔진은 구현이 정한다.
 */
public interface PreviewDatabaseProvisioner {

    Optional<PreviewDbConnection> provisionForPreview(Long projectId, String containerId, String engine);
}
