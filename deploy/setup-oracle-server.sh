#!/usr/bin/env bash
#
# Oracle Cloud(OCI) Always Free 운영 서버 초기 셋업 (#649)
#
# 대상: Canonical Ubuntu 24.04 (aarch64) · VM.Standard.A1.Flex · ubuntu 계정에서 1회 실행
#
#   API_HOST=api.qeploy.com CERT_EMAIL=<인증서 만료 안내 받을 메일> bash setup-oracle-server.sh
#
#   API_HOST   비우면 TLS 없이 80 번으로만 연다(DNS 가 아직 이 서버를 가리키지 않을 때)
#   CERT_EMAIL Let's Encrypt 계정 메일. API_HOST 와 함께 줄 때만 인증서를 받는다
#   DB_PASS    비우면 대화형으로 묻는다
#
# 순서는 setup-dev-server.sh(EC2)와 같다. 다른 점만 적는다.
#   - 호스트 iptables: OCI Ubuntu 이미지는 22 번 말고 들어오는 연결을 전부 REJECT 한다.
#     클라우드 보안 목록에서 80·443 을 열어도 이걸 열지 않으면 접속이 안 된다.
#   - ARM: 앱(jar)·MySQL·Docker·node:20-alpine 은 그대로 돈다. 사용자 배포 이미지는
#     ContainerImageBuilder 가 linux/amd64 로 고정해 빌드하므로 이 서버에서는 에뮬레이션이
#     필요하다(#556·#580). 프리뷰와 코딩 에이전트에는 영향이 없다.
#   - TLS: FE 가 qeploy.com 을 GitHub Pages 로 쓰므로 백엔드는 별도 호스트에 두고
#     Let's Encrypt 인증서를 받는다.
#
# 재실행해도 안전하도록 각 단계에 존재 검사를 둔다.

