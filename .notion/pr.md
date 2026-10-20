# PR 작성 규칙

> 기준일: 2026-07-17 · 기준 커밋: 34fe578 · 코드 실측 기반
> 개정: 2026-10-20 · 기준 커밋: 02a2240 — 릴리스 PR 형식, 다중 이슈 표기, 브랜치·base 를 `agent.md` 개정에 맞췄다

type 목록, 브랜치 규칙, 이슈 번호 표기 등 컨벤션 SSOT는 `agent.md`다. 이 파일은 PR 템플릿만 다룬다.

## Title
항상 아래 형식을 사용한다.

```markdown
[{Module}] {작업 요약} [ #IssueNumber ]
```

예:
- `[Config] Cloudflare 설정을 yml 관리 방식으로 전환 [ #IssueNumber ]`
- `[Project] 프로젝트 생성 시 GitHub 저장소 연결 강제 동작 수정 [ #IssueNumber ]`
- `[Project+Chat] API 테스트 중 발견된 저장소 플로우 및 대화 복구 보정 [ #IssueNumber ]`
- 여러 이슈: `[Deploy+Agent] 배포 base 확정 · 프리뷰 basename 방어 · 코딩 에이전트 배선 [ #116 #117 #325 ]` — 대괄호 하나 안에 공백으로 나열

릴리스 PR(`develop` → `main`)은 아래 형식이고, 스쿼시하지 않고 머지 커밋으로 합친다.

```markdown
[Release] {운영에 나가는 변경 요약} [ #IssueA #IssueB ]
```

예: `[Release] 플래너 instruction 언어 고정 [ #469 ]` (PR #476)

## 🔥 PR 개요
이 PR에서 수정하는 기존 동작, 변경 배경, 변경 후 기대 동작을 간단히 설명한다.

## 🔍 주요 변경 사항
- [ ] 변경 1
- [ ] 변경 2
- [ ] 변경 3

## 📌 관련 이슈
- Refs #IssueNumber

## ✅ 테스트 결과
- [ ] ./gradlew test 통과

## 📌 마이그레이션/배포 참고
- (필요 시 작성)

## 📌 참고 사항
- 로컬 환경 파일 변경은 PR 범위에서 제외

## 작성 원칙
- PR 본문에는 실제 커밋/푸시/리뷰 대상만 포함한다.
- 로컬 테스트 콘솔, 개인 환경 설정, 시크릿 변경처럼 PR에 포함하지 않을 내용은 "참고 사항"에만 적거나 생략한다.
- 기존 동작 보정 작업은 "추가"보다 "수정", "분리", "보정", "제거" 같은 표현을 우선 사용한다.
- 여러 이슈를 하나의 PR로 묶을 때는 관련 이슈에 `Refs #A`, `Refs #B`를 모두 적는다.
- PR title에는 `Issue A`, `Issue D` 같은 이슈 문자 표기를 넣지 않는다. 이슈 연결은 본문의 `Refs #IssueNumber`로만 표시한다.
- 브랜치는 `<이름>/<도메인>` 규칙을 기본으로 하고 base 는 `develop` 이다(`agent.md` 브랜치 규칙 참조). base 가 `develop` 이면 `Closes #N` 이 이슈를 닫지 않으므로, 이슈는 운영 반영 뒤 수동으로 닫는다.
