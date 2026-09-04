# Qeploy 코딩 에이전트 전용 이미지.
#
# 사용자 본인 API 키(BYOK)로 벤더 공식 CLI 를 헤드리스 실행하기 위한 이미지다. CLI 를 실행할 때마다
# npm 으로 설치하면 매 실행에 수십 초가 붙으므로, 미리 설치한 전용 이미지를 쓴다(운영 결정).
#
# 벤더별로 이미지를 나누지 않고 하나에 두 CLI 를 함께 담는다 — 운영해야 할 이미지가 하나로 줄고,
# 어차피 실행 시 어느 CLI 를 부를지는 어댑터가 정하므로 나눌 이득이 없다.
#
# 빌드:
#   docker build -t qeploy/coding-agent:local docker/coding-agent
#   # 재현성을 위해 운영에서는 버전을 고정한다:
#   docker build --build-arg CLAUDE_CODE_VERSION=<x.y.z> --build-arg CODEX_VERSION=<x.y.z> \
#     -t qeploy/coding-agent:<tag> docker/coding-agent
#
# 이 이미지는 레지스트리에서 pull 하지 않는다 — 러너가 로컬에 있는지 확인하고 없으면 명확히 실패한다.
# 키는 이미지에 절대 굽지 않는다. 실행 시 exec 환경변수로만 주입된다.
FROM node:20-alpine

# git: 코딩 에이전트가 워크스페이스에서 diff/commit 을 다룬다.
# ca-certificates: 공식 API(api.anthropic.com) TLS 검증.
RUN apk add --no-cache git ca-certificates

# 기본값 latest 는 로컬 실험용이다. 운영 이미지는 반드시 --build-arg 로 버전을 고정할 것 —
# 고정하지 않으면 같은 태그가 시점에 따라 다른 CLI 를 담게 되어 재현이 깨진다.
#
# 두 CLI 의 비대화 실행 인자는 애플리케이션 설정(qeploy.coding-agent.claude/codex.argv-prefix)에
# 있다. CLI 버전을 올렸는데 인자 형식이 바뀌면, 여기 핀과 그 설정을 함께 옮긴다.
ARG CLAUDE_CODE_VERSION=latest
ARG CODEX_VERSION=latest
RUN npm install -g \
        @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} \
        @openai/codex@${CODEX_VERSION} \
    && npm cache clean --force

# 워크스페이스는 실행 시 호스트 체크아웃을 bind mount 로 붙인다.
WORKDIR /workspace

# 컨테이너는 유휴 상태로 떠 있고, 실제 CLI 실행은 exec 로 들어온다 — 프리뷰 컨테이너와 같은 방식이며
# 키가 컨테이너 생성 설정(docker inspect)이 아니라 exec 환경에만 머무르게 하는 이유이기도 하다.
CMD ["tail", "-f", "/dev/null"]
