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

## Android 에뮬레이터 공유 — `share android`

에뮬레이터에 떠 있는 앱 화면을 같은 방식으로 공유한다. 보는 사람이 조작권을 받으면 터치·스크롤·키 입력·한글 입력이 에뮬레이터로 들어간다.

```bash
qeploy share android                         # 지금 앞에 떠 있는 앱
qeploy share android --app com.example.app   # 이 앱을 띄워서 공유
```

| 옵션 | 뜻 |
|---|---|
| `--app <패키지>` | 공유할 앱. 없으면 지금 앞에 떠 있는 앱(홈 화면이면 이 옵션이 필요하다) |
| `--any-app` | 앱으로 묶지 않고 에뮬레이터 전체를 공유한다(경고가 뜬다) |
| `--serial emulator-5554` | 에뮬레이터가 여러 대일 때 고른다 |
| `--max-size 1280` | 화면 긴 변(px). 기본 1280 |
| `--bit-rate 2M` | 영상 비트레이트. 기본 2Mbps |
| `--ttl 60` · `--json` | `share web` 과 같다 |

- **준비물**
  - Android SDK platform-tools(`adb`). `ANDROID_HOME` 이나 기본 SDK 위치에서 찾는다
  - scrcpy 4.x·5.x(`brew install scrcpy`). 그 안의 서버만 쓰고 scrcpy 창은 띄우지 않는다. 다른 위치라면 `QEPLOY_SCRCPY_SERVER`·`QEPLOY_SCRCPY_VERSION`
- 보는 사람 브라우저가 WebCodecs 로 H.264 를 푼다(최신 Chrome·Edge·Safari·Firefox).
- **에뮬레이터만** 받는다. 실제 폰에는 계정·사진이 있어서다.
- **공유는 앱 하나로 묶인다.** 1초마다 맨 앞 앱을 보고, 다른 앱(홈 화면, 에뮬레이터 Chrome 으로 내 localhost 인 `10.0.2.2` 등)으로 나가면 공유한 앱을 다시 앞으로 올린다. 권한 대화상자는 허용한다.
- 한글처럼 키보드로 바로 못 넣는 글자는 에뮬레이터 클립보드에 넣고 붙여넣는다. 에뮬레이터 클립보드는 밖으로 보내지 않는다.
- Ctrl+C 로 끝내면 링크를 회수하고 에뮬레이터의 scrcpy 서버와 `adb forward` 를 정리한다.

자세한 내용: https://github.com/Dvely/Dvely_springboot/tree/main/agent-tools
