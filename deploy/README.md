# EC2 배포 설정

`.github/workflows/deploy-ec2.yml`은 `v*` 태그를 푸시할 때만 실행되며, 아래 환경이 EC2에 이미 갖춰져 있다고 가정한다. 최초 1회만 수동으로 준비하면 이후 배포는 태그 푸시로 끝난다.

## 1. 서비스 계정과 디렉터리

```bash
sudo useradd --system --shell /usr/sbin/nologin --home-dir /opt/dvely dvely
sudo usermod -aG docker dvely          # 앱이 /var/run/docker.sock 을 쓴다
sudo mkdir -p /opt/dvely /etc/dvely
sudo chown dvely:dvely /opt/dvely

# 워크플로가 jar를 올려두는 곳. 반드시 root 소유여야 한다.
# /tmp 아래에 두면 권한 낮은 로컬 계정이 디렉터리를 미리 만들어 소유권을 가로챈 뒤
# jar를 바꿔치기하거나 심볼릭 링크를 심을 수 있고, 그걸 root로 도는 래퍼가 그대로 읽는다.
sudo mkdir -p /var/lib/dvely/staging
sudo chown root:ubuntu /var/lib/dvely/staging   # ubuntu = EC2_USER
sudo chmod 0770 /var/lib/dvely/staging
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

워크플로는 SSH로 붙어 jar를 교체하고 서비스를 재시작한다. 이 두 동작만 root로 수행하도록
인자 없는 래퍼 스크립트를 설치하고, 그 스크립트 하나만 sudo로 허용한다.

```bash
sudo install -m 0755 -o root -g root dvely-deploy.sh /usr/local/sbin/dvely-deploy
```

```
# /etc/sudoers.d/dvely-deploy  (visudo -f 로 편집할 것)
ubuntu ALL=(root) NOPASSWD: /usr/local/sbin/dvely-deploy
```

`install`이나 `cp`를 직접 NOPASSWD로 허용하면 안 된다. sudoers는 인자를 제한하지 않으므로
`sudo install -m 4755 /tmp/sh /usr/local/bin/rootsh` 같은 호출이 통해 사실상 무제한 root
권한이 된다. 래퍼는 인자를 받지 않아 그 여지가 없다.

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
- 배포 후 기동 확인은 `http://127.0.0.1:8080/actuator/health`를 폴링한다. 포트가 워크플로에
  하드코딩돼 있으므로 `SERVER_PORT`로 포트를 바꾸면 워크플로도 같이 고쳐야 한다. 안 그러면
  헬스체크가 엉뚱한 포트를 보고 배포가 실패로 끝난다.
- `/actuator/health`는 인증 없이 열려 있다(`show-details: never`라 상태값만 응답). 8080을
  인터넷에 직접 노출하지 말고 리버스 프록시나 보안그룹으로 막는 것을 전제한다.
