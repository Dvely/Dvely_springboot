# EC2 배포 설정

`.github/workflows/deploy-ec2.yml`은 **main에 커밋이 올라올 때마다**(= PR 병합 시) 실행되며, 앱은 EC2에서 **pm2**가 `ubuntu` 계정으로 돌린다. 최초 1회만 준비하면 이후 배포는 병합만으로 끝난다.

배포가 하는 일은 셋뿐이다. **jar를 올리고, 바꿔치고, pm2를 재시작한다.** `ecosystem.config.js`는 건드리지 않으므로 시크릿이 CI를 경유하지 않고 서버에만 남는다.

## 왜 sudo가 없나

`/var/www/dvely` 이하가 전부 `ubuntu` 소유이고 pm2도 `ubuntu`로 돌기 때문에, 배포 전 과정에 root 권한이 한 번도 필요하지 않다. 배포 계정에 sudo를 주지 않는 것 자체가 가장 확실한 방어라서, 래퍼 스크립트나 `sudoers` 규칙 같은 장치도 필요 없다.

다만 그 대가로 **앱이 `ubuntu` 계정으로 돈다.** 이 계정은 NOPASSWD sudo를 가지고 있어서, 앱이 원격 코드 실행에 뚫리면 그대로 root로 이어진다. 앱 전용 비특권 계정으로 분리하려면 pm2를 그 계정으로 돌리거나 systemd 유닛으로 옮겨야 한다. 지금 구성은 그 위험을 알고 받아들인 것이다.

## 1. 디렉터리

```bash
sudo mkdir -p /var/www/dvely/backend /var/www/dvely/staging
sudo chown -R ubuntu:ubuntu /var/www/dvely
```

`staging`은 워크플로가 jar를 올려두는 곳이다. 체크섬 대조를 통과한 뒤에만 `backend/app.jar`로 옮긴다.

## 2. 환경변수와 GitHub App 키

```bash
cp ecosystem.config.js.example /var/www/dvely/backend/ecosystem.config.js
vi /var/www/dvely/backend/ecosystem.config.js        # 값 채우기
chmod 600 /var/www/dvely/backend/ecosystem.config.js # 시크릿이 들어가므로 필수

install -m 0600 github-app.pem /var/www/dvely/backend/github-app.pem
```

`chmod 600`을 빠뜨리지 말 것. pm2 기본 생성 권한은 `0664`라서 서버의 다른 계정이 시크릿을 그대로 읽을 수 있다.

PEM은 여러 줄이라 JS 문자열에 넣기 번거롭다. `GithubAppClient`는 값이 `-----BEGIN`으로 시작하지 않으면 파일 경로로 읽으므로 경로만 넘긴다.

## 3. pm2 등록과 부팅 자동시작

```bash
cd /var/www/dvely/backend
pm2 startOrRestart ecosystem.config.js --update-env
pm2 save
pm2 startup    # 출력되는 sudo 명령을 한 번 실행하면 재부팅 시 자동 기동
```

## 4. Docker (CODE 에이전트용)

```bash
sudo apt-get install -y docker.io
sudo systemctl enable --now docker
sudo usermod -aG docker ubuntu   # 앱이 /var/run/docker.sock 을 쓴다
```

빠뜨려도 앱은 정상 기동한다. `DockerContainerService`가 첫 사용 시점까지 연결을 미루기 때문이다. 대신 CODE 에이전트나 프로젝트 프리뷰가 컨테이너를 띄우려는 순간 실패한다.

그 침묵을 없애려고 기동 직후 데몬에 핑을 한 번 넣는다(`PreviewEnvironmentHealthLogger`). 연결되지 않으면 위 설치 명령과 함께 경고가 남고, 프리뷰 API는 500이 아니라 **503 `PREVIEW_ENVIRONMENT_UNAVAILABLE`**로 원인을 적어 응답한다.

```
[PreviewEnv] Docker 연결 정상 · 프리뷰 기준 오리진 = https://qeploy.com     # 정상
[PreviewEnv] Docker 데몬에 연결하지 못했습니다. ...                          # 조치 필요
```

### 그룹 추가만으로는 적용되지 않는다

`usermod -aG docker` 는 **이미 떠 있는 프로세스에 적용되지 않는다.** `pm2 restart` 도 소용없다 —
pm2 **데몬**이 옛 자격증명을 그대로 들고 있어서, 그 아래 재시작된 앱도 docker 그룹 없이 뜬다.
데몬까지 새로 띄워야 한다.

```bash
sudo systemctl restart pm2-ubuntu      # 3번에서 pm2 startup 을 등록했다면 이 한 줄로 끝난다

# 등록하지 않았다면: 그룹은 새 로그인부터 적용되므로 반드시 재접속한 뒤
id -nG | tr ' ' '\n' | grep docker     # docker 가 보여야 한다
pm2 kill
cd /var/www/dvely/backend && pm2 startOrRestart ecosystem.config.js --update-env && pm2 save
```

빠뜨리면 설치는 끝났는데도 앱만 계속 실패하고, 프리뷰 API 는 그 원인을 그대로 싣는다
(2026-08-15 운영 서버 실사례).

```
503  프리뷰 컨테이너를 시작하지 못했습니다. 서버의 Docker 실행 환경을 확인해주세요.
     (원인: Permission denied)
```

`Permission denied` 는 "Docker 가 없다" 가 아니라 "이 절차가 아직 적용되지 않았다" 는 뜻이다.

## 5. GitHub Secrets

