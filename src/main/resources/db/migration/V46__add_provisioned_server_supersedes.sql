-- EC2 재배포 시 이 서버가 교체(대체)하는 이전 서버 id. 재배포면 submit 시점에 같은 프로젝트+동일
-- webOnly 의 현재 RUNNING 서버 id 를 기록하고, 새 서버가 RUNNING 되면 EIP 를 새 인스턴스로 옮긴 뒤
-- 옛 서버를 종료한다(블루그린). 최초 배포·비-EC2 는 NULL 이라 무영향(하위호환).
ALTER TABLE provisioned_servers
    ADD COLUMN supersedes_server_id BIGINT NULL AFTER web_only;
