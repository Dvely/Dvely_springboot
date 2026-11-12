# 내 기기 프리뷰 공유 설계 (앱 화면 미러링 + 제어)

> 기준일: 2026-11-12 · 기준 커밋: 67c1ff1a(develop) · 이슈 #652 · 서버 재호스팅 #649
> 구현 상태: 설계. 1단계(웹 화면) PR 들은 이 문서를 기준으로 한다

---

## 목표

내 기기에서 도는 **앱 화면**을 웹 링크로 공유하고, 보는 사람이 같은 화면을 보면서 **제어까지** 할 수 있게 한다.

```
$ qeploy share web http://localhost:5173
보기 링크:   https://k3x9…q2.share.qeploy.com          (남에게 줄 링크, 1시간 뒤 만료)
내 제어 링크: https://k3x9…q2.share.qeploy.com/#c=…     (나만 쓰는 링크, 바로 제어)
Ctrl+C 로 공유를 끝냅니다.
```

- **나 혼자 쓸 때**: 다른 컴퓨터나 폰에서 내 기기의 앱 화면을 보고 조작한다.
- **남에게 줄 때**: 받은 사람은 설치 없이 브라우저에서 같은 화면을 본다. 조작권을 요청하면 내가 기기에서 승인한다.
- **모두 같은 화면을 본다.** 누가 조작하면 다른 사람 화면에도 그대로 보인다. 에이전트가 코드를 고치면 핫리로드로 바뀐 화면이 모두에게 보인다.

### 비교 대상과의 차이

| | 보기 | 제어 | 설치 | 공유 범위 |
|---|---|---|---|---|
| 디스코드 화면 공유 | ○ | × | 앱·계정 | 화면·창 |
| 줌·팀즈 | ○ | ○(데스크톱 앱끼리) | 양쪽 앱, 회의 참가 | 화면·창 |
| Chrome 원격 데스크톱 | ○ | ○ | 호스트 쪽 설치 | 컴퓨터 전체 |
| **Qeploy** | ○ | ○(조작권 한 사람, 주인 승인) | 공유하는 쪽 CLI 만. 보는 쪽은 링크 | **개발 중인 앱 화면만** |

컴퓨터 전체 화면은 다루지 않는다(2026-11-12 결정). 링크 하나가 컴퓨터 전체를 조작하게 되면 사고의 범위가 너무 크고, 그 기능은 Chrome 원격 데스크톱이 이미 무료로 한다. 앱 단위로 묶으면 **입력이 그 앱 안에서 끝난다.**

### 단계

| 단계 | 대상 | 화면 | 제어 |
|---|---|---|---|
| **1** | 웹(dev 브라우저) | CLI 가 띄운 Chrome 을 DevTools 프로토콜(CDP) 화면 전송 | CDP 입력 이벤트 |
| 2 | Android 에뮬레이터 | scrcpy H.264 | scrcpy 제어 메시지 |
| 3 | iOS 시뮬레이터(macOS) | serve-sim 또는 AXe | AXe 탭·입력 |
| 나중 | 로컬 웹 직접 열기 | 받는 사람 브라우저에서 따로 열림(HTTP 중계, §10) | 각자 |

1단계를 웹으로 하는 이유: 화면(JPEG 프레임)·입력(CDP)이 가장 단순해서 공통 기반(터널, 뷰어, 조작권, 링크)을 먼저 검증할 수 있다. 2·3단계는 화면 인코딩과 입력 변환만 바꾼다.

## 1. 왜 서버 중계인가

