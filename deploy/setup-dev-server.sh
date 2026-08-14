#!/usr/bin/env bash
#
# dev EC2 초기 셋업 (Ubuntu 24.04, ubuntu 계정에서 1회 실행)
#
#   curl -fsSL <이 파일 URL> -o setup.sh && bash setup.sh
#   또는  scp 로 올린 뒤  bash setup-dev-server.sh
#
# 설치 버전은 전부 운영 서버(13.251.154.173)에서 실측한 값에 맞췄다. dev 의 존재 이유가
# "운영에서 터질 것을 먼저 만나는 것"이므로, 버전이 어긋나면 그 목적이 사라진다.
#
#   openjdk-25-jdk   25.0.3+9-2~24.04.2   (Ubuntu 24.04 기본 저장소)
#   mysql-server     8.0.46-0ubuntu0.24.04.3 (동, 8.0 계열)
#   node             22.x  (nodesource)
#   pm2              7.x
#   docker.io        29.x  (Ubuntu 기본 저장소)
#   nginx            1.24  (동)
#
# 이 스크립트는 재실행해도 안전하도록 각 단계에 존재 검사를 둔다.

set -euo pipefail

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

if [ "$(id -u)" -eq 0 ]; then
  echo "root 로 실행하지 마세요. ubuntu 계정에서 실행하면 필요한 곳에서만 sudo 를 씁니다." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 1. 스왑
#
# 운영에는 스왑이 0 이다. 이 앱은 프리뷰 컨테이너를 개당 1 GiB 로 띄우므로
# (DockerContainerService.MEMORY_LIMIT_BYTES), 메모리가 튀는 순간 완충 없이 OOM killer 로
# 간다. 그리고 OOM killer 는 RSS 가 가장 큰 프로세스 — 즉 앱 자신 — 를 고른다.
# dev 는 그 경로를 일부러 돌려보는 서버이므로 스왑이 특히 필요하다.
# ---------------------------------------------------------------------------
log "스왑 4G 설정"
if swapon --show | grep -q '/swapfile'; then
  echo "이미 활성화됨 — 건너뜀"
else
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi
# 스왑이 있어도 되도록 안 쓰는 편이 낫다(디스크 I/O). OOM 방지용 완충으로만 둔다.
sudo sysctl -w vm.swappiness=10 >/dev/null
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf >/dev/null

log "apt 갱신"
sudo apt-get update -y

# ---------------------------------------------------------------------------
# 2. JDK 25
#
# Ubuntu 24.04 기본 저장소에 있다. 별도 PPA/Adoptium 이 필요 없다 — 운영도 이 경로로 깔렸다.
# build.gradle 의 툴체인이 25 를 요구하고 CI(temurin 25)와도 맞는다.
# ---------------------------------------------------------------------------
log "JDK 25 설치"
sudo apt-get install -y openjdk-25-jdk-headless
java -version

# ---------------------------------------------------------------------------
# 3. MySQL 8.0
#
# 버전을 명시하지 않는다. Ubuntu 24.04 의 mysql-server 가 곧 8.0 계열이고, 운영이 받은
# 것과 같은 패키지가 깔린다. 8.0.46 처럼 특정 패치버전으로 고정하면 저장소가 앞으로
# 나가는 순간 설치가 깨진다 — 맞춰야 하는 것은 패치버전이 아니라 8.0 이라는 계열이다.
# ---------------------------------------------------------------------------
log "MySQL 8.0 설치"
sudo apt-get install -y mysql-server
mysql --version

log "MySQL 스키마/계정 생성"
# 3306 은 보안그룹에서 막더라도 바인딩 자체를 loopback 으로 좁혀 이중으로 막는다.
sudo sed -i 's/^bind-address.*/bind-address = 127.0.0.1/' /etc/mysql/mysql.conf.d/mysqld.cnf
sudo systemctl restart mysql

# 비밀번호는 이 스크립트에 넣지 않는다. 두 가지 경로를 지원한다.
#   대화형  : 그냥 실행하면 물어본다
#   자동    : DB_PASS=... bash setup-dev-server.sh   (SSH 로 원격 실행할 때)
# 어느 쪽이든 같은 값을 ecosystem.config.js 의 DB_PASSWORD 에 넣어야 한다.
if [ -z "${DB_PASS:-}" ]; then
  read -rsp "dev DB 비밀번호를 입력하세요 (dvely 계정): " DB_PASS; echo
fi
sudo mysql <<SQL
CREATE DATABASE IF NOT EXISTS dvely CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'dvely'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON dvely.* TO 'dvely'@'localhost';
FLUSH PRIVILEGES;
SQL
unset DB_PASS
echo "스키마 dvely / 계정 dvely@localhost 준비 완료"

# ---------------------------------------------------------------------------
# 4. Node + pm2
#
# 앱 자체는 Node 를 쓰지 않는다(코드 생성은 컨테이너 안 node:20-alpine 이 한다).
# 호스트의 Node 는 순전히 pm2 를 돌리기 위한 것이다.
# ---------------------------------------------------------------------------
log "Node 22 + pm2 설치"
if ! command -v node >/dev/null; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
  sudo apt-get install -y nodejs
fi
sudo npm install -g pm2@7
node -v; pm2 -v

# ---------------------------------------------------------------------------
# 5. Docker
#
# 제품 기능이다. 에이전트 CODE 스텝과 프리뷰 세션이 node:20-alpine 컨테이너를 띄운다.
# DockerContainerService 가 첫 사용 시점까지 연결을 미루므로 Docker 가 죽어 있어도 앱은
# 뜨지만, 그 상태로는 에이전트가 아무 일도 못 한다.
# ---------------------------------------------------------------------------
log "Docker 설치"
sudo apt-get install -y docker.io
sudo systemctl enable --now docker
# ubuntu 가 sudo 없이 docker 를 쓸 수 있어야 pm2 로 뜬 앱이 컨테이너를 만든다.
sudo usermod -aG docker ubuntu

