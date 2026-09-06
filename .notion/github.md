# Commit, Issue, PR 작성 가이드

> 기준일: 2026-07-17 · 기준 커밋: 34fe578 · 코드 실측 기반

<aside>
🔧

이 메뉴얼을 참고하여 개발해주시길 바랍니다. type 목록, 브랜치 규칙, 브랜드 이중 상태 등 컨벤션 SSOT는 `agent.md`다.

</aside>

# 정규 형식

Issue, Commit, PR 작성안은 항상 아래 형식을 기준으로 한다.

```text
Issue Title  : Issue {Letter}: [{Module}] {작업 요약}
Commit       : {type}({scope}): {작업 요약} [ #IssueNumber ]
PR Title     : [{Module}] {작업 요약} [ #IssueNumber ]
```

예:

```text
Issue D: [DomainBinding] 도메인 연결 및 Cloudflare/GitHub Pages 연동 추가
config(domainbinding): Cloudflare 설정을 yml 관리 방식으로 전환 [ #IssueNumber ]
[Config] Cloudflare 설정을 yml 관리 방식으로 전환 [ #IssueNumber ]
```

## Type 기준

| type | 기준 |
| --- | --- |
| feat | 새로운 공개 기능/API/모듈 추가 |
| fix | 기존 요구사항과 다른 동작 수정, 테스트 중 발견된 문제 보정 |
| refactor | 외부 동작 변화 없는 구조 개선 |
| config | yml/properties/env binding 등 설정 관리 변경 |
| style | 포맷팅 등 코드 의미 변화 없는 스타일 변경 |
| docs | 문서 추가/수정 |
| test | 테스트 코드 추가/수정 |
| chore | 빌드, 패키지 매니저, 잡무성 변경 |
| perf | 성능 개선 |

## 주의

- 이슈 번호는 대괄호 안에 공백을 넣는 형식을 사용한다. `[ #11 ]`이 표준이고, `[#11]`은 사용하지 않는다(`agent.md` 참조).
- Issue title에는 commit type을 넣지 않는다.
- PR title에는 Issue 번호를 붙인다.
- 로컬 환경 파일, 시크릿, 개인 개발용 설정은 커밋/PR에 포함하지 않는다.
- 브랜치는 `danto/<도메인>` 규칙을 기본으로 한다(`agent.md` 브랜치 규칙 참조).

# Issue Body

```markdown
## 🔥 Issue 개요
현재 문제나 변경 배경을 설명한다.

## 🎯 목표
- [ ] 목표 1
- [ ] 목표 2

## ✅ 작업 범위
- 포함 범위:
  - ...
- 제외 범위:
  - ...

## ✅ Task List (PR 단위)
- [ ] PR-1 ...

## ✅ 완료 기준
- [ ] 주요 기능 동작 확인
- [ ] 테스트 통과
- [ ] 문서/가이드 업데이트 여부 확인

## 📌 참고 사항
- 로컬 환경 파일과 시크릿은 커밋에 포함하지 않는다.
```

# PR Body

```markdown
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
```
