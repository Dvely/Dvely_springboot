# DB 프로비저닝 — FE 합의 계약 (2026-09-01)

> 이 파일은 이 기능을 이어받는 사람을 위한 것. FE 세션(dvely-fe-9d)과 합의한 계약을 박아둔다.
> 오늘 겪은 사고가 전부 "서버는 200 인데 FE 가 못 읽는" 계약 불일치였다 — 이 계약은 그걸 예방하려고 먼저 맞춘 것이다.

## 확정된 응답 계약

### 목록: GET /projects/{id}/databases  (순수 DB 조회 — 외부 API 안 때림, 상시 폴링 안전)
각 항목:
```
databaseId, projectId, method, engine, status,
host, port, database, username,
password: null,               // 목록·상세는 항상 null
expiresAt,                    // LOCAL 만 값, RDS/DOCKER 는 null — OffsetDateTime
errorCode: null, errorMessage: null,
createdAt, updatedAt          // 셋 다 OffsetDateTime (+09:00) — FE 가 포맷에 파싱함
```

### 생성: POST /projects/{id}/databases   요청 { method, engine }
**항상 같은 형태** (method 로 형태가 갈리면 안 됨 — FE 파싱 곤란):
```
{
  "requiresApproval": bool,     // 판별 필드. LOCAL=false, RDS/DOCKER=true
  "database": {...}|null,       // LOCAL 이면 채움(password 포함), 아니면 null
  "taskId": null,               // 승인 필요하면 채움
  "approvalIds": []
}
```
LOCAL 이 나중에 승인을 거치게 바뀌어도 requiresApproval 만 true 되면 됨 — 형태 안 깨짐.

## 규칙
- **닫힌 enum**: method(LOCAL·RDS·DOCKER) · engine(POSTGRESQL·MYSQL) · status(5개)
- **열린 문자열**: errorCode — 모르는 값은 FE 가 PROVIDER_ERROR 취급. 클라우드 오류는 더 생길 수 있어 닫으면 안 됨
- **status**: 전이(PENDING·PROVISIONING) / 종료(READY·FAILED·EXPIRED). FE 는 전이 있으면 폴링, 종료면 정지
- **시각**: 셋 다 OffsetDateTime. LocalDateTime 으로 내면 오프셋이 없어 FE 가 9시간 틀리게 파싱함 (deployment 응답들이 지금 그 상태 — 별도로 정리 예정)
- **password**: 생성 응답에서만 1회. 목록·상세는 null (환경변수 secret 선례와 동일)
- **봉투**: ApiResponseAdvice 가 감쌈. FE 는 unwrapApiData 로 벗김
- **상태 정본**: 서버 status 가 정본. expiresAt 과거 + READY 인 짧은 구간은 FE 가 "만료 처리 중" 표시. 회수 워커 1분 주기라 거의 안 보임
- **화면**: 인프라 탭(/project/$slug/infra), 클라우드 연결 선택 바로 아래

## 미해결 (다음 세션에서)
- 실제 구현: DockerContainerService 에 세션 전용 네트워크 + DB 컨테이너 생성 (ICC 차단 우회)
- LocalDbProvisioner 구현 → 엔드포인트 → 만료 회수 워커
- **로컬 Docker 로 실측 필수**: "READY = 진짜 접속 가능" 담보. postgres/mysql 컨테이너 띄우고 실제 접속 확인
- FE 사용자 확인 대기: 화면 위치(인프라 탭 합의됨), 비밀번호 정책(생성응답만 합의됨)
- RDS 단계에서: POST /databases/{id}/rotate-password 재발급 엔드포인트

## 기술 제약 (코드가 알려준 것 — 되풀이 금지)
1. 프리뷰 컨테이너는 cap-drop ALL → 그 안에서 docker 못 돌림(DinD 불가). DB 는 형제 컨테이너로.
2. qeploy-preview 네트워크는 ICC 차단(enable_icc=false, 세션 격리) → 형제 DB 를 그 네트워크에 붙여도 앱이 접속 못 함. → 세션 전용 네트워크를 따로 만들어 앱+DB 만 넣는다.

## 추가 합의 (2026-09-01 밤)
- **프리뷰 재시작 시 DB**: 프리뷰 세션이 끝나면 LOCAL DB 도 끝난다. acquire() 가 컨테이너가
  안 돌면 새로 만들므로(PreviewSessionService:11-18), DB 도 다시 만들어야 한다. FE 는
  "이 DB 는 현재 프리뷰 세션과 함께 사라집니다" 안내 예정 — 실제 동작과 맞음.
- **EXPIRED 누적**: 목록에서 제외(활성만 반환)로 결정. 행은 감사·이력으로 남기고 워커는
  상태만 EXPIRED 로 넘긴다. QueryService.list 가 EXPIRED 를 filter 로 뺀다.

## 다음 세션 (아침에 이어감)
- [ ] PR 올리고 dev 배포 → 엔드포인트 실제 200 확인 → FE 에 알림
- [ ] FE 사용자 확인(화면 위치=인프라 탭 합의됨, 비밀번호 정책=생성응답만 합의됨) 반영
- [ ] dev 에서 프리뷰 띄우고 LOCAL DB 실제 생성 e2e 확인
- [ ] 그다음: RDS 단계 (RdsProvisioner + 승인 흐름을 같은 틀에)

## 머지 전 검증 수정 (2026-09-01, 리뷰 반영)
- **앱↔DB 연결 구현됨**: provision 이 프리뷰 앱 컨테이너를 세션 네트워크에 직접 붙인다. 이전엔
  DB 만 네트워크에 넣어 앱이 접속 못 하는 공백이 있었다. 이제 host="db" 가 실제로 접속 가능.
- **비밀번호 로그 유출 제거**: MySQL 준비 핑이 컨테이너 env(MYSQL_PASSWORD)를 셸에서 확장 —
  로그·예외에 남는 명령 문자열에 평문 비번이 없다.
- **프로비저닝 트랜잭션 분리**: 느린 Docker 호출을 트랜잭션 밖에서. 실패 시 FAILED 감사 행이
  롤백되지 않고 남는다.
- **만료 워커 원자적 클레임**: READY→EXPIRED 를 원자적으로 집은 워커만 deprovision. 이중 정리
  방지. deprovision 실패해도 상태는 EXPIRED 확정(FE 가 죽은 DB 를 살아있는 것으로 안 봄).
- **목록 EXPIRED 제외를 DB 단으로**: 전 행 로드 후 메모리 필터 → status<>EXPIRED 술어.
