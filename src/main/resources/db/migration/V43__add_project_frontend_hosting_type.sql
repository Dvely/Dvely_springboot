-- 프론트엔드를 어디에 호스팅할지(GITHUB_PAGES / S3 / EC2). 프로젝트 단위 설정이며, 재배포·정리·
-- 도메인 바인딩이 이 값 하나로 프론트 위치를 안다. 기존 프로젝트는 전부 GitHub Pages 였으므로 기본값
-- GITHUB_PAGES 로 채워 하위호환을 지킨다(도메인 바인딩의 hosting_target 과는 별개 축).
ALTER TABLE projects
    ADD COLUMN frontend_hosting_type VARCHAR(20) NOT NULL DEFAULT 'GITHUB_PAGES' AFTER is_deleted;
