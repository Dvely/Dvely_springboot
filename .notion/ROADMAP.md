# Qeploy 단위(Unit) 파이프라인 로드맵

> 기준일: 2026-08-15 · 기준 커밋: 0635569 · 코드 실측 기반

이 문서는 `danto/<도메인>` 단위 작업 파이프라인의 SSOT다. 우선순위, 진행 상태, 세션 인계 정보를 이 문서 하나로 관리한다.

새 세션은 `/resume` 직후 이 문서의 "1. 현재 상태"부터 확인한다. 세션을 마칠 때는 "1. 현재 상태"를 갱신한 뒤 `/save-session`을 실행한다.

---

## 1. 현재 상태

- 2026-09-11: **성능·비용 전면 개선 — 10개 단위 완료**(Issue #335~#345, PR #346~#355). 백엔드를 다섯 영역(스키마·인덱스 / 쿼리패턴·JPA / 스케줄러·스레드풀 / 외부비용 / 요청경로)으로 나눠 코드 실측 감사한 뒤 3차에 걸쳐 처리했다. 상세는 `state.md` §4.26.

  **대표 수치(전부 실측)**: 유휴 DB 쿼리 **1,904 → 65/분(−97%)** · 프로젝트 개요의 변경 목록 **97MB → 20KB(4,684배)** · 웹훅당 `projects` 조회 **풀스캔 19,109행 → 인덱스 1행** · 동시 SSE 스트림 **2 → 상한까지** · 프리뷰 자산 20건 로드 시 세션 UPDATE **20 → 0회** · CODE 라운드 전송의 **82%가 캐시 접두** · 외부 무응답 시 스레드 점유 **무한 → 5~60초**.

  **버그 2건이 함께 잡혔다**: ① 배포·클라우드 워커가 실행기 포화(`TaskRejectedException`) 시 루프가 끊겨 같은 배치의 다음 작업이 IN_PROGRESS 인 채 리스 만료(2분)까지 방치됐다 — #55 에서 `AgentRunWorker` 만 고쳐진 형태가 남아 있었다. ② 전송 계층이 AWS SDK 의 전이 의존(Apache HttpClient5, 라우트당 5커넥션)으로 암묵 선택되고 있었다.

  **계획의 가정 3개가 실측으로 틀렸다**: 유휴 쿼리 추정 505/분(실제 1,904) · 비용의 정체가 SELECT 가 아니라 **트랜잭션 의례**(99%) · 감사 로그를 `afterCommit` 으로 미루라는 지시가 ADR-A2("롤백돼도 감사는 남는다")를 깨는 정책 변경이었다.

  **남은 것**: `#344`(프로비저닝 — STS·SDK 클라이언트 캐시, **ECR lifecycle 미설정으로 이미지가 영구 누적 중**)는 소유 구역 규칙에 따라 unhak 에게 인계·배정 완료. `Dvely_FE #86`(메시지 목록 커서 채택 — 500행 넘는 대화에서 최신 메시지를 못 본다). 백엔드 후속 2건: `llm_usage` 보존 정책, 배포 이력 목록 상한(유일하게 남은 무한 성장 지점).

- 2026-09-11: **#332(프리뷰 컨테이너 사용자 코드 격리)를 unhak 에게 인계** — 착수했다가 되돌리고 넘겼다. 이슈에 적어 뒀던 방법("root 로 워크스페이스를 만들어 넘기고 이후 exec 를 `--user node` 로")이 **실측으로 반증됐다**: `cap-drop ALL` 이 `DAC_OVERRIDE` 를 떼서 이 컨테이너의 root 는 파일 권한을 우회하지 못한다(`CapEff: c1`). 워크스페이스를 node 에게 넘기는 순간 이후 root 명령(git push·diff·씨딩)이 전부 막힌다 — **절반만 옮기는 설계는 없고 주인이 하나여야 한다.** 목표 상태 자체는 동작하는 것을 확인했다(적대적 postinstall 이 uid 1000 으로 돌고 `/etc`·`apk` 는 거부, git·npm 전 과정 정상).

  **넘긴 이유**: 위험한 부분이 전부 프리뷰 런타임(`PreviewRuntimeLauncher`·`PreviewWorkspaceService`)이고, JAVA_FULLSTACK 경로는 이쪽에서 재보지 못한 유일한 곳이다. 1단계(빌드 컨테이너 분리)만 먼저 하는 것도 검토했으나, 그 추상은 2단계를 하는 사람이 골라야 하고 `createAndStartContainer` 를 선점하면 충돌만 만든다. **비싼 부분(설계와 제약 발견)은 끝나 있고** 이슈 본문을 인계 문서로 다시 썼다 — 확정 제약 → 목표 상태 실측 → 4단계 순서 → 함정. **급하지 않다**: 실제로 위험했던 컨테이너→호스트 API 경로는 #333 에서 닫혔고 이건 심층 방어다.

- 2026-09-11: **보안 감사 — 프리뷰 컨테이너가 Qeploy API 에 닿던 경로를 닫았다** — 컨테이너 간은 막혀 있었지만 호스트로 나가는 길은 열려 있었고(코드 주석도 그렇게 적고 있었다), Spring 이 `server.address` 없이 `0.0.0.0` 에 붙어 있어 그 길 끝에 API 가 있었다. MySQL 은 이미 루프백이었고 앞단엔 nginx 가 있어 **앱만 열려 있던 것**이다. 실측으로 도달을 확인하고 루프백 바인딩으로 닫았다(바인딩 자체를 재서 검증 — 수정 후 `127.0.0.1:8099`, 되돌리면 `*:8099`). 함께: WRITE PAT 수명을 30일/최대 90일로 줄였다(READ 는 90/365 유지) — 유출 시 할 수 있는 일이 다른데 수명이 같았다. 소유권 검증·토큰 로깅은 전수 확인 결과 구멍이 없었고, 대신 **회귀 가드**(`ResourceOwnershipGuardTest`)를 뒀다 — 다음에 추가될 엔드포인트를 지킨다. 남은 몫(컨테이너 root 실행·egress 미제한)은 **#332**.
- 2026-09-11: **#330 — 빈 사이트를 초록색으로 배포하던 경로를 막았다** — 빌드가 아무것도 안 만들어도 custom domain 스텝의 `mkdir -p` 가 빈 디렉터리를 만들어 그대로 발행했다. 실패한 스텝이 하나도 없어 사용자는 어디를 볼지 알 수 없었다. 발행 경로를 러너에서 확정(`Resolve publish dir`)하고 산출물 검증을 `mkdir` **앞**에 뒀다. Nuxt 는 **실측으로 확정** — `nuxi generate`(4.5.2)는 `.output/public` 에 내고 `dist` 는 호환용 절대경로 심볼릭 링크다. 링크에 기대지 않고 실물을 가리키게 해, 링크 존재 여부와 발행 액션의 심볼릭 링크 처리라는 두 불확실성을 함께 없앴다. 재현: `scripts/verify-deploy-publish-dir.sh`.
- 2026-09-11: **#116·#117·#325 처리, #327 not planned — Danto 이슈 전부 정리** — **#116**: `vue-cli`·`sveltekit`·`gatsby`·`astro` 를 Next.js 의 감싸기 방식으로 바꾸고 분기가 없던 `nuxt` 를 추가했다. 이 다섯은 Qeploy 가 스캐폴딩하지 않아 **연결된 저장소로만** 들어오는데, 그 저장소에는 스캐폴더가 써 둔 config 가 반드시 있어 경고 분기가 사실상 유일한 경로였다. 확장자만으로 모듈 종류를 정하면 SvelteKit 에서 깨진다(`.js` 인데 ESM)는 것을 잡아 `package.json` 의 `type` 까지 본다. 생성된 셸을 가짜 저장소에서 실제로 돌려 검증했다(`scripts/verify-deploy-base-override.sh`). **#117**: CODE 프롬프트에 basename 고정 금지를 넣어 발현 자체를 막았다 — 완전 해법인 프리뷰 오리진 분리는 백로그에 남는다. **#325**: `CodingAgentWorkspaceBridge` 로 두 워크스페이스 모델을 잇고, 제공자 목록을 사용자 키 기준으로 갈랐다. `DockerClient` 가 스프링 빈이 아니라는 것을 컨텍스트 로드 82개 실패로 배웠다. **#327**: 붙여넣기로 충분해 not planned — 다만 `GITHUB_OAUTH_REDIRECT_URI` 가 아무도 안 읽는 죽은 설정임이 드러났다.
- 2026-09-10: **`qeploy login` — 발급 창구를 CLI 안에 만들어 FE 의존을 끊었다** — MCP·CLI 를 다 만들어 놓고 열쇠만 없는 상태였다(PAT 발급 창구가 웹 UI 뿐). 브라우저 JWT 를 한 번 받아 PAT 로 바꾸고 `~/.config/qeploy/config.json`(0600)에 저장한다. **CLI 와 MCP 가 같은 파일을 읽어** 로그인 한 번으로 둘 다 풀린다 — 에이전트는 `npx @qeploy/mcp` 로 실행되어 환경변수를 받을 자리가 없다. 실측으로 `redirect_uri` 를 GitHub 에 한 번도 보내지 않는 것을 확인했고(그래서 루프백 불가), FE 가 `state` 를 검증해 기존 콜백에 얹는 방법도 취약함을 확인해 브라우저 왕복은 **#327** 로 분리했다. `GITHUB_OAUTH_REDIRECT_URI` 가 선언만 되고 아무도 안 읽는 죽은 설정이라는 것도 이때 드러났다. 실 서버 검증(실제 JWT → 발급 → 환경변수 없이 CLI·MCP 동작 → logout), JS 테스트 77개.
- 2026-09-10: **PR #306 머지 (develop `d8237b7`) + Danto 이슈 8건 분류 완료** — PAT·MCP·CLI 가 develop 에 들어갔다(V60). `base` 가 `develop` 이라 `Closes` 키워드가 자동 발동하지 않아 #304 는 수동으로 닫았다 — 이 저장소에서는 앞으로도 그렇다.

  이슈 분류 결과: **#304 종료**(완료), **#207 종료**(운영에서 GLM 정상 동작 확인 — 코드는 처음부터 다 있었고 서버 설정만 남아 있던 건이다), **#116·#117 유지**(둘 다 실재하지만 병렬 작업자 구역이라 현황만 좁혀 기록), **#325 유지**(신규). FE 는 **#76 착수 가능**(백엔드 머지로 막힌 것이 없어졌고, 토큰 발급 창구가 웹 UI 뿐이라 이게 없으면 MCP·CLI 를 아무도 못 쓴다), **#54 보류**(#325 배선 전에 만들면 아무 일도 안 하는 키를 등록하게 된다), **#45 범위 축소**(파싱이 깨지던 진짜 위험은 `aiProviderSchema` 가 열린 문자열로 바뀌며 해소됐고, 남은 `model`·`thinking` 은 모델 선택 UI 의 선행 작업이다).

- 2026-09-10: **PR #306 CI 성공 · 머지 가능 + 이슈 정리** — 충돌·번호 밀림을 해소하고 로컬 전체 통과, CI 도 성공(`CLEAN`). `Closes #304` 를 붙여 머지가 이슈를 닫게 했다. 미해결 이슈 둘은 코드로 재확인해 범위를 좁혀 기록했다 — **#116** 은 Next.js 가 감싸기 방식으로 해소됐고 `vue-cli`·`sveltekit`·`gatsby`·`astro` 4개와 분기 자체가 없는 `nuxt` 가 남았다. **#117** 은 미해소인데, `absorbBuildBasePath`(#111)가 자산 경로를 흡수해서 덮인 것처럼 보일 수 있어 선을 그어 뒀다(라우터는 서버 응답이 아니라 `location.pathname` 을 본다). 둘 다 `DeployWorkflowTemplate`·`CodeAgentService` 등 병렬 작업자가 최근 3일 내 만진 파일이라 구현은 넘기고 현황만 남겼다. **#325** 를 새로 만들었다 — BYOK 코딩 에이전트가 머지됐으나 CODE 스텝이 부르지 않는 휴면 상태다. **#207** 은 운영 서버 pm2 에 GLM 키를 넣는 작업이라 사용자 조치가 필요하다(코드는 이미 다 있다).
- 2026-09-10: **PR #306 을 develop 에 맞춰 정리 — 마이그레이션이 또 밀렸다(V56 → V60)** — develop 이 V56~V59 를 가져가는 사이 PAT 마이그레이션이 V56 에 앉아 있었다. git 은 파일명이 다르니 조용히 통과하고 CI 만 중복 버전으로 죽는, AGENTS.md 가 경고하는 바로 그 형태다. `api.md` 도 양쪽이 `## 17.` 을 새로 붙여 충돌했다 — develop 의 Template 을 §17 로 두고 ApiToken 을 §18 로 밀었다. 번호가 겹치면 "§17 을 보라"가 두 곳을 가리켜 문서를 못 쓰게 된다.
- 2026-09-08: **`@qeploy/cli` 완료 — 설계 5단계까지 끝, 남은 건 FE 화면** — MCP 를 못 쓰는 에이전트·CI·사람용 CLI. 읽기 8 + 쓰기 3. 종료 코드를 실질적 인터페이스로 두고 인증 실패(3)를 일반 실패(1)와 분리했다 — 토큰 만료와 빌드 실패는 파이프라인의 대응이 다르다. 비대화형에서는 확인을 묻는 대신 즉시 거절하고, stdin 이 닫혀도 매달리지 않는다(종료된 스트림에서 `readline.question()` 이 resolve 도 reject 도 하지 않는 것을 실측). 실 서버 검증이 mock 이 통과시킨 결함을 잡았다 — 배포 확인 프롬프트가 개요 응답의 `name` 을 읽었는데 그 필드가 존재하지 않았다. JS 테스트 60개, `agent-tools/e2e-cli.sh`. FE 발급 화면은 `Dvely_FE` #76 으로 넘겼다.
- 2026-09-08: **PR #306 생성 — PAT + MCP 서버(읽기 11 · 쓰기 6)** — 쓰기 도구를 붙이고 PAT 와 한 PR 로 묶었다. MCP 는 PAT 없이 인증이 성립하지 않아 나눠 머지할 실익이 없다. 쓰기 방어는 세 층이다: 기본 비활성(`QEPLOY_ENABLE_WRITES`), 서버 스코프 403(클라이언트 우회·플래그 무관), 기존 승인 게이트. 되돌리기 어려운 조작은 플래그와 무관하게 미노출이고 테스트가 부재를 못박는다. 실 stdio 로 기본 11개/활성 17개·READ 토큰 거부·WRITE 토큰 인가 통과(404)까지 확인했고 실제 배포는 트리거하지 않았다. 다음은 `@qeploy/cli`(5단계)와 FE 발급 화면.
- 2026-09-08: **PR #245 머지 + PAT + 읽기 전용 MCP 서버 구현 완료(미머지)** — MCP 서버가 실제 stdio 프로토콜로 동작하는 것까지 확인했다(도구 11개 등록, 실 API 왕복, 없는 리소스는 프로토콜 예외가 아니라 에러 결과로 반환, 토큰 없으면 종료코드 1 + stdout 미오염). `agent-tools/` 워크스페이스에 `@qeploy/client`·`@qeploy/mcp` 를 두었고 JS 테스트 27개가 통과한다. 쓰기 도구 6개도 붙였으나 기본 비활성이고(`QEPLOY_ENABLE_WRITES`), READ 스코프 토큰은 서버가 403 으로 막는 것을 실측 확인했다. 지금은 이 저장소 안에 있고, 공개 저장소 분리·npm 배포는 오픈소스 공개를 결정할 때 한다. 다음은 쓰기 도구(4단계)다.
- 2026-09-08: **PR #245 머지 완료 + PAT 단위 구현 완료(미머지)** — #245 가 develop `cb7c5a5` 로 머지되어 BYOK 코딩 에이전트가 반영됐다(Issue #242 close). 이어서 MC 단위의 선행 요건인 PAT 를 구현했다: `apitoken` 도메인(V60), `qp_` 접두사 필터 분기, HTTP 메서드 기반 스코프, 엔드포인트 3개. 로컬 서버를 실제로 띄워 HTTP 10건 전수 검증(발급·폐기·스코프 403·만료 401·평문 부재·상한 400). 테스트 1347개 통과. 브랜치 `danto/agent-pat`, Issue #304. 다음은 `/client` → MCP 서버(읽기 전용)다.
- 2026-09-07: **MC 단위 설계 완료(미착수)** — 에이전트 연동(MCP 서버 + npm CLI). 구독 연결을 여러 형태로 검토한 끝에 방향을 뒤집었다: Qeploy 가 AI 를 부르는 게 아니라 **사용자의 AI 가 Qeploy 를 부른다**. 그러면 Qeploy 는 에이전트가 쓰는 도구가 되어 컴플라이언스 이슈가 소멸하고 이 경로의 AI 비용도 0 이다. 검토·기각한 안: ① 구독 자격증명 서버 라우팅(Anthropic 공식 금지 + 2026-01 부터 서버 차단, OpenAI 도 제3자 구동·재판매 금지) ② 확장으로 로컬 호출 후 결과를 서버로(실행 위치가 아니라 "무엇을 구동하나"가 기준이라 동일하게 금지) ③ 로컬 컴패니언(회색지대는 피하나 제품이 로컬 앱이 되어야 함 — draft 만 두고 폐기). 선행 과제는 PAT 하나뿐이다(현행 JWT 1시간). 설계 `docs/qeploy-mcp-cli-design.md`, PRD 부록 A-2, `srs.md` §B, ROADMAP MC 행.
- 2026-09-05: **CA 단위 머지 준비 완료** — Issue #242 / **PR #245**(CI 성공, MERGEABLE), 브랜치 `danto/coding-agent-byok`, 커밋 10개, `origin/develop` `9149d93` 위로 rebase 완료. **테스트 1168개 통과, 실패 0.** Codex 경로 end-to-end(인증→실행→파일 편집)를 **Java 코드 경로를 통과하는 게이트형 통합 테스트**(`-Ddocker.it=true`)로 고정 — 그 과정에서 OkHttp 전송의 exec stdin 하이재킹 결함을 잡아 archive 업로드 방식으로 수정(`state.md` §4.21). 머지는 사용자 판단 대기.
  - 외부 AI 코딩 에이전트 개인계정 연동을 **BYOK**(사용자 본인 공식 API 키)로 확정. 구독 임베딩은 Anthropic 공식 금지(2026-02-20 명문화, 04-04 시행)·OpenAI 미지원 확인으로 폐기.
  - 정본: PRD 부록 A-1, `srs.md` 신설, `docs/byok-coding-agent-design.md`, `api.md` §16, `state.md` §4.21.
  - 구성: `aiaccount` 도메인(V45) + `CodingAgentPort` + CLI 어댑터 2종 + 전용 이미지 + 엔드포인트 3개.
  - **마이그레이션 번호 교훈(두 번 밀림)**: V43 예약 → develop 이 V43(#241)·V44(#244)를 연속 선점 → V45. CI 는 develop 과 합친 머지 커밋을 빌드하므로 로컬은 초록인데 CI 만 Flyway 중복 버전으로 죽었다. 규칙을 "착수 시 예약"에서 "머지 직전 확정"으로 바꿈. 파일명이 달라 git은 조용하고 Flyway만 죽는 충돌이라, 다음 단위는 착수 직전에 `ls db/migration | sort -V | tail -1`로 실제 최신을 다시 확인할 것.
  - 후속(범위 밖, `state.md` §4.21): CODE 스텝 배선(워크스페이스 모델 상이), 키 실검증(FR-4), CLI 비대화 인자 실측.
- 2026-08-15: **프리뷰 정상 동작 확인 + 콘솔 정리** — 사용자 브라우저에서 프리뷰 화면 정상 확인. 남아 있던 `/cdn-cgi/rum` CORS 에러는 Cloudflare가 zone HTML에 자동 주입하는 RUM beacon이 sandbox 불투명 오리진에서 차단되는 것이었고(우리 서버 미도달), Issue #113 / **PR #114 머지·배포**(main `47bb5fb`)로 프리뷰 HTML에만 `no-transform`을 붙여 주입 자체를 차단. 737/737. `state.md` §2.33.
- 2026-08-15: **프리뷰 빌드 base 흡수** — Issue #111 / **PR #112 머지·배포** (main `0635569`), 브랜치 `danto/preview-base-absorb`. §2.31로 CORS·인가 층을 뚫자 드러난 다음 층 — 앱이 Pages 배포용 base(`/{repo}/`)로 빌드되면 자산 참조가 `{prefix}{base}/assets/...`가 되는데 컨테이너는 산출물 루트를 서빙해 `serve -s` fallback(200+HTML)이 오고 MIME 거부로 백지. 프록시가 흡수: 확장자 있는 자산에 HTML이 오면 선행 세그먼트를 벗겨 재요청(최대 2단계, 실패 시 원안 유지). 배포는 `vite build --base=` CLI가 config를 덮어쓰므로 base 변경 없이 프리뷰만 고침. 736/736 + 뮤테이션 실험 + 실제 `serve@14 -s` 실측. **재빌드 안은 폐기** — 다중 에이전트 심사에서 `--emptyOutDir`가 사용자 소스를 지운 채 push되는 경로 확인. `state.md` §2.32.
  - **확인 완료(2026-08-15)**: 사용자 브라우저에서 프리뷰 화면 정상 렌더 확인. 남은 콘솔 에러는 CDN beacon 건뿐이었고 Issue #113으로 해소.
  - 후속 이슈 후보: `pkill -f 'npx serve'` 미작동(옛 산출물 서빙 위험) · Next/SvelteKit/Gatsby/Astro/Nuxt의 커밋된 base가 커스텀 도메인 배포에서 자산 404 유발 · React Router `basename={BASE_URL}` 사용 시 프리뷰에서 빈 화면.
- 2026-08-15: **프리뷰 백지 수정 — 서브리소스 인가·CORS** — Issue #108 / **PR #109 + PR #110 머지·배포** (main `98e104a`), 브랜치 `danto/preview-subresource-authz`·`danto/preview-cors-null-origin`. §2.29 sandbox(불투명 오리진)×§2.30 소유권 쿠키의 상호작용 3중: ① 서브리소스에 쿠키 미탑재 → 401 ② module script CORS 로드에 ACAO 부재 ③ 전역 CORS(`/**`)가 `Origin: null`을 컨트롤러 도달 전 403 거절(운영 curl로 확정). 수정: 쿠키는 문서 탐색(`Sec-Fetch-Dest` 탐색 계열·헤더 부재)에만 요구, 서브리소스는 회전 토큰이 자격, `/api/v1/previews/**` 전용 무자격 CORS를 `/**`보다 먼저 등록(프록시 수동 ACAO는 중복 방지를 위해 제거). 731/731. **배포 후 운영 curl 재검증**: 서브리소스 200+ACAO 단일 / 문서 탐색·무헤더 curl 401 유지. 잔여 위험(현재 토큰 URL 보유자의 서브리소스 열람, 회전으로 창 제한)은 수용 — 완전 해소는 프리뷰 전용 오리진 분리(백로그). `state.md` §2.31.
- 2026-08-15: **운영 서버 프리뷰 Docker 권한 해소** — SSH 접근 불가 상황이라 GitHub Actions로 조치. `ops-restart-pm2-daemon.yml`(수동 실행 전용, PR #105→#106→#107 머지, main `53f4982`) 신설 — 배포 워크플로와 같은 시크릿 SSH로 `deploy/README.md` §4 전체를 멱등 적용(docker 설치/기동 확인 → `usermod -aG docker` → `pm2-ubuntu` systemd 데몬 재기동) 후 health + `/proc/<앱 pid>/status`의 docker gid 보유로 검증.
  - 실측: 서버에 docker는 설치·기동돼 있었으나 ubuntu 계정이 docker **그룹에 아예 미등록**이었다(README §4의 "usermod는 적용됨" 전제가 틀려 있었음). usermod 적용 + 데몬 재기동으로 해소.
  - 최종 확인(run 31848363267): 앱 프로세스가 gid 114 보유, 앱 자체 핑 `[PreviewEnv] Docker 연결 정상 · 프리뷰 기준 오리진 = https://qeploy.com`. **운영 프리뷰 컨테이너 실행 가능 상태.**
  - `Dvely_FE` PR #30(access 발급 연동)도 같은 날 머지·배포 완료(main `1717c69`, Deploy to EC2 성공) — 백엔드 인가·Docker 권한·FE 연동까지 **운영 프리뷰 전 구간 복구 완료**. 실사용 스모크 확인만 남음.
- 2026-08-15: **프리뷰 게이트웨이 인가 완료 (AG)** — Issue #77 / **PR #104 머지** (main `113abf0`), 브랜치 `danto/preview-gateway-authz`.
  - G2: `POST /preview-sessions/{sessionId}/access`(신규, 공개 API 93→94)가 소유자 확인 후 `HttpOnly` 소유권 쿠키(`Path=/api/v1/previews/{sessionId}/`, HMAC 서명 — DDL 없음) 발급. 게이트웨이는 쿠키 없음/불일치 시 **401**. 호환 스위치 `qeploy.preview.require-access-cookie`(기본 true)
  - G4: 같은 발급 호출이 accessToken을 회전 — 이전 주소(작업 응답 previewUrl 포함)는 즉시 404, 유출 주소 수명이 "소유자가 다음에 여는 시점"으로 제한
  - 726/726 통과. `state.md` §2.30, `docs/FRONTEND_API_GUIDE.md` §4.10 갱신
  - **FE 연동: `Dvely_FE` PR #30 머지·배포 완료** (`feature/preview-access-grant`, main `1717c69` — iframe 표시 직전 access 발급, 회전 previewUrl 사용, 자동 refetch 차단, `getPreviewProxyPath` 제거)
  - 잔여: 프리뷰 전용 오리진 분리(§2.29 운영 한계 — 그때 쿠키 `SameSite=None` 전환 필요)
- 2026-08-15: **프리뷰 오리진 격리 완료** — Issue #102 / **PR #103 머지** (main `21ed39e`), 브랜치 `danto/preview-csp-sandbox`.
  - 게이트웨이 프록시 응답에 CSP `sandbox`(+ `frame-ancestors 'self'`) 적용. `allow-same-origin` 미포함이 핵심 — 프리뷰 코드가 부모 창 `localStorage`의 서비스 JWT를 읽던 경로를 닫았다
  - FE 변경 없이 서버 헤더만으로 적용. 710/710 통과
  - 잔여: 게이트웨이 인가(#77 G2)·accessToken 회전(#77 G4), 프리뷰 전용 오리진 분리
- 2026-08-15: **배포 문서 보강** — Issue #100 / **PR #101 머지** (main `b4169d0`). `usermod -aG docker` 후 pm2 데몬까지 재시작해야 적용된다는 점을 `deploy/README.md` §4에 명시(운영 서버 Permission denied 실사례).

- 2026-08-15: **프리뷰 배포 환경 보정 완료** — Issue #95 / **PR #96 머지** (main `8ba8c11`), 브랜치 `danto/preview-gateway-origin`.
  - `PreviewGatewayUrlResolver`: 기준 오리진을 설정값 → CORS 허용 오리진 → 로컬 기본값 순으로 해석(루프백 제외), 운영 기본값 `https://qeploy.com`. 그동안 `previewUrl`이 `http://localhost:8080/...`으로 발급되던 문제 해소 — 작업 프리뷰도 함께 고쳐짐
  - Docker 실행 환경 실패를 catch-all 500 → **503 `PREVIEW_ENVIRONMENT_UNAVAILABLE`** + 원인 메시지로 분리, 기동 직후 데몬 핑 경고(`PreviewEnvironmentHealthLogger`)
  - 707/707 통과. **운영 잔여**: 운영 서버 Docker 설치·소켓 권한은 코드로 해결 불가 — `deploy/README.md` §4 절차 필요
- 2026-08-14: **프로젝트 단위 프리뷰 완료** — Issue #94 / **PR #92 머지** (main `40fe80a`), 브랜치 `danto/project-scoped-preview`, V31.
  - `GET`/`POST /projects/{id}/preview-session`: 진입 시 조회(200/204), 버튼으로 생성(200 연결 / 202 준비 / 409 저장소 미연결). `task_id` NULL이 프로젝트 단위 세션의 식별자
  - 세션 상태에 `PROVISIONING`·`FAILED` 추가, 준비는 `previewExecutor` 비동기, 실패 사유는 빌드 로그 꼬리와 함께 `failure_reason`에 보존
  - clone/build/serve 절차를 `PreviewWorkspaceService`로 분리해 CODE 스텝과 공유. 700/700 통과
  - 후속 후보: 서빙 중 세션 갱신(`?refresh=true`), 빌드 로그 실시간 스트리밍, 감사 로그 액션 추가 여부(설계 결정)

- 2026-07-17: **U0 완료** — gitignore 정리(Issue #34/PR #36 머지) + `.notion` 전 문서 개정 + 이 문서 신설.
- 2026-07-17: **U1 완료** — FE 회귀 검증 및 결함 수정.
  - QA: `./gradlew test` 143/143 기준선, 41/58 엔드포인트 실측(~71%), 결함 6건 발견. 리포트: `Dvely_springboot/.agent-team/11-qa/fe-regression-report.md`
  - 백엔드 수정: Issue #37 / **PR #38 머지** (main `8bfe8d8`) — 경로변수 타입 오류 500→400(`GlobalExceptionHandler`), Chat sendMessage Swagger 정정, 로그 새니타이즈. 최종 146/146 통과, 코드리뷰 APPROVE(`.agent-team/10-review/u1-review.md`)
  - FE 수정: Dvely_FE_test **PR #4 머지** (main `d5e761d`) — 타입 정합화(DomainBinding 계약·TaskStatus·VALIDATED·필드 보강), `API_TEST_FLOW.md` 휴지통 7일 정정
  - 미검증 잔여(다음 관련 단위에서): OAuth 브라우저 플로우(수동 확인 필요), 실배포/도메인 연결/웹훅/프리뷰 실행 계열(외부 부작용으로 스킵), GCP·AWS Role ARN 경로
- 2026-07-17: **U2 완료** — Agent CHAT 스텝 구현 (Issue #39 / **PR #40 머지**, main `f6f9de0`).
  - `ChatAgentService` 신설: LlmRouter 경유 대화 응답, 대화 맥락 주입(중복 user 턴 제거 + Anthropic user-first 계약 방어), `handleChat` 스텁 해소
  - `MessageResponse.taskId`(nullable) 노출 — U1 D-DOC-1 이관분 해소. FE 반영: Dvely_FE_test **PR #5 머지** (main `7bc5c4f`)
  - 테스트 155/155, 리뷰 APPROVE(`.agent-team/10-review/u2-review.md`, F1·F2 동일 PR 해소)
- 2026-07-18: **U3 완료** — Environment/Secrets 도메인 (Issue #41 / **PR #44 머지**).
  - `environment` 도메인 신설: CRUD 4 + 이력 조회 1 = 엔드포인트 5개(목록/생성/수정/삭제/이력), 값은 secret 여부와 무관하게 AES-256-GCM 전체 암호화 저장(V22), secret=true는 모든 응답에서 value=null로 완전 은닉, PREVIEW/PRODUCTION 스코프만 존재(COMMON 없음, 설계상 의도적 보류)
  - `EnvironmentValueResolver`는 애플리케이션 내부 port로만 존재하며 HTTP로 노출되지 않음(Preview/Deployment 실주입은 후속)
  - 리뷰에서 확정된 5건 수정 완료(값 미저장 이력 설계 포함 secret 오라클 차단)
- 2026-07-18: **U5 완료** — Repository Settings (Issue #43 / **PR #46 머지**).
  - `GET /projects/{id}/settings/repository`(연결 정보+실시간 defaultBranch 조회, 미연결도 200), `DELETE /projects/{id}/repository`(비파괴 연결 해제, GitHub API 호출 0회 — GitHub 저장소/workflow/Pages는 그대로 유지)
  - V23(`repository_connected_at`)
  - 동시성 lost-update(웹훅 head-sync와의 동시 쓰기 경합)는 **Issue #45**로 별도 분리, 이번 단위 범위에서 제외
- 2026-07-18: **U4 완료** — Preview 운영 API + 격리 정책 (Issue #42 / **PR #47 머지**).
  - `GET /preview-sessions/{id}/status`(컨테이너 실행 여부·리소스 사용량, 종료 세션도 200/containerRunning=false), `GET /preview-sessions/{id}/logs`(tail 기본200·[1,2000] 클램프, 비영속)
  - BI-194 격리 정책(`DockerContainerService`): 메모리 1GiB(+swap 동일 상한) · CPU 1.0 vCPU · pids 256 · `capDrop(ALL)` + `capAdd(CHOWN,SETUID,SETGID)` · `no-new-privileges` · 전용 브리지 네트워크 `qeploy-preview`(`enable_icc=false`로 컨테이너 간 통신 차단)
  - `docker-java` 3.3.6 → 3.7.1 업그레이드(최신 Docker Engine의 capability 응답 역직렬화 결함 대응, 버전 하한을 회귀 테스트로 고정) — DB 스키마 변경 없음
- 2026-07-18: **U6 완료** — 배포 실패 복구 (Issue #48 / **PR #50 머지**, V24).
  - `POST/GET /deployments/{id}/failure-analysis`: 온디맨드·멱등(이미 분석이 있으면 LLM 재호출 없이 그대로 반환), GitHub Actions 로그에서 최대 12,000자 발췌 후 시크릿 패턴 레닥션, LLM 60초 타임아웃 + 실패 시 룰 기반 fallback으로 항상 응답
  - `POST /deployments/{id}/retry`: `201`, 실패(FAILED) 이력만 대상(아니면 409), 새 `DeploymentHistory`를 생성하고 `retriedFromHistoryId`로 원본과 연결(원본은 감사 목적으로 보존), 승인 없이 즉시 재큐잉(직접 Deployment API와 동일하게 Approval 미적용)
  - 리뷰 확정 7건 수정(분석 중복 호출 차단, 시크릿 레닥션, LLM timeout degrade 등)
- 2026-07-18: **U7 완료** — 인프라 설정 저장 + INFRA_OPERATION 승인 (Issue #49 / **PR #51 머지**, V25).
  - `GET/PUT /projects/{id}/settings/infrastructure/configuration` + `GET .../configuration/history`: `deploymentArchitecture`(SERVER/CONTAINER/SERVERLESS) · `computeTier`(MICRO/SMALL/MEDIUM/LARGE) · `storageType`(NONE/OBJECT_STORAGE) · `networkAccess`(PUBLIC/PRIVATE) 4개 provider-중립 enum
  - CONNECTED 클라우드 연결이 없으면 설정 불가(`configurable=false`), 저장 시 프로젝트당 `PENDING_APPROVAL` 변경 1건만 허용(동시 대기 요청은 409), 현재 값과 완전히 같은 요청은 이력·승인 없이 no-op
  - `approvals.task_id`를 NULL 허용으로 변경해 **standalone 승인**(Agent task 없이 API에서 직접 생성되는 승인) 도입, `StandaloneApprovalHandler` SPI + `InfrastructureChangeApprovalHandler` 구현으로 INFRA_OPERATION 승인의 승인/거절 후속 처리를 프로젝트 도메인이 소유
  - 승인 결정(approve/reject)에 `@Lock(PESSIMISTIC_WRITE)` 비관적 잠금 적용해 동시 승인/거절 경합 방지
  - 오토스케일링/로드밸런서/DB 설정(BI-125~127)은 BACKLOG_STATUS.md Removed 지침대로 이번 단위 범위에서 명시적으로 제외
  - 리뷰 확정 7건 수정(승인 결정 비관적 잠금, 저장 전 전제조건 재검증 등)
- 통합 main 테스트 **345/345**, Flyway V1~V25 연속 (PR #51 머지 시점 보고 기준, 이번 문서 개정에서 재실행하지 않음 — 미확인)
- 2026-07-18: **Issue #45 완료** — Project 동시 쓰기 lost-update 해소 (PR #52 머지, V26).
  - `projects.version` 컬럼(V26) + JPA `@Version`으로 2겹 잠금 적용: ① `@Transactional` 경로(`ProjectCommandService`, `WebhookEventHandler.handle`, `deploy()`)는 Hibernate `@Version` flush 검사만으로 충분(영속성 컨텍스트가 같은 인스턴스를 재사용하므로), ② 무tx 경로(`DeploymentCommandService.execute()`, 비동기 worker)는 매 호출이 fresh read이므로 `@Version`만으로는 놓치는 경합을 `ProjectRepositoryAdapter.save()`의 명시적 버전 비교(`entity.getVersion() != project.getVersion()`)로 추가 방어
  - 두 방어 모두 동일한 `ObjectOptimisticLockingFailureException`(OOLFE)을 던지도록 통일
  - `GlobalExceptionHandler`에서 OOLFE 응답을 기존 404 → **409**로 전환(과거엔 `@Version` 도입 전이라 OOLFE의 사실상 유일한 원인이 "동시 삭제"였으나, 이제는 "행이 존재하며 버전이 경합했다"가 지배적 의미가 되어 404가 부정확해짐. 삭제 경합도 재조회 시 자연스럽게 404로 수렴하므로 전 도메인 공용으로 409 통일)
  - 경로별 정책: 사용자 직접 요청 경로는 409를 그대로 반환(클라이언트 재조회/재시도), 백그라운드 워커 경로(`DeploymentCommandService.handleExecutionFailure` 등)는 기존 배포 retry/backoff 머신에 그대로 편입, Agent 경로(`DeployAgentService`)는 버전 경합 시 인라인 1회 재시도 후 그래도 실패하면 기존 Agent task 실패 처리로 전파
  - `DeploymentCommandService.execute()`의 자기 유발(self-inflicted) 경합 제거: 기존에는 `execute()` 시작 시점에 읽은 오래된 `Project` 스냅샷으로 저장을 시도해, 같은 메서드의 GitHub I/O(`ensureWorkflow`/`prepareRelease`가 유발하는 push)가 트리거한 webhook이 먼저 버전을 갱신해버리는 통상적인 경합이 있었음 — 저장 직전에 프로젝트를 다시 조회하도록 바꿔 경합 창을 "execute() 전체 GitHub I/O 구간"에서 "수 밀리초"로 축소
  - `ProjectRepositoryAdapter.save()`의 삭제 경합 재삽입 버그 봉합: 기존엔 저장 대상 행이 동시에 삭제되면 `orElseGet`이 이를 새 행으로 재삽입했으나(중복 생성), 이제는 다른 버전 경합과 동일하게 OOLFE를 던져 저장을 거부
  - 리뷰에서 확정된 결함 수정 완료(자기 유발 경합 제거, OOLFE 정책 완결)
  - 테스트 366/366 (PR #52 머지 시점 보고 기준, 이번 문서 개정에서 재실행하지 않음 — 미확인). 신규 공개 엔드포인트는 없음(내부 동시성 제어만 변경)
- 2026-07-18: **Cost & Budget 완료** (Issue #53 / **PR #58 머지**, V27).
  - `GET/PUT/DELETE /projects/{id}/settings/cost-budget`: U7 인프라 설정(architecture/tier/storage/network)을 정적 가격표(코드 상수, `InfrastructureCostEstimator`)와 BigDecimal로 매 요청마다 온더플라이 계산(계산 결과 저장 안 함, 외부 호출 없음 → p95 <50ms 목표)
  - `project_budget_settings`(V27)에는 월 예산 금액만 영속화한다. 통화는 USD 고정
  - `budgetStatus` 4상태: `NO_BUDGET`(예산 미설정) / `NOT_EVALUABLE`(예산은 있으나 비용 추정 불가) / `OVER_BUDGET` / `WITHIN_BUDGET`(추정==예산이면 WITHIN, 엄격한 `>` 비교)
  - 인프라 미구성이거나 CONNECTED 클라우드 연결이 없어도 `200` + `costAvailable=false`로 응답(D5: 예산은 인프라 구성 전에도 먼저 설정 가능)
  - PUT은 upsert(멱등)이며 저장 직후 재계산된 비용/예산 상태를 GET과 동일한 shape로 함께 반환
- 2026-07-18: **Cloud Ops Agent 완료** (Issue #54 / **PR #59 머지**, DDL 변경 없음).
  - `AgentType.INFRA_OPERATE` 신설: 자연어 Chat(기존 `/agent/decision`, `/conversations/{id}/messages` 흐름)을 통해서만 접근하며 **신규 HTTP 엔드포인트는 없음**
  - `InfraOperation` 화이트리스트 enum(`STATUS_CHECK`/`LOG_VIEW`/`FAILURE_ANALYSIS`/`RESTART`/`RESOURCE_SCALING`(미지원)/`AUTOSCALING_CHANGE`(미지원)/`RESOURCE_CLEANUP`(미지원)) — LLM은 오직 operation "이름"만 제공하고 리소스 식별자는 절대 제공하지 않는다. 대상(preview container/deployment history)은 항상 서버가 `(projectId, ownerUserId)`로 DB에서 재조회한다(design D3, 프롬프트 인젝션 방어)
  - `STATUS_CHECK`/`LOG_VIEW`는 LLM이 아닌 고정 템플릿으로 응답(환각 방지), 각 행이 독립적으로 degrade. `FAILURE_ANALYSIS`는 U6의 `DeploymentFailureAnalysisService`를 그대로 재사용(중복 구현 없음)
  - `RESTART`는 유일한 변경 작업이며 대상은 항상 DB에서 재조회한 **ACTIVE** preview 세션으로 한정
  - `serviceImpact`/`costImpact`가 있는 지원 작업(`RESTART`)만 `INFRA_OPERATION` Approval을 요구한다 — U7과 같은 `ApprovalType.INFRA_OPERATION`을 재사용하지만, 이 승인은 Agent task 흐름에서 생성되므로 `taskId`가 채워진 **Agent 기반** 승인이다(U7의 standalone과는 출처가 다름, `connection.md` 참고). 미지원 작업(`RESOURCE_SCALING`/`AUTOSCALING_CHANGE`/`RESOURCE_CLEANUP`)은 승인 생성 없이 감지·설명 후 명시적으로 거부(실행 경로가 없는 승인은 만들지 않음, "정직한 거부")
  - 정직한 전제: 실제로 프로비저닝된 서버가 없으므로, `STATUS_CHECK` 응답에는 "저장된 인프라 설정의 실 클라우드 리소스는 아직 프로비저닝되지 않았습니다"라는 고정 문구가 항상 포함된다 — 있지도 않은 서버 상태를 지어내지 않는다
  - 리뷰 후속 수정: 보조 조회(배포/클라우드 연결/인프라 설정) 실패 시 응답 전체가 아니라 해당 행만 degrade하도록 보존
- 통합 main 테스트 **454/454**, 공개 API **90개**(컨트롤러 14개, CloudOps는 신규 엔드포인트 없음) (PR #59 머지 시점 보고 기준, 이번 문서 개정에서 재실행하지 않음 — 미확인)
- 2026-07-18: **결과 승인 2단계 완료** (Issue #56 / **PR #61 머지**, V28).
  - 제품 모델: 컨테이너/preview 서버 → ①git preview 브랜치 → ②**git main(결과 승인)** → ③GitHub Pages 공개(배포 승인). git 반영과 Pages 배포를 별개 단계로 분리
  - `ApprovalType.RESULT` 신설(다른 4개 타입은 실행 전 계획 승인, RESULT는 유일하게 실행 후 결과 승인). `ResultApprovalGate`가 plan의 마지막 CODE step 완료 직후 1회 평가: 프로젝트가 GitHub BOUND && `resultApprovalRequired` 정책(V28, 기본 `true`)이 켜져 있으면 preview 브랜치 push → task `WAITING_RESULT_APPROVAL`(워커가 절대 claim 못하는 상태) → RESULT Approval 생성
  - 승인 시 `ResultApprovalService.reflect`가 preview→main PR 생성+merge(또는 신규 커밋 없으면 멱등 no-op) 후 `Change`를 `MERGED`로 전환, task 재개(`resumeAfterResult`). 거절 시 `Change`는 `REJECTED`, merge 없이 task `CANCELLED` — 거절 커밋은 preview에 그대로 남아 다음 결과 승인이 "그 시점 preview 전체 상태"를 반영(누적 시맨틱)
  - 직접 Deployment API의 자동 merge 규칙 변경: 정책 ON이면 "한 번도 배포/게이트를 거치지 않은 신규 프로젝트"에만 예외적으로 자동 merge 허용, 그 외에는 merge 권한이 RESULT 승인으로 완전히 이관(직접 배포는 main 현재 상태만 공개). 정책 OFF는 기존 동작 유지(회귀 없음)
  - Chat 설정(`GET/PATCH .../settings/chat`)에 `resultApprovalRequired` 필드 추가(4→5필드, PATCH에서 이 필드만 예외적으로 nullable)
  - 리뷰 확정 결함 수정: 거절분 자동 merge 우회 차단(BLOCKING-1), 취소된 task의 게이트 상태 부활 방지, merge의 비가역성을 고려한 상태 전이 순서 재조정(BLOCKING-2/3)
  - **신규 HTTP 엔드포인트 없음** — 기존 Agent task/Approval/Change API 위에서 상태값만 확장
  - **PRD 예외 개정**: 사용자 확정에 따라 `prd.md` §15.2를 "배포 요청 시점에 merge" → "결과 승인 시점에 merge"로 개정(§15.2 외 PRD는 정본 유지)
- 통합 main 테스트 **514/514**, Flyway V1~V28 연속 (PR #61 머지 시점 보고 기준, 이번 문서 개정에서 재실행하지 않음 — 미확인)
- 2026-07-18: **오케스트레이션 동시성/복구 하드닝 완료** (Issue #55 / **PR #63 머지**, DDL 변경 없음, 신규 HTTP 엔드포인트 없음).
  - **D1 write-skew 고착 구조적 해소(ADR-Y1, 락 계층 통일)**: `ApprovalCommandService.approve/reject`가 이제 항상 `taskStore.lockTask`로 task 행을 먼저 잠근 뒤 그 task의 승인들을 잠금 상태로 재조회한다 — task 단위 mutex가 같은 task의 모든 승인 결정을 직렬화해, 동시 승인 결정이 서로를 "아직 PENDING"으로 오판해 task가 `WAITING_APPROVAL`에 영구 고착되는 write-skew를 새로 발생할 수 없게 만들었다. 결과 승인(#56)·인프라 설정 standalone 승인(#62 B3)도 같은 락 순서로 통일
  - **D1 복구**: `StuckApprovalSweeper`(기본 60초 주기, `qeploy.agent.approval.sweep-interval-ms`)가 "전 승인 APPROVED인데 여전히 WAITING_APPROVAL"인 task를 찾아 재검증·복구. ADR-Y1 이후 정상 운영에서는 후보 0건이 정상이며, 배포 이전 잔존 고착분 배출 + 향후 회귀 대비 안전망 역할
  - **D2 RUNNING 좀비 해소**: 실행자 풀 포화로 dispatch가 거부되면 task를 `RETRY_WAIT`로 되돌림. `AgentExecutionRegistry`(JVM 로컬, 실행자 제출 직전 등록)로 heartbeat 갱신 대상을 "실제로 실행 중인 task"로 한정 — 이전에는 claim된 모든 task의 lease가 갱신되어, 실행자 큐에 대기 중이라 아직 실행되지 않은 task도 살아있는 것처럼 보여 `recoverExpiredLeases`가 만료 lease를 회수하지 못하는(중복 실행 위험) 버그가 있었음
  - 부수 수정: 거절 시 형제 `PENDING` 승인도 `CANCELLED`로 대칭 전환(감사 G6 비대칭 해소), build 실패 복구 dedupe 요약 조회 로직 병합
  - 신규 통합 테스트(`OrchestrationConcurrencyIntegrationTest`, `ResultApprovalCancelRaceIntegrationTest`)로 동시 결정/취소 경합 재현·검증
- 통합 main 테스트 **564/564**, DDL 변경 없음 (PR #63 머지 시점 보고 기준, 이번 문서 개정에서 재실행하지 않음 — 미확인)
- 2026-07-19: **E2E 결함 수정 완료** (Issue #57 / **PR #65 머지**, DDL 변경 없음, 신규 HTTP 엔드포인트 없음).
  - QA `full-api-conformance-report.md`의 H1(재시도 가능 여부 불일치)·M3(승인 대기 task 미노출)는 같은 근본 원인이라 함께 해소: `GET /agent/tasks/{taskId}` 응답에 `pendingApprovalId`(nullable) 필드 추가
  - `retryable`을 `POST /tasks/{taskId}/retry`가 실제로 검사하는 것과 동일한 기준("PENDING 승인 없음 AND `attempt < maxAttempts`")으로 재정의 — 이전에는 `attempt < maxAttempts`만 보고 계산해, `BuildFailureRecoveryService`의 "자동 수정 및 재build" 승인이 아직 PENDING인데도 `retryable:true`를 반환해 FE가 `/retry`를 호출하면 항상 409가 나는 결함이 있었음
  - `AgentOrchestrator.findPendingApprovalId`(신규 공용 조회)를 응답 조립(AgentController)과 `/retry` 게이트가 동일하게 사용하도록 통일해 두 판정이 다시 어긋날 수 없게 함
  - `pendingApprovalId`가 있으면 `POST /approvals/{id}/approve`로 그 승인을 먼저 처리해야 하며(승인 자체가 재실행 트리거), 승인 대기가 없을 때만 `/retry` 사용
- 2026-07-19: **retry() TOCTOU 완결 완료** (Issue #64 / **PR #67 머지**, DDL 변경 없음, 신규 HTTP 엔드포인트 없음).
  - `AgentOrchestrator.retry()`를 `@Transactional` + `taskStore.lockTask`(태스크 행 선락, approve/reject/cancel/RESULT/sweep와 동일 순서) + locking read PENDING 검사로 원자화 — 확인~조치가 단일 태스크 뮤텍스 하 원자 단위
  - `BuildFailureRecoveryService`의 직접 `taskStore.retry()` 호출도 `agentOrchestrator.retry()` 경유로 교체 → 자동복구↔수동 재시도 경합의 이중 재시도(RETRY_QUEUED 2건) 제거. 이로써 `taskStore.retry()`로 가는 모든 경로가 락 하 직렬화되어 **#55 ADR-Y1 규율(모든 태스크 결정을 태스크 행 뮤텍스에서 직렬화)이 retry까지 완성**
  - 리뷰: 0ee59b5 APPROVE(락 순서·전파 실측, 부모 커밋 데드락 재현→수정본 제거 실증) + HIGH-1(두 번째 호출자) → a60bc8e 완결
- 통합 main 테스트 **577/577**, DDL 변경 없음
- 2026-07-19: **결과 승인 B1 잔여 완료** (Issue #62 / **PR #68 머지**, DDL 변경 없음, 신규 HTTP 엔드포인트 없음).
  - `ResultApprovalService.hasResultGateHistory`를 REJECTED/MERGED change 존재 **OR** PENDING RESULT Approval 존재로 확장 — PENDING(미결정) RESULT 승인이 있는 프로젝트도 "게이트 관할 중"으로 인정해, 동시 직접 배포가 미결정 내용을 우회 merge하던 협소 창을 닫음. 신규 프로젝트 최초 배포(게이트 이력 전무)는 여전히 정상 merge(회귀 없음)
  - 이로써 #56 결과 승인 리뷰 잔여(B1·B3·retry) 전부 정리. 통합 main **579/579**
- 2026-07-19: **PRD 정합성 종합 E2E v2 완료** — 실 LLM(Claude)+실 Docker로 8개 신규 단위 + Agent 오케스트레이션 전 구간 E2E 검증. 리포트 `.agent-team/11-qa/full-conformance-v2-report.md`(증거 `evidence-v2/`). 결함 2건 발견 → 이번 세션에서 전부 해소:
  - 🔴 **Critical — webhook 파이프라인 영구 정지** (Issue #70 / **PR #73 머지**, V29): `WebhookDeliveryWorker.claim()`이 `next_attempt_at=null`을 UPDATE하는데 V21이 `NOT NULL`로 선언 → 제약 위반으로 webhook 처리(Pages LIVE/FAILED 반영·push 동기화·installation 이벤트) 전면 중단. V29로 컬럼 nullable화 + 엔티티 매핑 정합(ddl-auto:validate가 nullability 미검증이라 부팅 시 못 잡았던 근본원인) + 실 MySQL 통합/스키마 테스트(claim/retry/lease/complete/ignore/FAILED null-write 경로 전부 실 DB 검증). 리뷰 APPROVE — 리뷰어가 V29 이전 상태 재현해 테스트가 프로덕션과 동일 예외로 실패함까지 확인(유효 가드 증명)
  - 🟠 **High — CloudOps RESTART 후 포트 미갱신** (Issue #71 / **PR #72 머지**): `InfraOpsAgentService.restart`가 컨테이너 재시작 후 새 매핑 포트를 `PreviewSessionService.updateHostPort`로 반영하지 않아 프리뷰 URL이 죽은 포트를 가리키던 문제 해소(`PreviewSessionEntity.rebindPort`)
  - 통합 main **594/594**, Flyway V1~V29 연속. 열린 이슈 0건
- 2026-07-25: **Audit Log 완료** (Issue #74 / **PR #75 머지**, V30, BACKLOG EPIC 17 BI-188~192).
  - `audit` 도메인 신설: `audit_logs`(V30, FK 0개·인덱스 3개, append-only) + 16종 액션 고정 카탈로그(GITHUB 6·DEPLOYMENT 4·DOMAIN 2·INFRA 4, `AuditAction`) + 서비스 10곳에 기록 훅 삽입(`ProjectCommandService`·`DeployAgentService`·`ResultApprovalGate`·`ResultApprovalService`·`DeploymentCommandService`·`WebhookEventHandler`·`DomainBindingCommandService`·`InfraOpsAgentService`·`ProjectInfrastructureConfigurationService`·`InfrastructureChangeApprovalHandler`)
  - 기록 메커니즘: `AuditRecorder.record()` → `AuditLogWriter.write()`(`@Transactional(REQUIRES_NEW)`, 이 메서드는 SELECT 0회·INSERT 1회만 수행)로 비차단 계약을 구조로 강제한다 — 감사 기록이 실패해도 본 작업(레포 연결/배포/도메인 연결 등)은 절대 실패하지 않고, FK 0개라 락 계층(#55 ADR-Y1)의 완전한 리프를 유지한다(리뷰가 `REQUIRES_NEW→REQUIRED` 뮤테이션 실험으로 이 의존성을 직접 실증)
  - 조회 API 1개 신설: `GET /projects/{projectId}/audit-logs`(소유자 404, `category`/`limit` 필터, `audit_log_id desc`) — 공개 API 90 → **91**
  - `common/security/SecretRedactor` 신설(U6 `DeploymentFailureAnalysisService`가 갖고 있던 시크릿 레닥션 정규식을 공용화, 기존 레닥션 테스트 무회귀). `error_summary`는 저장 계층(`AuditLog.from`)에서 무조건 레닥션+500자 절단
  - retention: 기본 180일(`qeploy.audit.retention-days`), 1시간 주기 배치 삭제(`AuditLogRetentionScheduler`, 500행 단위 반복)
  - BI-195(권한 최소화)는 이번 단위에서 감사 접근 최소화(소유자 404)·데이터 최소화(레닥션·화이트리스트)·권한 사용 가시화(카탈로그)까지만 **부분 완료**. GitHub App 설치 권한 재검토·축소, G5(컨테이너 `/tmp/.git-credentials` 평문 개선) 후속은 보안 성격이라 별도 분리했고 아직 이슈 미신설(`U-sec` 인접, 착수 전 사용자 확인 필요)
  - 리뷰 `.agent-team/10-review/ad-audit-review.md`(Thomas) **APPROVE** — Blocking/High 0건, Medium 1건(`AUDIT_FALLBACK` 폴백 로그에 `errorSummary` 누락)·Low 3건은 같은 PR 후속 커밋(`7840821`)에서 정리 완료
  - 통합 main **641/641**, Flyway V1~V30 연속
- 2026-07-25: **AE 완료 — preview 컨테이너 외부 노출 차단** (Issue #76, BI-081/G1, **PR #78 머지**, main `af221a7`).
  - 착수 전 John 감사(`.agent-team/01-reverse/preview-exposure-audit.md`)로 노출 표면을 실측해 **독립된 두 표면**을 분리했다: **G1**(컨테이너 호스트 포트가 `Ports.Binding.bindPort(0)` — HostIp 미지정이라 Docker가 `0.0.0.0` + IPv6 `::` 전체 인터페이스에 퍼블리시 → 게이트웨이·accessToken·Spring Security를 전부 우회해 인증 없는 `npx serve`에 직접 도달) / **G2**(게이트웨이가 `permitAll` + URL 내 accessToken 일치 검사만으로 인가, 세션 소유권 미검증). **하나를 막아도 나머지가 실질 노출로 남는다.**
  - 사용자 확정: **이번 단위 범위 = G1만.** G2·G4(토큰 회전·폐기 부재)는 무헤더 accessToken이 **iframe 임베딩을 위한 의도된 설계**여서 수정 시 FE(`Dvely_FE_test`) 조율이 동반되므로 **Issue #77로 분리**(착수 전 FE 조율 범위·UX 영향을 사용자와 확정).
  - 구현(브랜치 `danto/preview-exposure`, 커밋 `35dfe62`+`19aab1c`, **644/644**): `bindIpAndPort("127.0.0.1", 0)`로 전환해 루프백에만 퍼블리시. 게이트웨이는 이미 `127.0.0.1:hostPort`로만 프록시하므로 정상 경로는 무영향. 실 Docker로 바인딩 IP와 RESTART 후 재바인딩(#71 경로)을 검증하는 회귀 테스트 2건 + mock 1건 추가. 멀티호스트 Docker로 확장할 경우 이 루프백 바인딩과 게이트웨이 프록시 대상을 함께 재검토해야 한다는 함정을 코드 주석으로 남겼다.
  - 실증: 수정 전 `0.0.0.0:52469` + `[::]:52469` 확인 → 수정 후 동등 조건에서 루프백 접속 **성공**, 호스트 LAN IP 접속 **거부**. `inspect` 출력이 아니라 실제 도달 가능성으로 PRD §14.2("서비스 내부 워크스페이스에서만 접근") 위반 차단을 확인했다.
  - 리뷰 `.agent-team/10-review/ae-preview-exposure-review.md`(Thomas) **APPROVE** — Blocking 0 · High 0 · Low 2(비블로킹). **뮤테이션 실험**(`bindIpAndPort`→`bindPort` 되돌림)에서 신규 테스트 3개가 **전부 red** → 셋 다 진짜 회귀 가드임을 확증. 추가 실측: 재시작마다 실제로 다른 포트가 재할당됨(54176→54178→54185) · `docker exec` 경유 npm install 아웃바운드와 bridge egress NAT가 인바운드 퍼블리시와 **독립**임을 확인(U4 §2의 "포트 퍼블리시 유지" 요건과 충돌 없음 — 퍼블리시는 유지하고 HostIp만 좁힌 것) · 644/644 독립 재현 · 컨테이너 누수 0.
    - Low 2건(후속 권고, 비차단, 리뷰 확정 시점 기준 머지 차단 사유 아님): **L1** restart 테스트가 재시작 전/후 포트를 비교하지 않아 "재할당 검증"이라는 이름값을 스스로 단언하지 못함 — `19aab1c`에서 restart 전 `getMappedPort` 캡처 후 `isNotEqualTo` 단언을 추가해 반영 · **L2** `HOST_BIND_IP`와 `PreviewGatewayService`의 `"127.0.0.1"`이 상수 미공유(이번 PR이 만든 문제 아님, 정보성 기록으로 남기고 미반영).
  - Flyway 변경 없음(V1~V30 연속), 신규 엔드포인트·프로퍼티 없음(공개 API 91개 유지). 운영 전제: 게이트웨이(Spring)와 Docker 데몬이 동일 호스트여야 한다는 기존 전제(`PreviewGatewayService`가 이미 `127.0.0.1` 프록시)를 코드로 강제하게 됨(신규 제약 아님) — 멀티호스트/원격 Docker 전환 시 재검토 필요.
  - `.notion` 갱신 완료: `state.md` §2.26 신설 + §3.5/§4.3/§4.13/§5/§6 BI-081 참조 정리, `BACKLOG_STATUS.md` BI-081 Todo→Done(5차 갱신), 이 문서 §1·§3 AE 행 완료 전환.
- **다음 단위: `BI-163~165` Project Settings 나머지(Version Policy/Deployment Defaults/Domain).** 그 외 남은 백로그: WireMock 계약 테스트 인프라(E2E H2/M2) + EnvironmentValueResolver 실주입 seam + EPIC 18 운영 지표(BI-196~201) + `U-sec`(🔴 실 OpenAI API 키 무효 포함 env 이관, 보류). 착수 전 사용자와 우선순위를 확인한다.
- `U-sec`(실 API 키 env 이관 등 민감 작업)는 사용자 승인 전까지 착수하지 않는다.

---

## 2. 단위(Unit) 파이프라인 규칙

- 단위 1개 = **이슈 1개 + 브랜치 1개(`danto/<도메인>`) + PR 1개**. Issue/Commit/PR 형식은 `agent.md`를 따른다.
- 완료 기준(Definition of Done):
  1. `./gradlew test` 통과
  2. 해당 영역의 FE 플로우 확인 — FE 정본 repo(`/Users/otter/Dvely_FE_test`) 기준으로 관련 화면/API 호출이 깨지지 않는지 확인한다. 내부 사본(FE 코드가 이 저장소 안에 있다면)은 구버전이므로 판단 근거로 쓰지 않는다.
- 단위 착수 전 관련 `BACKLOG_STATUS.md` BI 항목과 `state.md` 해당 절을 확인해 중복 작업을 피한다.
- 단위 완료 후 `api.md`/`connection.md`/`state.md`/`BACKLOG_STATUS.md` 중 실제로 바뀐 부분을 함께 갱신한다(Timothy 담당 또는 담당자 합의).
- 민감한 작업(예: 실 API 키/시크릿 이관)은 사용자 승인 후 착수한다.
- 단위 진행 중 발견된 범위 밖 결함/리스크(예: 동시성 이슈)는 해당 단위를 막지 않고 별도 Issue로 분리한다(U5 → Issue #45 사례 참고).

---

## 3. 단위 백로그

| 단위 | 브랜치 | 상태 | 관련 BI/EPIC | 내용 |
|---|---|---|---|---|
| U1 | `danto/fe-verification` | **완료 (2026-07-17)** | - | FE 회귀 검증 + 결함 수정. 백엔드 PR #38, FE PR #4 머지 |
| U2 | `danto/agent-chat` | **완료 (2026-07-17)** | EPIC 05 (Chat Agent) | Agent CHAT 스텝 구현 + `MessageResponse.taskId` 노출. 백엔드 PR #40, FE PR #5 머지 |
| U3 | `danto/environment` | **완료 (2026-07-18)** | BI-152~160 (EPIC 13) | Environment/Secrets 도메인 CRUD+이력, AES 전체 암호화, secret 완전 마스킹. Issue #41 / PR #44 머지 |
| U4 | `danto/preview-ops` | **완료 (2026-07-18)** | BI-082·083·194 (EPIC 07/17) | Preview 컨테이너 상태/로그 조회 API + 격리 정책(자원 상한/capability/no-new-priv/네트워크 격리) + docker-java 3.7.1. Issue #42 / PR #47 머지 |
| U5 | `danto/repo-settings` | **완료 (2026-07-18)** | BI-162·025 (EPIC 02/14) | Repository Settings 조회 API, 레포 연결 해제(비파괴). Issue #43 / PR #46 머지. 동시성 후속: Issue #45 |
| U6 | `danto/deploy-recovery` | **완료 (2026-07-18)** | BI-112·113·186 (EPIC 09/16) | 배포 실패 원인 분석(온디맨드·멱등·LLM+룰 fallback) + 재시도 API. Issue #48 / PR #50 머지, V24 |
| U7 | `danto/infra-settings` | **완료 (2026-07-18)** | BI-121~124·129·097 (EPIC 10/08) | 인프라 설정 저장(4개 provider-중립 enum) + standalone INFRA_OPERATION 승인. Issue #49 / PR #51 머지, V25 |
| Issue #45 | `danto/project-locking` | **완료 (2026-07-18)** | - | Project 동시 쓰기 lost-update 해소. V26 `version` 컬럼 + @Version + 어댑터 버전 가드 2겹, OOLFE 404→409, 경로별 정책(사용자 409/워커 재시도 머신/Agent 1회 재시도). PR #52 머지 |
| Cost & Budget | `danto/cost-budget` | **완료 (2026-07-18)** | BI-144~147·167 (EPIC 12) | 인프라 설정 기반 온더플라이 비용 추정(정적 가격표) + 월 예산 저장/평가. Issue #53 / PR #58 머지, V27 |
| Cloud Ops Agent | `danto/cloud-ops-agent` | **완료 (2026-07-18)** | BI-055·170~173·176·177 (EPIC 05/15) | `AgentType.INFRA_OPERATE` — 자연어 chat 경유 서버 상태/로그/장애분석/재시작. Issue #54 / PR #59 머지, HTTP 엔드포인트 추가 없음 |
| Issue #56 | `danto/result-approval` | **완료 (2026-07-18)** | - | 결과 승인 2단계 게이트 — `ApprovalType.RESULT`, preview→main 반영을 배포와 분리. PR #61 머지, V28. PRD §15.2 예외 개정 포함 |
| Issue #55 | `danto/orchestration-hardening` | **완료 (2026-07-18)** | - | 오케스트레이션 동시성/복구 하드닝 — write-skew 락 계층 통일(ADR-Y1), StuckApprovalSweeper, RUNNING 좀비 해소(AgentExecutionRegistry). PR #63 머지, DDL 없음 |
| Issue #57 | `danto/e2e-fixes` | **완료 (2026-07-19)** | - | task 응답 `retryable`·`pendingApprovalId` 정합(H1/M3 동일 근본원인 해소). PR #65 머지, DDL 없음, 신규 엔드포인트 없음 |
| AD | `danto/audit-log` | **완료 (2026-07-25)** | BI-188~192·195(부분) (EPIC 17) | 감사 로그 도메인 신설 — `audit_logs`(V30, FK 0개) + 16종 액션 카탈로그 + 서비스 10곳 훅 + 조회 API 1개(90→91) + retention 180일. BI-195는 부분 완료(후속 분리, 아래 참고). Issue #74 / PR #75 머지 |
| AE | `danto/preview-exposure` | **완료 (2026-07-25)** | BI-081/G1 (EPIC 07/17) | preview 컨테이너 호스트 포트를 루프백에만 바인딩해 외부 직접 접근 차단(G1). 644/644, 리뷰 APPROVE(Low 2, L1 반영). Issue #76 / PR #78 머지, main `af221a7` |
| AG | `danto/preview-gateway-authz` | **완료 (2026-08-15)** | - | preview 게이트웨이 인가 — G2(소유권 쿠키 발급 `POST /preview-sessions/{id}/access` + 게이트웨이 401) · G4(발급 시 accessToken 회전). 726/726, DDL 없음. Issue #77 / PR #104 머지, main `113abf0`. FE 연동 `Dvely_FE` PR #30 머지·배포 완료 |
| U-sec | `danto/security` | **보류 — 사용자 승인 필요** | - | 🔴 실 OpenAI API 키 등 민감정보의 env 이관, 추적/로그에 남은 민감정보 정리 |
| CA | `danto/coding-agent-byok` | **머지 준비 완료 (2026-09-05) · PR #245 CI 성공** | EPIC 05 (Agent) | 외부 AI 코딩 에이전트 개인계정 연동(BYOK). 사용자 본인 공식 API 키로 Claude Code / Codex CLI를 격리 컨테이너에서 헤드리스 실행. 변형 A(서버측) 1차 범위. 구독 임베딩·세션 가로채기·우회는 비목표. 설계 `docs/byok-coding-agent-design.md`, 요구사항 `srs.md`, PRD 부록 A-1. V45(develop 이 V43·V44 연속 선점). PR 5분할 + 실측 수정 3건, 커밋 10개, 테스트 1166개 통과, CI 성공. Issue #242. **CODE 스텝 배선은 Issue #325 로 분리** — 실행기·자격증명·라우팅은 다 있으나 제품 플로우가 부르지 않는 휴면 상태다. Claude 경로 실검증도 그 안에 있다(`state.md` §4.21) |
| SEC-preview | (미착수) | **설계 확정 · unhak 인계 (2026-09-11)** | EPIC 07 (Preview) | 프리뷰 컨테이너의 사용자 코드 실행 격리 — 워크스페이스 소유자를 node 로 통일. `cap-drop ALL` 이 `DAC_OVERRIDE` 를 떼는 것이 설계를 결정한다(root 가 node 소유 트리에 못 씀 → 주인이 하나여야 함). 순서: 빌드 컨테이너 분리 → 워크스페이스를 처음부터 node → exec 기본 뒤집기 → (별건) egress 허용목록. Issue #332, `state.md` §4.25 |
| PAT | `danto/agent-pat` | **완료 (2026-09-10) · PR #306 머지** | EPIC 05 (Agent) | 에이전트용 개인 액세스 토큰. MC 단위의 유일한 선행 요건. 해시 저장, `qp_` 접두사 필터 분기, 스코프는 HTTP 메서드로 강제. 엔드포인트 3개, V60. 실 서버 HTTP 검증 10건 통과. Issue #304, `state.md` §4.22, `api.md` §18 |
| MC | `danto/agent-pat` | **완료 (2026-09-10) · PR #306 머지** | EPIC 05 (Agent) | 에이전트 연동 — MCP 서버 + npm CLI 오픈소스 제공. 사용자의 Claude Code·Codex 가 Qeploy 를 도구로 호출한다(CA 와 방향이 반대라 컴플라이언스 이슈 없음, AI 비용 0). 선행: 개인 액세스 토큰(PAT) — 현행 JWT 는 1시간이라 헤드리스 불가. 설계 `docs/qeploy-mcp-cli-design.md`, 요구사항 `srs.md` §B, PRD 부록 A-2 |

이후 백로그(단위 편성 전, `BACKLOG_STATUS.md` Backlog 참고):

- `BI-163~165` Project Settings 나머지(Version Policy/Deployment Defaults/Domain) — **다음 단위 1순위**
- **Issue #115** — 프리뷰 serve 프로세스 종료 실패(`pkill -f 'npx serve'`가 실제 cmdline `npm exec serve …`/`node …/.bin/serve …`를 못 잡고 자기 셸만 SIGTERM). 포트 3000이 점유된 채 새 serve가 랜덤 포트로 조용히 떠서 **낡은 빌드가 계속 서빙**된다. 인접 결함: `DockerContainerService.exec`가 종료코드를 안 읽어 빌드 실패가 조용히 성공으로 넘어감. **신뢰성 영향이 커 프리뷰 계열 1순위 후보**
- **Issue #116** — Next/Vue CLI/SvelteKit/Gatsby/Astro/Nuxt는 config가 있으면 배포 워크플로가 `::warning::`만 내고 커밋된 base가 이긴다(`DeployWorkflowTemplate:222/242/277/302/328`, Nuxt는 주입 지점 자체 없음). 커스텀 도메인 연결 시 자산 404. Vite·CRA는 CLI/homepage 덮어쓰기로 안전
- **Issue #117** — 라우터 basename을 배포용 base로 고정한 앱(`basename={import.meta.env.BASE_URL}`)은 프리뷰에서 자산이 다 로드돼도 빈 화면. accessToken 회전 때문에 basename을 프리뷰 URL에 맞출 수 없어 구조적이며, 완전 해법은 프리뷰 전용 오리진 분리 + base 중립화
- EPIC 18 운영 지표(BI-196~201), Preview 런타임 확장(BI-079·085~087·090~092), 도메인 확장(BI-141·142), AWS/GCP 실배포(BI-130·131 — IaC 설계 선행)
- BI-195 후속 분리분(EPIC 17, `.agent-team/04-architecture/ad-audit-log-design.md` §9 분할 근거): GitHub App 설치 권한 재검토·축소, G5 컨테이너 `/tmp/.git-credentials` 평문 개선 — 보안 성격이라 `U-sec`에 인접하며, 아직 이슈 미신설(착수 전 범위·우선순위를 사용자와 확인)

Audit Log(Issue #74, BI-188~192)는 완료했다(2026-07-25, §1 참고). Issue #62(결과 승인 리뷰 Low 등급 잔여)·#64(배포 재시도 TOCTOU 경합)도 각각 PR #68·PR #67로 이미 완료되었다(§1의 2026-07-19 항목 참고) — 이 목록에 남아 있던 것은 §1을 갱신한 뒤 이 절을 함께 갱신하지 않아 발생한 지연이었다(이번 개정에서 정정). `U-sec`는 실 OpenAI API 키 이관을 포함하므로 민감 작업으로 분류되어 있으며 여전히 사용자 승인 전까지 보류한다. 우선순위가 바뀌면 이 표를 먼저 갱신한다.

---

## 4. 세션 인계 규칙

1. 새 세션 시작 시 `/resume` 직후 이 문서(`ROADMAP.md`)의 "1. 현재 상태"를 먼저 확인한다.
2. 작업 중 단위가 바뀌거나(Unit 완료/착수) 우선순위가 바뀌면 "1. 현재 상태"와 "3. 단위 백로그"의 상태 컬럼을 즉시 갱신한다.
3. 세션 종료 시:
   - "1. 현재 상태"에 마지막 진행 지점을 남긴다(어떤 단위, 어디까지, 다음에 무엇을 할지).
   - `/save-session`을 실행한다.
4. 단위 완료 시 이 문서뿐 아니라 `state.md`의 "한 것"/"해야 할 것", `BACKLOG_STATUS.md`의 해당 BI 상태(Todo/Doing → Done)도 함께 갱신한다.

---

## 5. 참고 문서

- 요구사항 정본: `prd.md` (기존 §0~§29 요구사항 텍스트 수정 금지, 개정은 말미 "부록 A. 개정 이력"에 append-only)
- 단위 요구사항(SRS): `srs.md`
- 구현 상태: `state.md`
- API 계약: `api.md`
- 모듈 연결: `connection.md`
- 컨벤션 SSOT: `agent.md`
- PRD 백로그 원장: `BACKLOG_STATUS.md`
