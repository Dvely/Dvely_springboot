-- 템플릿 카탈로그의 마지막 성공 스냅샷 (#395).
--
-- 왜 DB 에 두는가 — PagesTemplateCatalogClient 의 캐시는 `private volatile Snapshot` 이라
-- 메모리에만 있다. 재시작마다 cold start 이고, 배포가 곧 그 순간이다. 그때 GH Pages 가
-- request-timeout(5s) 안에 응답하지 못하면 /api/v1/templates 가 503 이 된다.
--
-- Dvely_FE#117 이후로 FE 다섯 화면(랜딩·갤러리·홈 탐색·생성 미리보기·프롬프트 얹기)이 전부
-- 이 API 하나만 본다. 예전에는 갤러리만 깨졌다.
--
-- 파일이 아니라 DB 인 이유: 인스턴스가 여러 대일 때 한 대가 받아둔 것을 재시작한 다른 대가
-- 쓸 수 있어야 한다. 파일은 그 공유가 안 되고 인스턴스 교체도 못 넘는다.
--
-- 단일 행이다. catalog_id 를 항상 1 로 고정하고 upsert 한다 — 이력을 쌓을 이유가 없고,
-- 쌓으면 지우는 일이 생긴다.
CREATE TABLE template_catalog_snapshot (
    catalog_id  TINYINT      NOT NULL,
    -- 파싱해서 쓰는 값이 아니라 우리가 직렬화한 템플릿 목록이다. 카탈로그 원문이 아니라
    -- 파싱 결과를 담는 이유는, 원문을 담으면 복원 때 역직렬화 규칙이 갈릴 수 있기 때문이다.
    -- 길이는 넉넉히 둔다 — 17종에 contentHints 까지 담긴 지금이 약 20KB 다.
    payload     LONGTEXT     NOT NULL,
    -- 이 스냅샷을 실제로 네트워크에서 받은 시각. 복원 여부를 이 값의 나이로 판단한다.
    fetched_at  DATETIME(6)  NOT NULL,
    -- 어느 카탈로그에서 받은 것인지. URL 이 바뀌면(설정 변경) 남의 카탈로그를 복원하지 않는다.
    source_url  VARCHAR(500) NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (catalog_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
