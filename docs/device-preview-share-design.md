# 내 기기 프리뷰 공유 설계 (1단계: 로컬 웹 링크)

> 기준일: 2026-11-12 · 기준 커밋: 67c1ff1a(develop) · 이슈 #652 · 서버 재호스팅 #649
> 구현 상태: 설계. PR-2~5 는 이 문서를 기준으로 한다

---

## 목표

남에게 작업 중인 화면을 보여 줄 때 화면 공유 대신 링크를 넘긴다. 1단계 대상은 **내 기기에서 도는 로컬 웹(dev 서버)** 이다.

```
$ qeploy share 5173
공유 링크: https://k3x9…q2.share.qeploy.com   (1시간 뒤 만료, 읽기 요청만)
Ctrl+C 로 공유를 끝냅니다.
```

- 받은 사람은 설치 없이 브라우저에서 그 웹을 쓴다. 각자의 브라우저에서 따로 열리고, 내 화면에는 보이지 않는다.
- 파일을 고치면 받은 쪽 화면도 갱신된다. dev 서버의 HMR WebSocket 을 통과시킨다.
- 기기는 서버로 WebSocket 을 **먼저** 연다. 포트 개방·VPN·relay 직접 호스팅이 필요 없다.
- 링크는 만료되고, 주인이 회수하면 바로 끊긴다.

2단계(Android 에뮬레이터 미러링·조작)와 3단계(iOS 시뮬레이터)는 같은 터널에 프레임 종류를 더해 얹는다(§9).

## 1. 왜 서버 중계인가

