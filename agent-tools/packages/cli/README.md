# @qeploy/cli

> **⚠️ 서비스 일시 중단 (2026-10-30부터)** — 운영 서버 사정으로 Qeploy 서비스를 일시 중단했습니다. 재개할 때까지 로그인과 모든 도구 호출이 실패합니다. 0.1.4 부터는 그때 "대상을 찾을 수 없습니다" 대신 사이트가 내건 중단 안내([`status.json`](https://qeploy.com/status.json))를 그대로 보여줍니다. 재개 일정은 [qeploy.com](https://qeploy.com) 에서 알립니다.

[Qeploy](https://qeploy.com) 커맨드라인. MCP 를 못 쓰는 에이전트·CI·사람용.

```bash
npx @qeploy/cli login
npx @qeploy/cli projects
npx @qeploy/cli status <deploymentId>
npx @qeploy/cli deploy <projectId> --yes
```

토큰은 옵션으로 받지 않는다(셸 히스토리·`ps` 에 남는다). 환경변수 `QEPLOY_TOKEN` 이나 `login` 이 저장한
파일만 읽는다. 종료 코드: 0 성공 · 1 실패 · 2 사용법 오류 · 3 인증 실패(로그인 안 함 포함).

## 내 기기 프리뷰 공유 — `share web`

내 컴퓨터에서 도는 웹 앱 화면을 링크로 공유한다. 받은 사람은 설치 없이 브라우저에서 같은 화면을 보고,
내가 승인하면 조작까지 한다. WRITE 스코프 토큰이 필요하다.

```bash
qeploy share web http://localhost:5173                       # 데스크톱 크기
qeploy share web http://localhost:5173 --viewport 390x844    # 폰 화면 흉내
```

```
보기 링크:    https://k3x9…q2.share.qeploy.com/       ← 남에게 줄 링크. 보기만 되고 조작은 내가 승인
내 제어 링크: https://k3x9…q2.share.qeploy.com/#c=…   ← 나만 쓰는 링크. 열면 바로 조작
Ctrl+C 로 공유를 끝냅니다.
```

| 옵션 | 뜻 |
|---|---|
| `--viewport 390x844` | 화면 크기. 너비 600 미만이면 모바일로 흉내 낸다 |
| `--ttl 60` | 링크 유효 시간(분). 기본 60, 최대 1440 |
| `--headed` | Chrome 창을 띄운다(기본은 창 없이) |
| `--json` | 만든 링크 정보를 JSON 으로 stdout 에 출력 |

- 설치된 Chrome·Chromium·Edge 를 찾아 쓴다. 못 찾으면 `CHROME_PATH` 로 실행 파일을 알려준다.
- Chrome 은 늘 **새 임시 프로필**로 띄우고 끝나면 지운다. 내 쿠키·저장된 비밀번호·확장이 없다.
- 공유 탭은 `<url>` 과 **같은 오리진에서만** 움직인다. 다른 주소(다른 포트의 localhost, 사내망, `file://`)로 가는 이동,
  다운로드, 파일 올리기, 붙여넣기(내 클립보드)는 막는다. 새 창·탭은 닫는다.
- 보기 링크로 들어온 사람이 조작을 요청하면 터미널에 묻는다(`y` 면 10분, 30초 안에 답이 없거나 터미널이 아니면 거절).
- 연결이 끊기면 1·2·4·8·16·30초 간격으로 다시 잇는다. 링크가 회수·만료되면 멈춘다.
- Ctrl+C 로 끝내면 링크를 회수하고 Chrome 과 임시 프로필을 지운다.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
