-- EC2 백엔드에 안정 주소(Elastic IP)를 붙인다. 자동할당 public IP 는 stop·재배포마다 바뀌어 도메인이
-- 깨지므로, 도메인 바인딩의 전제로 서버당 EIP 를 할당·연결한다. 이 컬럼은 그 EIP 의 allocation ID —
-- 종료 정리가 이 값으로 release 해야 유휴 EIP 가 계속 과금되지 않는다(연결만 풀려도 할당은 남는다).
ALTER TABLE provisioned_servers
    ADD COLUMN elastic_ip_allocation_id VARCHAR(40) NULL AFTER instance_id;