개편안 1·2판은 WebRTC P2P 를 전제로 했다. 서버리스(Workers)에서는 화면을 서버로 흘릴 수 없어서였다. 서버리스는 보류됐고 서버(#649, Oracle 도쿄)가 생겼으므로 1단계는 중계로 간다.

| | 서버 중계 (채택) | WebRTC P2P |
|---|---|---|
| 구현 | WebSocket 하나에 다중화. Spring·Node 기본 기능 | 신호 서버 + Node DataChannel(별도 네이티브 의존) + TURN |
| 실패 진단 | 서버 로그 한 곳 | NAT·TURN 경로마다 다름 |
| 비용 | 서버 아웃바운드. Oracle Always Free 월 10TB | TURN 1,000GB 무료 뒤 과금(상한 장치 필요) |
| 지연 | 기기 → 도쿄 → 받는 사람(한국 기준 왕복 약 37ms 추가) | 직접 연결이면 더 짧음 |

로컬 웹은 정적 파일·JS 모듈이 대부분이라 중계 비용이 작다. 영상(2단계)에서 대역폭이 문제가 되면 그때 P2P 를 더한다. 프레임 프로토콜(§4)은 전송 수단과 독립적으로 둔다.

## 2. 구조

```
받는 사람 브라우저 ──HTTPS/WSS──▶ <토큰>.share.qeploy.com ──nginx──▶ Spring Boot
                                                                        │ 공유 게이트웨이(Host 로 분기)
                                                                        │ 터널 레지스트리(공유 id → 세션)
qeploy CLI(내 기기) ◀──────────────── WSS 하나(기기가 먼저 연결) ─────────┘
   └─▶ http://localhost:5173 (dev 서버)
```

- **서버**: 새 패키지 `com.example.dvely.deviceshare`(4계층). 기존 구역과 섞지 않는다.
- **CLI**: `@qeploy/cli` 에 `share` 명령. 기존 `@qeploy/client` 의 PAT·API 주소 설정을 쓴다. WebSocket 은 Node 내장 `WebSocket`(Node 22+) 이 아니라, 헤더를 실어 보낼 수 있는 경로를 쓴다(§6).
- **FE**: 1단계에는 화면이 없다. 링크 관리 화면은 Dvely_FE 별도 이슈.

서버 프리뷰(Docker 컨테이너, `preview` 패키지)는 그대로 둔다. 화면·문서에서는 "서버 프리뷰 / 내 기기 프리뷰"로 나눠 부른다.

## 3. 공유 오리진

### 3.1 링크마다 다른 오리진

공유 콘텐츠는 `api.qeploy.com` 에서 내보내지 않는다. 링크마다 `<토큰>.share.qeploy.com` 이다.

- **API 오리진과 분리**: OAuth 콜백(`/api/v1/auth/github/callback`)이 API 오리진에 있다. 같은 오리진에서 남의 dev 서버 콘텐츠를 내보내면 그 콘텐츠의 스크립트가 같은 오리진 권한을 갖는다.
- **링크끼리 분리**: 링크마다 오리진이 달라 쿠키·localStorage·서비스 워커가 섞이지 않는다. 한 사용자의 두 링크도 서로를 읽지 못한다.
- **토큰이 곧 주소**: 128비트 난수를 소문자 base32 로 쓴 26자(DNS 라벨 63자 이하). 서버는 SHA-256 해시만 저장한다.

로컬 개발에서는 `<토큰>.share.localhost:8080` 을 쓴다. Chrome·Firefox 는 `*.localhost` 를 루프백으로 푼다. curl 은 `--resolve` 나 `Host` 헤더로 시험한다.

설정 키: `qeploy.device-share.base-domain`(기본 `share.localhost`, 운영 `share.qeploy.com`), `qeploy.device-share.public-scheme`(기본 `http`, 운영 `https`), `qeploy.device-share.public-port`(로컬 8080, 운영 비움).

### 3.2 Host 로 분기한다

`Host` 가 `*.<base-domain>` 이면 **공유 게이트웨이 필터**가 요청을 끝까지 처리하고 체인을 넘기지 않는다. 그래서 공유 오리진에서는 `/api/v1/**` 컨트롤러에 닿을 길이 구조적으로 없다. 반대로 API 호스트로 온 요청은 게이트웨이를 타지 않는다.

- 필터는 `RequestIdFilter` 다음, Spring Security 체인보다 **앞**에 둔다. 공유 오리진 요청은 인증하지 않는다(링크 토큰이 자격이다).
- 토큰 라벨 형식이 아니면(26자 base32 가 아니면) 404.
- `/__qeploy/` 경로는 예약한다. 1단계에서는 상태 페이지(기기 연결 끊김 안내)만 쓴다. 2단계 뷰어가 여기 들어간다.

### 3.3 운영 DNS·인증서 (#649 서버 작업)

- Cloudflare: `*.share` A 레코드, DNS only(Cloudflare 프록시는 오래 응답이 없는 요청을 끊는다).
- 인증서: 와일드카드라 HTTP-01 이 안 된다. `certbot-dns-cloudflare` 로 DNS-01. 토큰은 DNS 편집 권한만 가진 것을 서버에 따로 둔다.
- nginx: `server_name ~^[a-z2-7]{26}\.share\.qeploy\.com$;` 블록. `Host` 를 그대로 넘기고 WebSocket 업그레이드를 통과시킨다(셋업 스크립트의 `map $http_upgrade` 재사용).

## 4. 터널 프레임 프로토콜

WebSocket 바이너리 메시지 하나가 프레임 하나다.

```
0        1                5
+--------+----------------+----------------------
| kind   | stream id (BE) | payload
| 1 byte | 4 bytes        | 나머지
+--------+----------------+----------------------
```

- stream id 는 서버가 요청마다 매긴다(1부터 증가). 0 은 터널 자체.
- JSON payload 는 UTF-8. 본문 조각은 원본 바이트 그대로(base64 없음).
- 본문 조각 최대 64KiB. 서버·CLI 모두 WebSocket 메시지 최대 크기를 1MiB 로 둔다.

| kind | 방향 | payload | 뜻 |
|---|---|---|---|
| `0x01` REQ_HEAD | 서버 → 기기 | JSON `{method, path, query, headers}` | 요청 시작 |
| `0x02` REQ_BODY | 서버 → 기기 | 바이트 | 요청 본문 조각 |
| `0x03` REQ_END | 서버 → 기기 | 없음 | 요청 본문 끝 |
| `0x04` REQ_ABORT | 서버 → 기기 | 없음 | 받는 사람이 끊음 |
| `0x11` RES_HEAD | 기기 → 서버 | JSON `{status, headers}` | 응답 시작 |
| `0x12` RES_BODY | 기기 → 서버 | 바이트 | 응답 본문 조각 |
| `0x13` RES_END | 기기 → 서버 | 없음 | 응답 끝 |
| `0x14` RES_ERROR | 기기 → 서버 | JSON `{code, message}` | dev 서버 연결 실패 등 |
| `0x21` WS_OPEN | 서버 → 기기 | JSON `{path, query, headers, protocols}` | 받는 쪽 WebSocket 열기 요청 |
| `0x22` WS_OPENED | 기기 → 서버 | JSON `{protocol}` | dev 서버와 연결됨 |
| `0x23` WS_TEXT | 양방향 | UTF-8 | 텍스트 메시지 |
| `0x24` WS_BINARY | 양방향 | 바이트 | 바이너리 메시지 |
| `0x25` WS_CLOSE | 양방향 | JSON `{code, reason}` | 닫힘 |
| `0x41` HELLO | 기기 → 서버 | JSON `{cliVersion, targetPort}` | 연결 직후 한 번 |
| `0x42` WELCOME | 서버 → 기기 | JSON `{shareId, url, expiresAt, allowWrite}` | 받아들임 |
| `0x43` GOODBYE | 서버 → 기기 | JSON `{reason}` | 회수·만료·대체로 끊음 |

프로토콜 버전은 연결 URL 의 쿼리(`?v=1`)로 맞춘다. 서버가 모르는 버전이면 GOODBYE 후 닫는다.

### 4.1 흐름 제어

1단계는 단순하게 둔다.

- 서버 → 받는 사람: 기기가 보낸 조각을 받는 즉시 응답 스트림에 쓰고 flush 한다(#641 과 같은 이유로 `getBody()` 를 쓰지 않는다). 쓰기가 막혀 스트림별 대기 버퍼가 8MiB 를 넘으면 그 스트림을 REQ_ABORT 로 끊는다.
- 서버 → 기기: Spring 의 `ConcurrentWebSocketSessionDecorator`(보내기 시간 상한 10초, 버퍼 4MiB). 넘치면 터널을 닫는다. 한 받는 사람이 느려도 다른 스트림을 붙잡지 않도록 본문은 조각으로만 보낸다.
- 터널 하나의 동시 스트림 32개. 넘으면 503.

## 5. 서버

### 5.1 데이터

`device_share_links`(Flyway 번호는 머지 직전에 정한다)

| 열 | 타입 | 설명 |
|---|---|---|
| `share_id` | BIGINT PK | |
| `user_id` | BIGINT FK | 만든 사람 |
| `token_hash` | CHAR(64) UNIQUE | 토큰 SHA-256 hex |
| `token_prefix` | VARCHAR(8) | 목록 표시용 앞 6자 |
| `target_port` | INT | 기기의 dev 서버 포트(표시용) |
| `allow_write` | BOOLEAN | 쓰기 요청 허용 |
| `expires_at` | DATETIME(6) | |
| `revoked_at` | DATETIME(6) NULL | |
| `created_at` · `last_access_at` | DATETIME(6) | |

인덱스: `(user_id, created_at)`. 만료·회수된 행은 기존 보존 스윕에 7일 보관으로 붙인다.

접근 로그는 테이블이 아니라 애플리케이션 로그 한 줄(`share=`, 메서드, 경로, 상태, 바이트, 시간). **쿼리 문자열은 남기지 않는다**(토큰·검색어가 실릴 수 있다).

### 5.2 API

| 메서드 · 경로 | 인증 | 설명 |
|---|---|---|
| `POST /api/v1/device-shares` | 브라우저 · PAT(WRITE) | `{targetPort, ttlMinutes?, allowWrite?}` → `{shareId, url, tunnelUrl, expiresAt, allowWrite}`. 토큰 평문은 이 응답에만 실린다(url 안) |
| `GET /api/v1/device-shares` | 브라우저 · PAT(READ) | 내 링크 목록(만료·회수 포함 최근 50개, 연결 여부) |
| `DELETE /api/v1/device-shares/{shareId}` | 브라우저 · PAT(WRITE) | 회수. 열린 터널에 GOODBYE 후 닫는다 |
| `GET /api/v1/device-shares/{shareId}/tunnel` (WS 업그레이드) | PAT(WRITE) | 기기 터널. 링크 주인만. 같은 링크로 새 연결이 오면 옛 연결을 GOODBYE(`replaced`) 로 닫는다 |

- `ttlMinutes` 기본 60, 최대 1440. `allowWrite` 기본 false.
- 사용자당 살아 있는 링크 5개, 서버 전체 연결 터널 50개. 넘으면 429(`DEVICE_SHARE_LIMIT_EXCEEDED`).
- 터널 연결의 인증은 기존 `JwtAuthenticationFilter` 가 핸드셰이크 GET 의 `Authorization` 헤더로 한다. 브라우저 WebSocket 은 헤더를 못 싣지만, 터널은 CLI 만 연다.

### 5.3 게이트웨이 처리 순서

1. `Host` 에서 토큰 라벨을 꺼내 해시로 링크를 찾는다. 없으면 404, 회수·만료면 410.
2. 메서드 확인. `allowWrite=false` 면 GET·HEAD·OPTIONS 만. 나머지는 403(`읽기 전용 링크`).
3. 경로 확인(§7). 막힌 경로는 403.
4. 연결된 터널이 없으면 503 과 `/__qeploy/offline` 안내 HTML.
5. 스트림을 열고 REQ_HEAD → 본문 조각 → REQ_END. 본문 상한 10MiB(넘으면 413).
6. RES_HEAD 를 기다린다(30초 넘으면 504). 상태·헤더를 쓰고 본문 조각을 흘린다. 조각 사이 60초 무응답이면 끊는다. 요청 전체 상한 5분.

WebSocket 업그레이드 요청(HMR)은 필터가 내부 경로 `/__qeploy/ws-relay` 로 forward 하고, Spring WebSocket 핸들러가 WS_OPEN/… 프레임으로 잇는다. forward 디스패치는 보안 체인에서 그 경로만 허용한다.

## 6. CLI

```
qeploy share <port> [--ttl 60] [--allow-write] [--json]
```

1. `POST /device-shares` 로 링크를 만든다.
2. `tunnelUrl` 로 WebSocket 을 연다. `Authorization: Bearer <PAT>` 헤더를 싣는다. Node 내장 `WebSocket` 은 헤더 지정이 안 되므로 `ws` 패키지를 쓴다(의존 1개 추가, 버전 고정).
3. HELLO → WELCOME 을 받으면 링크와 만료 시각을 출력한다.
4. REQ_HEAD 를 받으면 `http://127.0.0.1:<port>` 로 요청한다.
   - **`Host` 를 `localhost:<port>` 로 바꾼다.** Vite 는 허용되지 않은 Host 를 막는다(`server.allowedHosts`). `Origin`·`Referer` 도 같은 방식으로 바꾼다. WebSocket 의 `Origin` 검사도 같다.
   - 응답의 `Location` 이 `http://localhost:<port>` 를 가리키면 상대 경로로 바꾼다.
5. 끊기면 1·2·4·8초…최대 30초 간격으로 다시 잇는다(같은 링크). 링크가 410 이면 멈춘다.
6. Ctrl+C 면 `DELETE /device-shares/{id}` 로 회수하고 끝낸다.

CLI 는 dev 서버가 안 떠 있으면 RES_ERROR(`ECONNREFUSED`)를 보내고, 받는 사람에게는 502 안내가 나간다.

## 7. 안전장치

dev 서버는 공개 노출을 전제로 만들지 않는다. 예를 들어 Vite 는 `/@fs/` 경로로 프로젝트 밖 파일을 읽게 하는 우회 취약점이 2025년에 여러 번 공개됐다. 1단계의 기본값은 보수적으로 둔다.

| 항목 | 기본 | 이유 |
|---|---|---|
| 링크 수명 | 60분, 최대 24시간 | 잊힌 링크가 열려 있지 않게 |
| 메서드 | GET · HEAD · OPTIONS | 받는 사람이 내 dev DB 에 쓰지 않게. `--allow-write` 로 켬 |
| 막는 경로 | `/@fs/` 로 시작, `.`으로 시작하는 경로 조각(`.git`, `.env` 등. `/.well-known/` 은 허용) | 프로젝트 밖·비밀 파일 |
| 요청 본문 | 10MiB | |
| 동시 스트림 | 터널당 32 | |
| 응답 헤더 | `X-Robots-Tag: noindex`, `Referrer-Policy: no-referrer` 를 덧붙임 | 검색 노출·토큰 주소 유출 방지 |
| 로그 | 쿼리 문자열 제외 | |

`/@fs/` 를 막으면 프로젝트 밖 파일을 쓰는 일부 구성(모노레포 링크 패키지 등)이 깨질 수 있다. 1단계에서는 막고, 막힌 응답에 이유를 적는다. 필요가 확인되면 프로젝트 루트 안쪽만 허용하는 방식을 검토한다.

## 8. 테스트

- 프레임 코덱: 왕복, 경계(빈 본문, 64KiB 조각, 모르는 kind).
- 게이트웨이: 가짜 기기(테스트용 WebSocket 클라이언트) + 가짜 dev 서버(JDK `HttpServer`)로 끝까지. GET·스트리밍·HEAD·쓰기 거부·막힌 경로·만료 410·회수 즉시 끊김·터널 없음 503·조각 무응답 시간 상한.
- 분기: 공유 Host 로 `/api/v1/**` 를 요청해도 컨트롤러에 닿지 않는다. API Host 로 공유 경로를 요청해도 게이트웨이를 타지 않는다.
- 저장: 실 MySQL(`SchemaTest` 포함). 토큰 평문이 DB·로그에 없다.
- CLI: Node 테스트로 Host·Origin 치환, 재연결, Ctrl+C 회수.
- 실측: Vite 앱 하나로 HMR 갱신이 받는 쪽에 반영되는지(수동 기록).

## 9. 2단계로 넓히기

- 같은 터널에 `0x51` VIDEO_CONFIG, `0x52` VIDEO_FRAME(H.264 NAL 그대로), `0x61` INPUT(탭·스와이프·키) 를 더한다.
- 뷰어 페이지는 공유 오리진의 `/__qeploy/view` 에 둔다. 브라우저는 WebCodecs 로 풀고, 입력은 같은 오리진 WebSocket(`/__qeploy/ws`)으로 보낸다.
- 조작권: 한 사람만. 기기에서 승인한다(CLI 프롬프트). 기본 꺼짐, 시간 제한.
- 영상은 받는 사람 수만큼 서버가 복제해 보낸다. 대역폭은 #649 서버 지표로 본다.

## 10. 정한 것과 남은 질문

- 정함: 서버 중계, 링크마다 하위 도메인 오리진, 기본 읽기 전용, `/@fs/` 차단, 사용자당 링크 5개.
- 남음: 받는 사람 쪽 비밀번호(선택)를 둘지, 링크 접속을 주인에게 실시간으로 알릴지(2단계 조작권 흐름과 같이 정한다).
