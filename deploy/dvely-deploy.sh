#!/usr/bin/env bash
#
# /usr/local/sbin/dvely-deploy 로 설치한다. (root:root, 0755)
#
# 배포 계정에 sudo를 줄 때 `install`이나 `cp` 를 직접 허용하면 인자에 제한이 없어
# 사실상 무제한 root 권한이 된다. (예: sudo install -m 4755 /tmp/sh /usr/local/bin/rootsh)
# 그래서 인자를 받지 않는 이 스크립트 하나만 NOPASSWD로 허용한다.
#
# 이 스크립트가 신뢰하는 입력은 STAGED 경로의 jar 하나뿐이며, 그건 이미
# 배포 계정이 자기 권한으로 올려놓은 파일이다. 권한이 새로 늘어나지 않는다.

set -euo pipefail

APP_NAME=dvely
REMOTE_DIR=/opt/dvely
STAGED="/tmp/${APP_NAME}-deploy/app.jar"

if [ ! -s "$STAGED" ]; then
    echo "배포할 jar가 없습니다: $STAGED" >&2
    exit 1
fi

# 롤백할 수 있도록 직전 jar를 남긴다.
if [ -f "${REMOTE_DIR}/app.jar" ]; then
    cp "${REMOTE_DIR}/app.jar" "${REMOTE_DIR}/app.jar.prev"
fi

install -m 0644 -o "$APP_NAME" -g "$APP_NAME" "$STAGED" "${REMOTE_DIR}/app.jar"
rm -f "$STAGED"

systemctl restart "$APP_NAME"