set -euo pipefail

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[주의] %s\033[0m\n' "$*" >&2; }

if [ "$(id -u)" -eq 0 ]; then
  echo "root 로 실행하지 마세요. ubuntu 계정에서 실행하면 필요한 곳에서만 sudo 를 씁니다." >&2
  exit 1
fi

API_HOST="${API_HOST:-}"
CERT_EMAIL="${CERT_EMAIL:-}"

if [ "$(uname -m)" != "aarch64" ]; then
  warn "aarch64 가 아닙니다($(uname -m)). OCI A1 인스턴스 기준 스크립트지만 그대로 진행합니다."
fi
. /etc/os-release
if [ "${VERSION_ID:-}" != "24.04" ]; then
  warn "Ubuntu 24.04 가 아닙니다(${PRETTY_NAME:-unknown}). JDK 25 패키지가 없을 수 있습니다."
fi

# ---------------------------------------------------------------------------
# 1. 스왑
#
# 프리뷰 컨테이너를 개당 1 GiB 로 띄운다(DockerContainerService.MEMORY_LIMIT_BYTES).
# 메모리가 튀면 OOM killer 가 RSS 가 가장 큰 앱 자신을 고르므로 완충으로 둔다.
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
sudo sysctl -w vm.swappiness=10 >/dev/null
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf >/dev/null

# ---------------------------------------------------------------------------
# 2. 타임존 (Asia/Seoul)
#
# DB_URL 이 serverTimezone=Asia/Seoul 로 접속한다. 호스트가 UTC 면 앱이 쓴 시각이 DB 의
# NOW() 와 9시간 어긋난다(deploy/README.md "주의"). MySQL 은 기동 시점의 호스트 타임존을
# 잡으므로 MySQL 설치보다 먼저 한다. OCI 이미지 기본값도 UTC 다.
# ---------------------------------------------------------------------------
log "타임존 Asia/Seoul 설정"
if [ "$(timedatectl show -p Timezone --value)" = "Asia/Seoul" ]; then
  echo "이미 Asia/Seoul — 건너뜀"
else
  sudo timedatectl set-timezone Asia/Seoul
  if systemctl is-active --quiet mysql; then
    sudo systemctl restart mysql
  fi
fi

# ---------------------------------------------------------------------------
# 3. 호스트 방화벽 (OCI 전용)
#
# OCI 의 Ubuntu 이미지는 /etc/iptables/rules.v4 에 INPUT 마지막 규칙으로
#   -A INPUT -j REJECT --reject-with icmp-host-prohibited
# 를 두고, 그 앞에서 22 번만 허용한다. 80·443 을 그 REJECT 앞에 끼워 넣는다.
#
# Docker 설치보다 먼저 저장해야 한다. Docker 가 만든 체인까지 rules.v4 에 저장되면 부팅 때
# Docker 보다 먼저 복원되어 규칙이 겹친다. 그래서 이 단계는 규칙을 새로 넣었을 때만 저장한다.
# ---------------------------------------------------------------------------
log "호스트 방화벽에서 80·443 열기"
changed=0
for port in 80 443; do
  if sudo iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2>/dev/null; then
    echo "${port} 이미 열림 — 건너뜀"
    continue
  fi
  reject_line=$(sudo iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" {print $1; exit}')
  if [ -n "$reject_line" ]; then
    sudo iptables -I INPUT "$reject_line" -p tcp -m state --state NEW --dport "$port" -j ACCEPT
  else
    sudo iptables -A INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT
  fi
  changed=1
done
if [ "$changed" -eq 1 ]; then
  if command -v netfilter-persistent >/dev/null; then
    sudo netfilter-persistent save
  else
    warn "netfilter-persistent 가 없어 규칙이 재부팅 뒤 사라집니다. iptables-persistent 를 설치한 뒤 저장하세요."
  fi
fi
echo "클라우드 쪽(VCN 보안 목록)에서도 TCP 80·443 인그레스를 열어야 합니다."

log "apt 갱신"
sudo apt-get update -y

# ---------------------------------------------------------------------------
# 4. JDK 25
#
# Ubuntu 24.04 기본 저장소(arm64 포함)에 있다. build.gradle 툴체인·CI(temurin 25)와 맞춘다.
# ---------------------------------------------------------------------------
log "JDK 25 설치"
sudo apt-get install -y openjdk-25-jdk-headless
java -version

# ---------------------------------------------------------------------------
# 5. MySQL 8.0
#
# 버전은 계열(8.0)만 맞춘다. 3306 은 loopback 에만 묶는다.
# ---------------------------------------------------------------------------
log "MySQL 8.0 설치"
sudo apt-get install -y mysql-server
mysql --version
sudo sed -i 's/^bind-address.*/bind-address = 127.0.0.1/' /etc/mysql/mysql.conf.d/mysqld.cnf
# 큰 TEXT 행(diff·로그)을 고칠 때 binlog 에 변하지 않은 BLOB/TEXT 까지 적지 않게 한다(#625).
# 엔티티의 @DynamicUpdate 와 함께 써야 효과가 난다. 시점 복구 여지를 남기려고 binlog 자체는 끄지 않는다.
sudo tee /etc/mysql/mysql.conf.d/qeploy.cnf >/dev/null <<'CNF'
[mysqld]
binlog_format = ROW
binlog_row_image = NOBLOB
CNF
sudo systemctl restart mysql

log "MySQL 스키마/계정 생성"
if [ -z "${DB_PASS:-}" ]; then
  read -rsp "운영 DB 비밀번호를 입력하세요 (dvely 계정): " DB_PASS; echo
fi
sudo mysql <<SQL
CREATE DATABASE IF NOT EXISTS dvely CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'dvely'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON dvely.* TO 'dvely'@'localhost';
FLUSH PRIVILEGES;
SQL
unset DB_PASS
echo "스키마 dvely / 계정 dvely@localhost 준비 완료"

# 보존 스윕·토큰 정리가 READ COMMITTED 로 돈다(#618·#625). RC 는 statement 바이너리 로그와
# 함께 쓸 수 없으므로 ROW 여야 한다. 8.0 기본값이 ROW 지만 확인만 한다.
binlog_format=$(sudo mysql -N -e "select @@global.binlog_format")
if [ "$binlog_format" != "ROW" ]; then
  warn "binlog_format=${binlog_format} — ROW 가 아니면 READ COMMITTED 트랜잭션의 쓰기가 실패합니다."
else
  echo "binlog_format=ROW"
fi

# ---------------------------------------------------------------------------
# 6. Docker
#
# 에이전트 CODE 스텝과 프리뷰가 컨테이너를 띄운다. pm2 보다 먼저 설치하고 그룹을 붙인다.
# pm2 데몬이 docker 그룹 없이 한 번 뜨면 그 아래 앱도 그룹 없이 뜬다(README "그룹 추가만으로는
# 적용되지 않는다"). 맨 끝에서 pm2 데몬을 한 번 더 내린다.
# ---------------------------------------------------------------------------
log "Docker 설치"
sudo apt-get install -y docker.io
sudo systemctl enable --now docker
sudo usermod -aG docker ubuntu
sudo docker pull node:20-alpine

log "도커 정리 cron 등록"
# label!=qeploy.keep=true 를 빼면 코딩 에이전트 이미지가 72시간 뒤 지워진다(#616).
CRON='0 4 * * * docker system prune -af --filter "until=72h" --filter "label!=qeploy.keep=true" >/dev/null 2>&1'
TMP_CRON=$(mktemp)
crontab -l 2>/dev/null | grep -v 'docker system prune' > "$TMP_CRON" || true
echo "$CRON" >> "$TMP_CRON"
crontab "$TMP_CRON"
rm -f "$TMP_CRON"

# ---------------------------------------------------------------------------
# 7. Node + pm2 + 로그 회전
#
# Node 는 pm2 를 돌리기 위한 것이다. 로그 회전을 빠뜨리면 로그 한 파일이 디스크를 채운다
# (dev 에서 3주 반 만에 6.6GB, README 5절).
# ---------------------------------------------------------------------------
log "Node 22 + pm2 설치"
if ! command -v node >/dev/null; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
  sudo apt-get install -y nodejs
fi
sudo npm install -g pm2@7
node -v; pm2 -v

log "pm2-logrotate 설정"
if pm2 ls 2>/dev/null | grep -q 'pm2-logrotate'; then
  echo "이미 설치됨 — 설정만 다시 적용"
else
  pm2 install pm2-logrotate
fi
pm2 set pm2-logrotate:max_size 100M
pm2 set pm2-logrotate:retain 30
pm2 set pm2-logrotate:compress true
pm2 set pm2-logrotate:rotateInterval '0 0 * * *'
pm2 set pm2-logrotate:workerInterval 60
pm2 save

# ---------------------------------------------------------------------------
# 8. nginx (+ TLS)
#
# SSE(에이전트 진행·프리뷰 이벤트)는 버퍼링을 끄고 타임아웃을 길게 둔다. WebSocket 업그레이드도
# 미리 통과시킨다 — 기기 프리뷰 공유가 WebSocket 으로 붙는다. Upgrade 헤더가 없는 요청은
# Connection 을 비워 두어 SSE 의 keep-alive 동작은 예전 설정과 같다.
# ---------------------------------------------------------------------------
log "nginx 설치 및 리버스 프록시 설정"
sudo apt-get install -y nginx
SERVER_NAME="${API_HOST:-_}"
sudo tee /etc/nginx/sites-available/qeploy-api >/dev/null <<NGINX
map \$http_upgrade \$connection_upgrade {
    default upgrade;
    ''      '';
}

server {
    listen 80 default_server;
    server_name ${SERVER_NAME};

    proxy_buffering off;
    proxy_cache off;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade           \$http_upgrade;
        proxy_set_header Connection        \$connection_upgrade;

        proxy_read_timeout    3600s;
        proxy_send_timeout    3600s;
        proxy_connect_timeout 10s;
    }
}
NGINX
sudo rm -f /etc/nginx/sites-enabled/default
sudo ln -sf /etc/nginx/sites-available/qeploy-api /etc/nginx/sites-enabled/qeploy-api
sudo nginx -t
sudo systemctl reload nginx

if [ -n "$API_HOST" ] && [ -n "$CERT_EMAIL" ]; then
  log "TLS 인증서 발급 (${API_HOST})"
  sudo apt-get install -y certbot python3-certbot-nginx
  # DNS 가 아직 이 서버를 가리키지 않거나 보안 목록에서 80 이 닫혀 있으면 실패한다.
  # 그 경우 나머지 셋업은 끝내고, 원인을 고친 뒤 같은 명령만 다시 돌린다.
  if ! sudo certbot --nginx -d "$API_HOST" -m "$CERT_EMAIL" --agree-tos --redirect -n; then
    warn "인증서 발급 실패. DNS A 레코드와 보안 목록 80 번을 확인한 뒤 다시 실행하세요:"
    warn "  sudo certbot --nginx -d ${API_HOST} -m <메일> --agree-tos --redirect -n"
  fi
else
  echo "API_HOST·CERT_EMAIL 이 없어 TLS 는 건너뜀(80 번만 열림)"
fi

# ---------------------------------------------------------------------------
# 9. 배포 디렉터리
#
# 경로와 pm2 앱 이름을 EC2 와 같게 둔다. 그래야 deploy-ec2.yml 을 시크릿만 바꿔 재사용한다.
# ---------------------------------------------------------------------------
log "배포 디렉터리 생성"
sudo mkdir -p /var/www/dvely/backend /var/www/dvely/staging
sudo chown -R ubuntu:ubuntu /var/www/dvely

# docker 그룹 없이 뜬 pm2 데몬을 내린다. 다음 로그인 셸에서 앱을 띄우면 그룹이 붙는다.
pm2 kill >/dev/null 2>&1 || true

log "완료"
cat <<'DONE'

남은 수동 작업
--------------
1) SSH 를 끊었다 다시 접속한다(docker 그룹 반영). 확인: id -nG | tr ' ' '\n' | grep docker

