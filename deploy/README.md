# EC2 배포 설정

`.github/workflows/deploy-ec2.yml`은 `v*` 태그를 푸시할 때만 실행되며, 아래 환경이 EC2에 이미 갖춰져 있다고 가정한다. 최초 1회만 수동으로 준비하면 이후 배포는 태그 푸시로 끝난다.

## 1. 서비스 계정과 디렉터리

```bash
sudo useradd --system --shell /usr/sbin/nologin --home-dir /opt/dvely dvely
sudo usermod -aG docker dvely          # 앱이 /var/run/docker.sock 을 쓴다
sudo mkdir -p /opt/dvely /etc/dvely
sudo chown dvely:dvely /opt/dvely
```

`docker` 그룹 추가를 빠뜨려도 앱은 정상 기동한다. `DockerContainerService`가 첫 사용
시점까지 연결을 미루기 때문이다. 대신 CODE 에이전트가 컨테이너를 띄우려는 순간 실패하므로,
기동 로그만 보고 정상이라 판단하기 쉽다.

## 2. 환경변수와 GitHub App 키

```bash
sudo install -m 0640 -o root -g dvely dvely.env.example /etc/dvely/dvely.env
sudo vi /etc/dvely/dvely.env                       # 값 채우기
sudo install -m 0640 -o root -g dvely github-app.pem /etc/dvely/github-app.pem
```

PEM은 여러 줄이라 env 파일에 못 넣는다. `GithubAppClient`는 값이 `-----BEGIN`으로
시작하지 않으면 파일 경로로 읽으므로 경로만 넘긴다.

## 3. systemd 유닛 등록

```bash
sudo install -m 0644 dvely.service /etc/systemd/system/dvely.service
sudo systemctl daemon-reload
sudo systemctl enable --now dvely
systemctl status dvely
```

## 4. 배포 계정의 sudo 권한

워크플로는 SSH로 붙어 jar를 교체하고 서비스를 재시작한다. 해당 계정(`EC2_USER`)이
비밀번호 없이 아래를 실행할 수 있어야 한다.

sudo는 인자까지 그대로 대조하므로 스크립트가 호출하는 형태와 정확히 일치해야 한다.
경로도 실제 위치와 같아야 한다. 우분투에서는 `command -v systemctl`이 `/usr/bin/systemctl`을
가리키므로 `/bin/systemctl`로 적으면 매칭되지 않는다.

```
# /etc/sudoers.d/dvely-deploy  (visudo -f 로 편집할 것)
ubuntu ALL=(root) NOPASSWD: /usr/bin/install, /usr/bin/cp, /usr/bin/systemctl restart dvely
```

`is-active`와 `journalctl`은 sudo 없이 실행한다. 다만 배포 계정이 서비스 로그를 읽으려면
저널 접근 권한이 필요하다.

```bash
sudo usermod -aG systemd-journal ubuntu
```

이걸 빠뜨리면 배포는 되지만, 실패했을 때 워크플로 로그에 원인이 안 찍힌다.

## 5. GitHub Secrets

| 이름 | 필수 | 설명 |
|---|---|---|
| `EC2_HOST` | 예 | EC2 퍼블릭 주소 |
| `EC2_USER` | 예 | SSH 접속 계정 (예: `ubuntu`) |
| `EC2_SSH_KEY` | 예 | 개인키 전문 |
| `EC2_SSH_PORT` | 아니오 | 기본 22 |

## 배포 방법

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 롤백

직전 jar가 `/opt/dvely/app.jar.prev`로 남는다.

```bash
sudo cp /opt/dvely/app.jar.prev /opt/dvely/app.jar
sudo systemctl restart dvely
```

## 주의

- 워크플로는 테스트를 돌리지 않는다(`-x test`). `@SpringBootTest` 3종이 실제 MySQL과
  gitignore된 `application-local.yml`을 요구하기 때문이다. 태그를 밀기 전에 로컬에서
  `./gradlew test`를 돌리는 것을 전제로 한다.
- `application.yaml`의 `baseline-version: 5` 때문에 **빈 DB에서는 V1~V5가 실행되지 않는다.**
  새 DB로 붙일 때는 `schema.sql`로 기준 스키마를 먼저 만들거나 baseline을 조정해야 한다.
