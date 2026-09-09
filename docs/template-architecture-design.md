# 퍼블리싱 템플릿 구조 설계

## 목표

사용자가 **어떤 디자인을 원하는지 모를 때** 템플릿을 골라 그대로 만들고, 이후 내용만 바꾸거나
디자인을 조금 손보게 한다. 고르기 **전에** 실제로 클릭해보며 어떻게 생겼는지 확인할 수 있어야 한다.

## 현재 상태 — 받아서 저장하고, 아무도 안 읽는다

템플릿은 이미 API 에 있지만 **아무 일도 하지 않는다.**

| 항목 | 현재 |
|---|---|
| `startMode` | `blank` \| `template` — 검증됨 |
| `templateType` | 슬러그 형식만 검증(`[a-z0-9][a-z0-9_-]{0,49}`). **카탈로그 대조 없음** |
| 저장 | `projects.template_type` 에 저장됨 |
| 소비 | **없음.** 읽는 코드가 하나도 없다 |
| 실제 동작 | `CodeAgentService` 가 백지에서 전부 생성 |

즉 사용자가 템플릿을 고르면 **조용히 무시되고 백지 생성된다.** `ProjectCreationService` 주석이
이 사실을 명시하고 있다("값은 프로젝트 행에 저장되지만 아무도 읽지 않는다").

따라서 이 작업은 새 기능 설계가 아니라 **뚫린 구멍을 메우는 일**이다. 컬럼·검증·`startMode` 골격은
이미 있으므로 스키마 변경 없이 시작할 수 있다.

## 핵심 분리: 데모 ≠ 소스

"org GH Pages 냐 / FE 냐 / BE 냐"는 **셋 중 하나를 고르는 문제가 아니다.** 템플릿은 두 역할을 하고
요구 조건이 정반대다.

| | **데모** (고르기 전에 조작) | **소스** (프로젝트에 심는 씨앗) |
|---|---|---|
| 필요한 것 | 살아서 클릭되는 URL | git 으로 꺼낼 수 있는 파일 트리 |
| 사용자별 차이 | 없음 — 모두 동일 | 사용자 프로젝트로 복사됨 |
| 비용 | 정적이면 0 | 컨테이너 안에서만 필요 |

**데모에 프리뷰 컨테이너를 쓰면 안 된다.** 컨테이너는 1 개당 1GB 상한이고 dev 박스는 RAM 3.7GB 다.
템플릿 몇 개만 둘러봐도 서버가 죽는다. 데모는 전원에게 동일하므로 정적으로 한 번만 올린다.

프리뷰 컨테이너는 **고른 이후** 사용자 사본을 띄우는 데 계속 쓴다. 두 경로는 별개다.

## 구조 — 저장소 하나

`Dvely/qeploy-templates` (public)

```
templates/
  landing-minimal/
    template.json        ← 매니페스트
    src/                 ← 실제 소스 (씨앗)
  portfolio-grid/
  shop-single/
catalog.json             ← CI 가 template.json 들을 모아 생성
docs/                    ← GH Pages 발행 대상
  t/landing-minimal/     ← 빌드된 데모
```

| 역할 | 위치 | 비고 |
|---|---|---|
| 데모 | 같은 저장소의 GH Pages · `dvely.github.io/qeploy-templates/t/<id>/` | iframe 임베드 가능 (아래) |
| 소스 | 같은 저장소 `templates/<id>/src/` | 씨딩 시 컨테이너 안에서 취득 |
| 카탈로그 | BE 가 `catalog.json` 만 읽어 서빙 | **소스는 들지 않는다** |

저장소를 템플릿마다 쪼개면 Pages 설정도 N 개, 카탈로그도 흩어지고 리뷰도 흩어진다. 하나로 둔다.

**iframe 가능 여부는 실측했다**(2026-09-10). 실제 Pages 사이트 응답에 `X-Frame-Options` 도
`Content-Security-Policy: frame-ancestors` 도 없다 — 우리 UI 안에서 조작하게 할 수 있다.
(404 오류 페이지만 제한적 CSP 를 보내는데, 이건 정상 문서와 무관하다.)

## 왜 FE·BE 가 소스를 들면 안 되는가

- **FE 보유** — 번들이 부푼다. 결정적으로 씨딩은 **서버 쪽 컨테이너 안**에서 일어나는데 FE 는
  거기에 파일을 넣을 수 없다.