서버리스는 보류됐고 서버(#649, Oracle 도쿄)가 있다. 1단계는 WebRTC P2P 대신 서버 중계로 간다.

| | 서버 중계 (채택) | WebRTC P2P |
|---|---|---|
| 구현 | WebSocket. Spring·Node 기본 기능 | 신호 서버 + Node DataChannel(네이티브 의존) + TURN |
| 여러 명이 보기 | 서버가 프레임을 복제해 나눠 줌 | 보는 사람마다 연결 |
| 비용 | 서버 아웃바운드. Oracle Always Free 월 10TB | TURN 1,000GB 무료 뒤 과금 |
| 지연 | 기기 → 도쿄 → 보는 사람(한국 기준 왕복 약 37ms 추가) | 직접 연결이면 더 짧음 |

화면 전송이 대역폭을 크게 쓰면 그때 P2P 를 더한다. 프레임 형식(§4)은 전송 수단과 독립적으로 둔다.

## 2. 구조

```
보는 사람 브라우저 ──HTTPS──▶ <토큰>.share.qeploy.com/__qeploy/view   (서버가 내보내는 뷰어 페이지)
                  ──WSS────▶ <토큰>.share.qeploy.com/__qeploy/ws     (프레임 받기, 입력 보내기)
                                        │
                                Spring Boot  공유 세션(링크 → 기기 연결, 보는 사람들, 조작권)
                                        │
qeploy CLI(내 기기) ◀── WSS 하나(기기가 먼저 연결, PAT) ──┘
   └─▶ Chrome(임시 프로필) ── CDP ──▶ http://localhost:5173
```

- **서버**: 새 패키지 `com.example.dvely.deviceshare`(4계층). 기존 구역과 섞지 않는다.
- **CLI**: `@qeploy/cli` 에 `share` 명령. PAT·API 주소 설정은 `@qeploy/client` 를 쓴다.
- **뷰어**: 서버가 공유 오리진에서 정적 페이지 하나를 내보낸다. FE 저장소 작업이 없다. 링크 관리 화면은 Dvely_FE 별도 이슈.

서버 프리뷰(Docker 컨테이너, `preview` 패키지)는 그대로 둔다. 화면·문서에서는 "서버 프리뷰 / 내 기기 프리뷰"로 나눠 부른다.

## 3. 공유 오리진

### 3.1 링크마다 다른 오리진

공유 화면은 `api.qeploy.com` 에서 내보내지 않는다. 링크마다 `<토큰>.share.qeploy.com` 이다.

- **API 오리진과 분리**: OAuth 콜백이 API 오리진에 있다. 뷰어 페이지·입력 경로를 거기서 분리한다.
- **링크끼리 분리**: 쿠키·저장소가 링크마다 따로다.
- **토큰이 곧 주소**: 128비트 난수를 소문자 base32 로 쓴 26자(DNS 라벨 63자 이하). 서버는 SHA-256 해시만 저장한다.

로컬 개발에서는 `<토큰>.share.localhost:8080` 을 쓴다. Chrome·Firefox 는 `*.localhost` 를 루프백으로 푼다.

설정 키: `qeploy.device-share.base-domain`(기본 `share.localhost`, 운영 `share.qeploy.com`), `qeploy.device-share.public-scheme`(기본 `http`, 운영 `https`), `qeploy.device-share.public-port`(로컬 8080, 운영 비움).

### 3.2 Host 로 분기한다

`Host` 가 `*.<base-domain>` 이면 **공유 호스트 필터**가 처리하고 API 쪽 체인으로 넘기지 않는다. 공유 오리진에서 `/api/v1/**` 컨트롤러에 닿을 길이 구조적으로 없다.

| 공유 오리진 경로 | 처리 |
|---|---|
| `/` | `/__qeploy/view` 로 302 |
| `/__qeploy/view` | 뷰어 HTML(정적, 인라인 JS·CSS, CSP 엄격) |
| `/__qeploy/ws` | 뷰어 WebSocket. 필터가 내부 경로로 forward 하고 Spring WebSocket 핸들러가 받는다 |
| 그 밖 | 404 |

- 토큰 라벨 형식이 아니면 404, 링크가 없으면 404, 회수·만료면 410(뷰어 페이지는 안내를 띄운다).
- 필터는 Spring Security 체인보다 앞에 둔다. 공유 오리진은 인증하지 않는다(링크 토큰이 보기 자격이다).

### 3.3 운영 DNS·인증서 (#649 서버 작업)

- Cloudflare: `*.share` A 레코드, DNS only.
- 인증서: 와일드카드라 `certbot-dns-cloudflare` 로 DNS-01. DNS 편집 권한만 가진 토큰을 서버에 따로 둔다.
- nginx: `server_name ~^[a-z2-7]{26}\.share\.qeploy\.com$;` 블록. `Host` 유지, WebSocket 업그레이드 통과.

## 4. 프레임

### 4.1 기기 터널 (CLI ↔ 서버, 바이너리)

WebSocket 바이너리 메시지 하나가 프레임 하나다.

```
+--------+----------------+-----------
| kind   | stream id (BE) | payload
| 1 byte | 4 bytes        | 나머지
+--------+----------------+-----------
```

stream id 는 1단계에서 늘 0 이다(나중 HTTP 중계용 자리). JSON payload 는 UTF-8.

| kind | 방향 | payload | 뜻 |
|---|---|---|---|
| `0x41` HELLO | 기기 → 서버 | JSON `{cliVersion, kind, target}` | 연결 직후 한 번 |
| `0x42` WELCOME | 서버 → 기기 | JSON `{shareId, expiresAt}` | 받아들임 |
| `0x43` GOODBYE | 서버 → 기기 | JSON `{reason}` | 회수·만료·대체로 끊음 |
| `0x51` SCREEN_CONFIG | 기기 → 서버 | JSON `{format, width, height, url, title}` | 화면 크기·주소가 바뀔 때 |
| `0x52` SCREEN_FRAME | 기기 → 서버 | JPEG 바이트(1단계) | 화면 한 장 |
| `0x61` INPUT | 서버 → 기기 | JSON(§4.3) | 조작권 가진 사람의 입력 |
| `0x62` CONTROL_REQUEST | 서버 → 기기 | JSON `{viewerId, name}` | 조작권 요청 |
| `0x63` CONTROL_DECISION | 기기 → 서버 | JSON `{viewerId, granted, ttlSeconds}` | 주인의 결정 |
| `0x64` PRESENCE | 서버 → 기기 | JSON `{viewers, controller}` | 보는 사람 수·조작 중인 사람 |

`0x01`~`0x25` 는 나중 "직접 열기"(HTTP·WebSocket 중계, §10)에 쓴다. 프로토콜 버전은 연결 URL 의 `?v=1` 로 맞춘다.

### 4.2 뷰어 소켓 (브라우저 ↔ 서버)

- 서버 → 브라우저: 바이너리 메시지는 화면 한 장(JPEG). 텍스트 메시지는 JSON `{type: "config" | "presence" | "control" | "offline" | "ended"}`.
- 브라우저 → 서버: 텍스트 JSON. 첫 메시지 `{type: "join", name, controlSecret?}`. 그 뒤 `input`, `request-control`, `release-control`.
- **내 제어 링크**의 비밀은 URL 조각(`#c=…`)에 둔다. 조각은 HTTP 요청에 실리지 않아 서버 로그·Referer 에 남지 않는다. 뷰어 JS 가 `join` 으로만 보내고, 보낸 뒤 주소창에서 지운다(`history.replaceState`).

메시지 모양(필드 이름은 이대로 고정한다).

| 방향 | 메시지 |
|---|---|
| 브라우저 → 서버 | `{"type":"join","name":"민지","controlSecret":"…"}` (비밀은 없으면 빼기) |
| 브라우저 → 서버 | `{"type":"input","input":{…§4.3}}` |
| 브라우저 → 서버 | `{"type":"request-control"}` · `{"type":"release-control"}` |
| 서버 → 브라우저 | `{"type":"welcome","viewerId":"v3","name":"민지","hasControl":false}` |
| 서버 → 브라우저 | `{"type":"config","width":390,"height":844,"url":"http://localhost:5173/","title":"Vite App"}` |
| 서버 → 브라우저 | `{"type":"presence","viewers":2,"controller":{"viewerId":"v1","name":"주인"}}` (조작 중인 사람이 없으면 `null`) |
| 서버 → 브라우저 | `{"type":"control","state":"granted","ttlSeconds":600}` (`granted` · `denied` · `revoked` · `released` · `pending`) |
| 서버 → 브라우저 | `{"type":"device","state":"online"}` · `{"type":"device","state":"offline"}` |
| 서버 → 브라우저 | `{"type":"ended","reason":"revoked"}` (`revoked` · `expired`) |
| 서버 → 브라우저 | `{"type":"error","message":"…"}` |

- 이름은 1~20자, 비우면 서버가 `보는 사람 N` 을 붙인다. 내 제어 링크로 들어온 사람의 이름은 `주인` 이다.
- 화면 한 장(바이너리)은 `config` 를 한 번 이상 받은 뒤에 온다. 기기가 다시 연결되면 `config` 가 다시 온다.

### 4.3 입력

```json
{"type":"pointer","action":"down|move|up","x":0.42,"y":0.17,"button":"left","clickCount":1}
{"type":"wheel","x":0.5,"y":0.5,"dx":0,"dy":120}
{"type":"key","action":"down|up","key":"Enter","code":"Enter","modifiers":0}
{"type":"text","text":"안녕하세요"}
{"type":"nav","action":"back|forward|reload"}
```

- 좌표는 화면 크기에 대한 비율(0~1). 기기가 실제 픽셀로 바꾼다.
- 한글처럼 조합하는 입력은 뷰어가 `compositionend` 에서 `text` 로 보낸다. CDP `Input.insertText` 로 넣는다.
- 서버는 조작권이 없는 사람의 입력을 버린다. 초당 120개를 넘는 입력도 버린다.

### 4.4 흐름 제어

- **CDP 화면 전송은 확인(ack)을 받아야 다음 장을 보낸다.** CLI 는 터널 소켓의 보내기 버퍼가 비었을 때 ack 한다. 그래서 서버나 네트워크가 느리면 기기 쪽에서 장 수가 줄어든다.
- 서버는 보는 사람마다 "보낼 최신 한 장" 자리만 둔다. 앞 장을 아직 못 보냈으면 새 장으로 덮는다(느린 사람은 장이 빠질 뿐, 다른 사람을 붙잡지 않는다).
- 화면 한 장 최대 2MiB, 기본 최대 초당 15장.

## 5. 조작권

- 한 번에 한 사람만 조작한다.
- **내 제어 링크**(`#c=…`)로 들어온 사람은 바로 조작권을 받는다. 주인이 나 자신이기 때문이다.
- **보기 링크**로 들어온 사람은 보기만 한다. "조작 요청"을 누르면 CLI 에 승인 프롬프트가 뜬다(`y` 면 기본 10분). TTY 가 아니면 거절한다.
- 주인(CLI 또는 내 제어 링크)은 언제든 조작권을 회수한다. 조작 중인 사람이 나가면 조작권이 풀린다.
- 뷰어 화면에 "지금 조작 중: ○○" 를 늘 보여 준다.

## 6. 서버

### 6.1 데이터

`device_share_links`(Flyway 번호는 머지 직전에 정한다)

| 열 | 타입 | 설명 |
|---|---|---|
| `share_id` | BIGINT PK | |
| `user_id` | BIGINT FK | 만든 사람 |
| `kind` | VARCHAR(16) | `WEB`(1단계) · `ANDROID` · `IOS` |
| `target_label` | VARCHAR(255) | 표시용(예: `http://localhost:5173`, 에뮬레이터 이름) |
| `token_hash` | CHAR(64) UNIQUE | 보기 토큰 SHA-256 hex |
| `control_secret_hash` | CHAR(64) | 내 제어 링크 비밀 SHA-256 hex |
| `token_prefix` | VARCHAR(8) | 목록 표시용 앞 6자 |
| `expires_at` · `revoked_at` | DATETIME(6) | |
| `created_at` · `last_viewed_at` | DATETIME(6) | |

인덱스: `(user_id, created_at)`. 만료·회수된 행은 기존 보존 스윕에 7일 보관으로 붙인다. 공유 세션(연결·보는 사람·조작권)은 메모리에만 둔다(서버 한 대 전제).

로그: 연결·참가·조작권 변화·종료를 한 줄씩 남긴다(`share=`, 이벤트, 보는 사람 수). 화면 내용과 입력 내용(타이핑)은 남기지 않는다.

### 6.2 API

| 메서드 · 경로 | 인증 | 설명 |
|---|---|---|
| `POST /api/v1/device-shares` | 브라우저 · PAT(WRITE) | `{kind, targetLabel, ttlMinutes?}` → `{shareId, viewUrl, controlUrl, tunnelUrl, expiresAt}`. 평문 토큰·비밀은 이 응답에만 실린다 |
| `GET /api/v1/device-shares` | 브라우저 · PAT(READ) | 내 링크 목록(최근 50개, 연결 여부·보는 사람 수) |
| `DELETE /api/v1/device-shares/{shareId}` | 브라우저 · PAT(WRITE) | 회수. 기기에 GOODBYE, 보는 사람에게 `ended` |
| `GET /api/v1/device-shares/{shareId}/tunnel` (WS) | PAT(WRITE) | 기기 터널. 같은 링크로 새 연결이 오면 옛 연결을 GOODBYE(`replaced`) |

- `ttlMinutes` 기본 60, 최대 1440.
- 사용자당 살아 있는 링크 5개, 서버 전체 터널 50개, 링크당 보는 사람 20명. 넘으면 429(`DEVICE_SHARE_LIMIT_EXCEEDED`).
- 터널 핸드셰이크는 기존 `JwtAuthenticationFilter` 가 `Authorization` 헤더로 인증한다.

## 7. CLI (1단계: 웹)

```
qeploy share web <url> [--viewport 390x844] [--ttl 60] [--headed] [--json]
```

1. `POST /device-shares` 로 링크를 만들고 보기 링크·내 제어 링크를 출력한다.
2. 설치된 Chrome 을 찾아 **임시 프로필**(`--user-data-dir` 새 디렉터리)로 띄운다. 기본 헤드리스, `--headed` 면 창을 띄운다. CDP 포트는 루프백의 임의 포트.
3. `<url>` 로 이동하고 `Page.startScreencast`(JPEG, 품질 70, 최대 1280px) 를 시작한다. `--viewport` 면 `Emulation.setDeviceMetricsOverride` 로 모바일 화면을 흉내 낸다.
4. 터널(`tunnelUrl`)을 연다. `Authorization: Bearer <PAT>` 를 싣기 위해 `ws` 패키지를 쓴다(Node 내장 `WebSocket` 은 헤더를 못 싣는다. 버전 고정).
5. INPUT 을 CDP `Input.dispatchMouseEvent`·`dispatchKeyEvent`·`insertText`·`Page.navigate` 계열로 바꾼다.
6. 끊기면 1·2·4·8초…최대 30초 간격으로 같은 링크로 다시 잇는다. 410 이면 멈춘다.
7. Ctrl+C 면 회수하고 Chrome 과 임시 프로필을 지운다.

## 8. 안전장치

조작권을 가진 사람은 그 Chrome 을 조작할 수 있다. 1단계의 범위를 그 탭과 그 dev 서버로 묶는다.

| 위험 | 막는 방법 |
|---|---|
| 내 브라우저의 쿠키·비밀번호 | 늘 새 임시 프로필로 띄운다. 끝나면 지운다 |
| 다른 주소로 이동(내 로컬 서비스, 사내망, `file://`) | CDP `Fetch` 로 최상위 문서 요청을 가로채 `<url>` 과 같은 오리진만 허용한다. 나머지는 막고 뷰어에 알린다 |
| 파일 다운로드·업로드 | `Browser.setDownloadBehavior: deny`, 파일 선택 창 가로채 거절 |
| 브라우저 주소창·단축키 | CDP 입력은 페이지 안으로만 들어간다. 주소 이동은 `nav` 입력(뒤로·앞으로·새로고침)만 받는다 |
| 잊힌 링크 | 기본 60분, 최대 24시간. 사용자당 5개 |
| 링크 유출 | 보기만 된다. 조작은 주인 승인. 내 제어 링크 비밀은 URL 조각에만 |
| 기록 | 화면·타이핑 내용은 저장하지 않는다 |

2단계 에뮬레이터는 입력이 에뮬레이터 안으로만 가지만, 에뮬레이터는 `10.0.2.2` 로 내 기기의 localhost 에 닿는다. 조작권 기본 꺼짐·시간 제한에 더해 Android 화면 고정을 권한다(2단계 설계에서 다룬다).

## 9. 테스트

- 프레임 코덱: 왕복, 경계(빈 payload, 2MiB 넘는 장, 모르는 kind).
- 공유 세션: 가짜 기기(테스트 WebSocket 클라이언트)와 가짜 뷰어 둘로 끝까지.
  - 화면 한 장이 두 뷰어에 간다. 느린 뷰어는 최신 장만 받는다.
  - 조작권 없는 입력은 기기로 가지 않는다. 요청 → 승인 → 입력이 간다. 회수 뒤 다시 막힌다.
  - 내 제어 링크 비밀이 틀리면 보기 전용이다.
  - 회수·만료 시 뷰어에 `ended`, 기기에 GOODBYE.
- 분기: 공유 Host 로 `/api/v1/**` 를 요청해도 컨트롤러에 닿지 않는다. API Host 로 `/__qeploy/**` 를 요청해도 공유 필터를 타지 않는다.
- 저장: 실 MySQL(`SchemaTest` 포함). 평문 토큰·비밀이 DB·로그에 없다.
- CLI: Node 테스트로 입력 변환(비율 → 픽셀, 한글 `text`), 다른 오리진 이동 차단, 재연결, Ctrl+C 회수.
- 실측: Vite 앱을 폰에서 제어해 화면이 바뀌고, 소스 수정이 핫리로드로 보는 사람 모두에게 보이는지(수동 기록, 프레임 수·대역폭 함께).

## 10. 나중: 로컬 웹 직접 열기

받는 사람 브라우저가 같은 로컬 웹을 **따로** 여는 방식이다. 같은 화면을 보지는 않지만 브라우저 기능(스크롤 성능, 개발자 도구)을 그대로 쓴다. 터널에서 `0x01`~`0x25` 프레임으로 HTTP 요청·응답과 dev 서버 WebSocket(HMR)을 다중화한다. 이때는 dev 서버가 직접 인터넷에 열리는 셈이라 기본 읽기 요청만, `/@fs/`(Vite 의 프로젝트 밖 파일 경로, 2025년에 우회 취약점이 여러 번 공개됨)·점으로 시작하는 경로 차단, `Host`·`Origin` 을 `localhost` 로 바꾸기(Vite 허용 호스트 검사)를 둔다.

## 11. 정한 것과 남은 질문

- 정함: 앱 화면 단위(컴퓨터 전체 제외), 1단계 웹(CDP), 서버 중계, 링크마다 하위 도메인 오리진, 조작권 한 사람·주인 승인, 내 제어 링크는 URL 조각 비밀.
- 남음: 보는 사람 이름을 받을지(로그인 없이 자유 입력 vs 익명 번호), 소리(1단계 없음), 여러 탭.