| 이름 | 필수 | 설명 |
|---|---|---|
| `EC2_HOST` | 예 | EC2 퍼블릭 주소 |
| `EC2_USER` | 예 | SSH 접속 계정 (예: `ubuntu`) |
| `EC2_SSH_KEY` | 예 | 개인키 전문 |
| `EC2_SSH_PORT` | 아니오 | 기본 22 |

`EC2_SSH_KEY`는 **웹 UI에 붙여넣지 말 것.** 줄바꿈이 뭉개져 PEM 블록이 깨지면 `ssh: no key found`로 실패한다. CLI로 파일을 그대로 넘기면 이 문제가 없다.

```bash
gh secret set EC2_SSH_KEY < ~/.ssh/dvely-key.pem
```

퍼블릭 IP는 Elastic IP를 붙이지 않으면 인스턴스를 중지·시작할 때마다 바뀐다. `EC2_HOST`가 낡으면 `dial tcp ...:22: i/o timeout`으로 실패한다.

## 배포 방법

main으로 PR을 병합하면 끝이다. 별도 조작은 없다.

코드 변경 없이 다시 배포해야 할 때(롤백 후 재배포 등)는 Actions 탭에서 **Deploy to EC2 → Run workflow**로 수동 실행한다.

`push: branches: [main]` 이므로 main에 직접 푸시해도 배포가 돈다. main을 보호 브랜치로 걸어 PR로만 들어오게 해 두는 것을 전제한다.

## 롤백

직전 jar가 `/var/www/dvely/backend/app.jar.prev`로 남는다.

```bash
cd /var/www/dvely/backend
cp app.jar.prev app.jar
pm2 startOrRestart ecosystem.config.js --update-env
```

## 주의

- **호스트 타임존은 `Asia/Seoul`이어야 한다.** `DB_URL`이 `serverTimezone=Asia/Seoul`로 접속하므로 Connector/J가 JVM 기본 타임존과 그 값 사이에서 시각을 변환한다. 호스트가 UTC면 앱이 쓴 시각이 +9된 값으로 저장돼 같은 DB의 `NOW()`·`DEFAULT CURRENT_TIMESTAMP`와 9시간 어긋난다. 앱 안에서는 읽을 때 같은 폭으로 되돌아오므로 **기능은 멀쩡히 돌고 테스트도 통과한다** — SQL로 직접 들여다볼 때만 드러나고, 그때 만료 시각 같은 값을 잘못 읽게 된다. 실제로 dev가 Ubuntu 기본값인 UTC로 떠 있어 이 상태였다(2026-08-16 수정). MySQL은 `time_zone=SYSTEM`이라 **기동 시점의** 호스트 타임존을 잡으므로, 바꿨으면 MySQL과 앱을 모두 재기동해야 한다.
  ```bash
  timedatectl show -p Timezone --value        # => Asia/Seoul
  mysql -e "select now(), utc_timestamp();"   # 두 값이 9시간 차이여야 한다
  ```
- **`pm2 restart dvely-backend` 로는 env가 갱신되지 않는다.** pm2는 프로세스 생성 당시의 env를 저장해두고 재사용하므로, `ecosystem.config.js`를 고쳐도 반영되지 않는다. 반드시 파일을 인자로 주고 `--update-env`를 붙여야 한다. 이어서 `pm2 save`까지 해야 재부팅 후에도 유지된다 — 안 하면 `~/.pm2/dump.pm2`의 옛 env로 되돌아간다.
- 워크플로는 테스트를 돌리지 않는다(`-x test`). 검증은 `ci.yml`이 PR 단계에서 MySQL을 붙여 수행한다. **main 보호 규칙에 `Build and test`를 required status check로 걸어야** 실제로 병합이 막힌다. 워크플로 파일만으로는 강제되지 않는다.
- 시크릿이 비어 있어도 **앱은 정상 기동한다.** 이 값들은 `@Value` 지연 주입이라 기동 시점에 검증되지 않고, 해석 못 한 `${GITHUB_OAUTH_CLIENT_ID}` 같은 문자열이 그대로 OAuth URL에 실려 나간다. 배포 후 아래로 실제 값이 들어갔는지 확인할 것.
  ```bash
  curl -s http://127.0.0.1:8080/api/v1/auth/github/url
  # client_id=${GITHUB_OAUTH_CLIENT_ID} 처럼 나오면 env가 안 들어간 것이다
  ```
- `application.yaml`의 `baseline-version: 5`는 **테이블은 이미 있는데 `flyway_schema_history`가 없는 스키마**에 붙었을 때만 동작한다. 그 경우 V5로 baseline이 잡혀 V1~V5가 건너뛰어지므로, 그 다섯 개가 만드는 테이블이 없으면 `ddl-auto: validate`에서 기동이 실패한다. 완전히 빈 스키마라면 baseline 없이 V1부터 전부 실행되므로 그냥 두면 된다.
- `application.yaml`의 `spring.flyway` / `spring.jpa` 블록 위에 최상위 키를 끼워 넣지 말 것. 들여쓰기상 그 키의 자식이 되어 설정이 통째로 무시되는데, 바인딩 실패가 조용해서 앱은 그대로 뜬다. 실제로 한 번 발생했다(#60 → #79에서 수정).
- 배포 후 기동 확인은 `http://127.0.0.1:8080/actuator/health`를 폴링한다. 포트가 워크플로에 하드코딩돼 있으므로 `SERVER_PORT`로 포트를 바꾸면 워크플로도 같이 고쳐야 한다.
- `/actuator/health`는 인증 없이 열려 있다(`show-details: never`라 상태값만 응답). 8080을 인터넷에 직접 노출하지 말고 리버스 프록시나 보안그룹으로 막는 것을 전제한다.
