-- 리프레시 토큰을 평문 대신 SHA-256 hex 로 저장한다 (#506).
--
-- 테이블이 새면 평문 행 하나하나가 그대로 쓸 수 있는 토큰이었다. 이제 원문은 발급 응답에 한 번
-- 실리고 끝나고, DB 에는 해시만 남는다.
--
-- 기존 행은 무효화하지 않고 같은 해시로 바꾼다. 앱이 원문을 SHA-256 소문자 hex 로 바꿔 찾으므로
-- SHA2(token, 256) 으로 바꾼 행은 그대로 이어진다 — 사용자는 다시 로그인하지 않는다. 원문이
-- 무작위 UUID(ASCII 36자)라 컬럼 문자셋과 무관하게 바이트가 같다.
--
-- CHAR_LENGTH = 36 조건은 재실행 보호다. 해시는 64자라 두 번 해시되지 않는다.
-- 유니크 키는 컬럼 이름이 바뀌어도 그대로 따라간다(이름이 환경마다 다를 수 있어 건드리지 않는다).

ALTER TABLE refresh_tokens CHANGE COLUMN token token_hash VARCHAR(64) NOT NULL;

UPDATE refresh_tokens
SET token_hash = SHA2(token_hash, 256)
WHERE CHAR_LENGTH(token_hash) = 36;
