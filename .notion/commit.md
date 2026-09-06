# Commit 규칙

> 기준일: 2026-07-17 · 기준 커밋: 34fe578 · 코드 실측 기반

type 목록, 브랜치 규칙, 브랜드 이중 상태 등 컨벤션 SSOT는 `agent.md`다. 이 파일은 커밋 메시지 형식만 다룬다.

## 기본 규칙
- 기본은 이슈 1개 = 커밋 1개
- 커밋 메시지에 반드시 `[ #IssueNumber ]` 포함
- 불가피하게 여러 이슈를 하나의 커밋으로 묶을 경우, 이슈 번호를 모두 표기
- 이슈 번호는 대괄호 안에 공백을 넣는 형식을 사용한다. `[ #11 ]`이 표준이고, `[#11]`은 사용하지 않는다(`git log` 실측: 공백형 26건 vs 무공백형 4건, `agent.md` 참조).

## 타입 목록
- feat: 기능 추가
- fix: 버그 수정
- refactor: 리팩토링 (기능 변화 없음)
- style: 스타일 변경 (포맷팅 등)
- docs: 문서 변경
- test: 테스트 추가/수정
- chore: 빌드/환경 설정
- perf: 성능 개선
- config: yml, properties, 외부 서비스 설정 바인딩 등 설정 관리 변경

## 메시지 형식
항상 아래 형식을 사용한다.

```text
{type}({scope}): {작업 요약} [ #IssueNumber ]
```

예:
- `fix(project): 프로젝트 생성 시 저장소 연결 강제 동작 수정 [ #IssueNumber ]`
- `config(domainbinding): Cloudflare 설정을 yml 관리 방식으로 전환 [ #IssueNumber ]`
- `refactor(project): 프로젝트 저장소 연결 책임 분리 [ #IssueNumber ]`

## Type 선택 기준
- 기존 요구사항과 다른 동작을 수정하면 `fix`
- 새 공개 기능/API/모듈을 추가하면 `feat`
- 외부 동작 변화 없이 구조를 정리하면 `refactor`
- yml/properties/env binding 등 설정 관리 방식 변경이면 `config`
- 테스트만 변경하면 `test`
- 문서만 변경하면 `docs`
- 포맷팅 등 의미 변화 없는 스타일 변경이면 `style`
- 성능 개선이면 `perf`
- 빌드/패키지 매니저/운영 보조 작업이면 `chore`

## Scope 기준
- scope는 소문자 단수형을 우선 사용한다.
- 예: `project`, `chat`, `domainbinding`, `deployment`, `auth`, `config`
- 여러 모듈을 한 커밋으로 묶는 경우 `project-chat`처럼 하이픈으로 연결한다.