2) 코딩 에이전트 이미지를 이 서버에서 빌드한다(레지스트리에 없다).
     git clone --depth 1 https://github.com/Dvely/Dvely_springboot.git ~/qeploy-src
     docker build -t qeploy/coding-agent:local ~/qeploy-src/docker/coding-agent

3) /var/www/dvely/backend/ecosystem.config.js 를 만든다(deploy/ecosystem.config.js.example).
   API 를 별도 호스트에 두므로 아래 값이 EC2 시절과 다르다(deploy/README.md "Oracle Cloud").
     GITHUB_OAUTH_REDIRECT_URI, GITHUB_APP_INSTALLATION_REDIRECT_URI, QEPLOY_PREVIEW_GATEWAY_BASE_URL
   이어서 pm2 startOrRestart ecosystem.config.js --update-env && pm2 save && pm2 startup

4) GitHub 저장소 Secrets 의 EC2_HOST / EC2_USER / EC2_SSH_KEY 를 이 서버 값으로 바꾼다.

확인
----
   free -h                        스왑 4G
   sudo iptables -L INPUT -n      80·443 ACCEPT 가 REJECT 보다 위
   docker images                  node:20-alpine, qeploy/coding-agent:local
   curl -I localhost              앱이 없으면 502 가 정상
DONE