- **BE 리소스 보유** — jar 가 이미 **158MB** 다. 템플릿 하나 고치려고 백엔드를 재배포하는 구조가
  된다. 템플릿 변경 주기와 서버 배포 주기는 달라야 한다.

## 씨딩 지점 — 첫 CODE 스텝

프로젝트 생성 시점이 **아니라** 첫 CODE 스텝에서 컨테이너에 전개한다.

- 생성 API 는 동기다. 거기서 네트워크·컨테이너 작업을 하면 안 된다.
- 컨테이너는 어차피 첫 작업 때 늦게 뜬다.
- 생성 시점 스캐폴딩은 **과거에 넣었다가 한 번도 실행되지 않았다.** 이유가 `ProjectCreationService`
  주석에 남아 있다(승인 정책·null 대화·`WAITING_APPROVAL` 조합으로 아무도 볼 수 없는 승인 뒤에
  영구히 남았다). 같은 실수를 반복할 자리다.

```
템플릿 소스를 /workspace/app 에 전개 (ContainerPaths.APP_DIR)
  → CODE 프롬프트가 "백지에서 만들어라" → "이걸 고쳐라" 로 바뀜
    → 수정분이 diff API(#317) 로 그대로 보임
```

#317 에서 고친 **초기 프로젝트 diff 가 여기서 값을 한다.** 사용자는 "회사 이름을 바꿔줘"라고 하고,
템플릿 대비 무엇이 바뀌었는지를 diff 로 확인한다.

## 매니페스트 (`template.json`)

```jsonc
{
  "id": "landing-minimal",
  "name": "미니멀 랜딩",
  "tags": ["landing", "one-page"],
  "stack": "vanilla",              // 파이프라인 전제와 직결 — vanilla-path 함정 참고
  "demoPath": "t/landing-minimal/",
  "srcPath": "templates/landing-minimal/src",
  "contentHints": [                // 어디가 "내용"인지 템플릿이 스스로 선언
    { "key": "brand",       "where": "index.html", "desc": "상단 브랜드명" },
    { "key": "hero.title",  "where": "index.html", "desc": "히어로 제목" },
    { "key": "cards",       "where": "index.html", "desc": "특징 카드 3개" }
  ]
}
```

`contentHints` 가 없으면 "내용만 변형"을 LLM 추측에 맡기게 된다. 있는 쪽을 권한다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/templates` | 카탈로그 목록(id·name·tags·stack·데모 URL·썸네일) |
| GET | `/api/v1/templates/{id}` | 단건 상세 + `contentHints` |

프로젝트 생성은 기존 `startMode=template` + `templateType` 을 그대로 쓰되, **검증을 카탈로그 대조로
바꾼다.** 지금은 슬러그 형식만 보므로 존재하지 않는 템플릿도 통과한다.

## 단계

1. `qeploy-templates` 저장소 + 템플릿 2~3 종 + Pages 발행
2. BE 카탈로그 API (`catalog.json` 취득·캐시) + `templateType` 검증을 카탈로그 대조로 교체
3. 첫 CODE 스텝 씨딩 + 프롬프트 분기("생성" vs "수정")
4. FE 갤러리(iframe 데모) — FE 세션 담당

## 결정

- 저장소는 **하나**, public. Pages 발행과 raw 취득이 토큰 없이 된다.
- 데모는 **정적 GH Pages**. 컨테이너를 쓰지 않는다.
- BE 는 **카탈로그만** 소유한다. 소스는 저장소에 둔다.
- 씨딩은 **첫 CODE 스텝**. 생성 시점이 아니다.

## 열린 결정

1. `templateType` 컬럼명을 유지할지. 의미는 이제 "타입"이 아니라 **카탈로그 ID** 다. 마이그레이션과
   FE 계약을 안 건드리려면 컬럼은 두고 주석으로 못 박는 쪽이 싸다.
2. 템플릿 소스 취득 방식 — raw URL 다운로드 vs 얕은 clone. 저장소가 커지면 후자가 유리하다.
3. 썸네일 생성 — 수동 캡처 vs CI 자동 스크린샷.

## 저작권

실제 상용 사이트를 본떠 만들면 문제가 된다. 레이아웃보다 **브랜드명·로고·카피·이미지**가 위험하다.
직접 제작하거나 MIT/CC0 소스를 쓰고, 템플릿 이름도 실제 브랜드를 연상시키지 않게 짓는다.