# ⚠️ 여기서 pm2 데몬을 반드시 죽인다.
#
# pm2 가 띄우는 프로세스는 "pm2 명령을 실행한 셸"이 아니라 이미 떠 있는 God 데몬의
# 자격증명을 물려받는다. 위 usermod 이전에 God 데몬이 한 번이라도 떴다면(앞 단계의
# `pm2 -v` 만으로도 뜬다) 그 데몬은 docker 그룹이 없는 상태로 고정되고, 이후 배포에서
# `pm2 startOrRestart` 를 아무리 새 SSH 세션에서 돌려도 자식 프로세스에 docker 그룹이
# 붙지 않는다. 증상은 앱은 정상 기동하는데 에이전트 실행만
#   "Failed create socket with path /var/run/docker.sock"
# 으로 실패하는 것이라, 원인을 찾기 어렵다. 실제로 그렇게 한 번 당했다.
sudo pkill -f 'PM2 v' 2>/dev/null || true
pm2 kill >/dev/null 2>&1 || true
# 첫 에이전트 실행이 이미지 pull 로 수 분 걸리는 것을 막는다.
sudo docker pull node:20-alpine

log "도커 정리 cron 등록"
# 프리뷰 컨테이너와 레이어가 계속 쌓여 디스크를 채운다. dev 는 특히 빠르게 찬다.
#
# `crontab -l | grep -v ...` 를 그대로 쓰면 안 된다. crontab 이 비어 있는 첫 실행에서
# grep 이 매칭 0건으로 exit 1 을 내고, set -e + pipefail 에 걸려 스크립트가 여기서 죽는다.
# 실제로 그렇게 죽어서 아래 nginx 단계가 통째로 건너뛰어진 적이 있다. || true 로 막는다.
CRON='0 4 * * * docker system prune -af --filter "until=72h" >/dev/null 2>&1'
TMP_CRON=$(mktemp)
crontab -l 2>/dev/null | grep -v 'docker system prune' > "$TMP_CRON" || true
echo "$CRON" >> "$TMP_CRON"
crontab "$TMP_CRON"
rm -f "$TMP_CRON"

# ---------------------------------------------------------------------------
# 6. nginx
#
# 도메인 없이 EIP 만 쓰므로 TLS 는 없다(Let's Encrypt 는 IP 에 인증서를 발급하지 않는다).
# 그래도 nginx 를 두는 이유는 콜백 URL 에 :8080 이 붙지 않게 하기 위해서다. 나중에
# 도메인을 붙일 때 GitHub 앱 설정의 경로를 그대로 두고 호스트만 바꾸면 된다.
# ---------------------------------------------------------------------------
log "nginx 설치 및 리버스 프록시 설정"
sudo apt-get install -y nginx
sudo tee /etc/nginx/sites-available/dvely-dev >/dev/null <<'NGINX'
server {
    listen 80 default_server;
    server_name _;

    # 에이전트 진행 상황이 SSE(/api/v1/agent/tasks/{id}/events/stream)로 나간다.
    # 버퍼링을 끄지 않으면 이벤트가 뭉쳐서 도착해 실시간처럼 보이지 않는다.
    proxy_buffering off;
    proxy_cache off;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection        '';

        # 에이전트 실행은 길다. 기본 60초로는 SSE 가 끊긴다.
        proxy_read_timeout    3600s;
        proxy_send_timeout    3600s;
        proxy_connect_timeout 10s;
    }
}
NGINX
sudo rm -f /etc/nginx/sites-enabled/default
sudo ln -sf /etc/nginx/sites-available/dvely-dev /etc/nginx/sites-enabled/dvely-dev
sudo nginx -t
sudo systemctl reload nginx

# ---------------------------------------------------------------------------
# 7. 배포 디렉터리
#
# 경로와 pm2 앱 이름을 운영과 동일하게 둔다. 서버가 다르므로 이름이 겹쳐도 무관하고,
# 같아야 deploy 워크플로를 그대로 재사용할 수 있다. 전부 ubuntu 소유라 배포 전 과정에
# sudo 가 한 번도 필요하지 않다.
# ---------------------------------------------------------------------------
log "배포 디렉터리 생성"
sudo mkdir -p /var/www/dvely/backend /var/www/dvely/staging
sudo chown -R ubuntu:ubuntu /var/www/dvely

log "완료"
cat <<'DONE'

남은 수동 작업 3가지
--------------------
1) docker 그룹 반영을 위해 SSH 를 한 번 끊었다 다시 접속한다.

2) /var/www/dvely/backend/ecosystem.config.js 를 만든다.
   레포의 deploy/ecosystem.config.dev.js.example 을 복사해 <EIP> 와 비밀번호를 채운다.

3) GitHub 저장소 Secrets 에 아래를 등록한다(운영과 별개의 값).
     DEV_EC2_HOST      = <EIP>
     DEV_EC2_USER      = ubuntu
     DEV_EC2_SSH_KEY   = dvely-dev-key.pem 내용
     DEV_EC2_SSH_PORT  = 22   (기본값이면 생략 가능)

확인
----
   free -h              스왑 4G 가 보이는지
   mysql --version      8.0.x 인지
   docker images        node:20-alpine 이 있는지
   curl -I localhost    nginx 가 502 를 주면 정상(앱이 아직 없음)
DONE
