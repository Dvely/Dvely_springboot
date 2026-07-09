#!/usr/bin/env bash
#
# /usr/local/sbin/dvely-deploy 로 설치한다. (root:root, 0755)
#
# 배포 계정에 sudo를 줄 때 `install`이나 `cp` 를 직접 허용하면 인자에 제한이 없어
# 사실상 무제한 root 권한이 된다. (예: sudo install -m 4755 /tmp/sh /usr/local/bin/rootsh)
# 그래서 인자를 받지 않는 이 스크립트 하나만 NOPASSWD로 허용한다.
#
# 이 스크립트는 root로 돌기 때문에 입력 경로를 신뢰해서는 안 된다.
# STAGING_DIR을 /tmp 아래 두면 안 되는 이유가 여기에 있다. /tmp는 누구나 쓸 수 있어서
# 권한 낮은 로컬 계정이 디렉터리를 미리 만들어 소유권을 가로챌 수 있고, 그러면 jar를
# 바꿔치기하거나 심볼릭 링크를 심어 root가 엉뚱한 파일을 읽게 만들 수 있다.

set -euo pipefail

APP_NAME=dvely
REMOTE_DIR=/opt/dvely
STAGING_DIR=/var/lib/dvely/staging
STAGED="${STAGING_DIR}/app.jar"
CHECKSUM="${STAGING_DIR}/app.jar.sha256"

# 1. staging 디렉터리는 반드시 root 소유의 실제 디렉터리여야 한다.
#    남이 선점해 만든 디렉터리라면 그 안의 무엇도 믿을 수 없다.
if [ -L "$STAGING_DIR" ] || [ ! -d "$STAGING_DIR" ]; then
    echo "staging 디렉터리가 없거나 심볼릭 링크입니다: $STAGING_DIR" >&2
    exit 1
fi
if [ "$(stat -c '%U' "$STAGING_DIR")" != "root" ]; then
    echo "staging 디렉터리가 root 소유가 아닙니다: $STAGING_DIR" >&2
    exit 1
fi

# 2. root가 심볼릭 링크를 따라가지 않도록 정규 파일인지 확인한다.
#    install 은 소스 링크를 따라가므로, 이 검사가 없으면 /etc/shadow 같은 파일이
#    그대로 복사되어 나갈 수 있다.
for f in "$STAGED" "$CHECKSUM"; do
    if [ -L "$f" ] || [ ! -f "$f" ]; then
        echo "정상적인 파일이 아닙니다: $f" >&2
        exit 1
    fi
done
if [ ! -s "$STAGED" ]; then
    echo "배포할 jar가 비어 있습니다: $STAGED" >&2
    exit 1
fi

# 3. 업로드된 jar가 빌드 산출물 그대로인지 대조한다.
if ! (cd "$STAGING_DIR" && sha256sum -c --status app.jar.sha256); then
    echo "jar 체크섬이 일치하지 않습니다. 배포를 중단합니다." >&2
    exit 1
fi

# 롤백할 수 있도록 직전 jar를 남긴다.
if [ -f "${REMOTE_DIR}/app.jar" ]; then
    cp "${REMOTE_DIR}/app.jar" "${REMOTE_DIR}/app.jar.prev"
fi

# 0640 dvely:dvely. 배포 계정조차 읽을 수 없게 해 두면, 만에 하나 소스 경로가
# 바꿔치기되더라도 root가 읽은 내용이 배포 계정에게 노출되지 않는다.
install -m 0640 -o "$APP_NAME" -g "$APP_NAME" "$STAGED" "${REMOTE_DIR}/app.jar"
rm -f "$STAGED" "$CHECKSUM"

systemctl restart "$APP_NAME"
