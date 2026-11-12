#!/usr/bin/env bash
#
# OCI Always Free A1 인스턴스를 만든다. 자리가 없으면 날 때까지 재시도한다 (#649)
#
# 무료 ARM(A1) 자리는 홈 리전에 빈 호스트가 있을 때만 잡힌다. 없으면 생성이
# "Out of host capacity" 로 실패하고 아무것도 만들어지지 않는다(요금 없음). 이 스크립트는
# 네트워크(VCN·서브넷·인터넷 게이트웨이·80/443 인그레스)를 준비한 뒤 생성을 반복한다.
# 이미 같은 이름의 인스턴스가 있으면 아무것도 하지 않는다. 몇 번을 다시 돌려도 안전하다.
#
# 준비: OCI CLI(brew install oci-cli)와 ~/.oci/config 의 API 키 프로필
#
#   bash deploy/oci-create-instance.sh
#
# 환경변수(모두 선택)
#   OCI_PROFILE     ~/.oci/config 프로필 (기본 DEFAULT)
#   SSH_PUBKEY      인스턴스에 넣을 공개키 (기본 ~/.ssh/qeploy-oracle.pub)
#   INTERVAL        자리가 없을 때 다음 시도까지 초 (기본 60)
#   MAX_ATTEMPTS    0 이면 무한 (기본 0)

set -euo pipefail

OCI_PROFILE="${OCI_PROFILE:-DEFAULT}"
SSH_PUBKEY="${SSH_PUBKEY:-$HOME/.ssh/qeploy-oracle.pub}"
INTERVAL="${INTERVAL:-60}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-0}"

COMPARTMENT_NAME="qeploy"
INSTANCE_NAME="qeploy-api"
VCN_NAME="qeploy-vcn"
SUBNET_NAME="qeploy-public"
IGW_NAME="qeploy-igw"
SHAPE="VM.Standard.A1.Flex"
OCPUS=2           # Always Free A1 한도(2026-06-15~): 2 OCPU · 12 GB
MEMORY_GB=12
BOOT_GB=100       # Always Free 블록 스토리지는 부트 볼륨 포함 200 GB

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
# 새 API 키는 서비스 노드마다 퍼지는 시간이 달라, 등록 직후 몇 분 동안은 같은 키로 어떤 호출은
# 통과하고 어떤 호출은 401 NotAuthenticated 가 난다(2026-11-12 실측: VCN 생성은 되고 바로 다음
# 인터넷 게이트웨이 생성이 401). 401 이면 20초 쉬고 같은 호출을 다시 한다(최대 10번).
oci() {
  local n=0 rc errf outf
  errf=$(mktemp); outf=$(mktemp)
  while :; do
    if command oci --profile "$OCI_PROFILE" "$@" >"$outf" 2>"$errf"; then rc=0; else rc=$?; fi
    if [ "$rc" -ne 0 ] && grep -q NotAuthenticated "$errf" && [ "$n" -lt 10 ]; then
      n=$((n + 1)); log "인증 전파 대기 ($n/10)" >&2; sleep 20; continue
    fi
    cat "$outf"; cat "$errf" >&2; rm -f "$errf" "$outf"; return "$rc"
  done
}

[ -f "$SSH_PUBKEY" ] || { echo "SSH 공개키가 없습니다: $SSH_PUBKEY" >&2; exit 1; }

TENANCY_ID=$(awk -v p="[$OCI_PROFILE]" '$0==p{f=1;next} /^\[/{f=0} f && /^tenancy=/{sub(/^tenancy=/,"");print;exit}' "$HOME/.oci/config")
[ -n "$TENANCY_ID" ] || { echo "~/.oci/config 의 [$OCI_PROFILE] 에 tenancy 가 없습니다." >&2; exit 1; }

# --- 컴파트먼트 -------------------------------------------------------------
COMPARTMENT_ID=$(oci iam compartment list --compartment-id "$TENANCY_ID" --all \
  --query "data[?name=='$COMPARTMENT_NAME' && \"lifecycle-state\"=='ACTIVE'] | [0].id" --raw-output 2>/dev/null || true)
if [ -z "$COMPARTMENT_ID" ] || [ "$COMPARTMENT_ID" = "null" ]; then
  log "컴파트먼트 $COMPARTMENT_NAME 생성"
  COMPARTMENT_ID=$(oci iam compartment create --compartment-id "$TENANCY_ID" --name "$COMPARTMENT_NAME" \
    --description "Qeploy backend" --wait-for-state ACTIVE --query 'data.id' --raw-output)
fi
log "컴파트먼트 $COMPARTMENT_ID"

# --- 이미 있으면 끝 ---------------------------------------------------------
existing=$(oci compute instance list --compartment-id "$COMPARTMENT_ID" --display-name "$INSTANCE_NAME" \
  --query "data[?\"lifecycle-state\"!='TERMINATED' && \"lifecycle-state\"!='TERMINATING'] | [0].id" --raw-output 2>/dev/null || true)
if [ -n "$existing" ] && [ "$existing" != "null" ]; then
  log "이미 있음: $existing"
  oci compute instance list-vnics --instance-id "$existing" --query 'data[0]."public-ip"' --raw-output
  exit 0
fi

# --- 네트워크 ---------------------------------------------------------------
VCN_ID=$(oci network vcn list --compartment-id "$COMPARTMENT_ID" --display-name "$VCN_NAME" \
  --query 'data[0].id' --raw-output 2>/dev/null || true)
if [ -z "$VCN_ID" ] || [ "$VCN_ID" = "null" ]; then
  log "VCN 생성"
  VCN_ID=$(oci network vcn create --compartment-id "$COMPARTMENT_ID" --display-name "$VCN_NAME" \
    --cidr-blocks '["10.0.0.0/16"]' --dns-label qeploy --wait-for-state AVAILABLE --query 'data.id' --raw-output)
fi

IGW_ID=$(oci network internet-gateway list --compartment-id "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
  --query 'data[0].id' --raw-output 2>/dev/null || true)
if [ -z "$IGW_ID" ] || [ "$IGW_ID" = "null" ]; then
  log "인터넷 게이트웨이 생성"
  IGW_ID=$(oci network internet-gateway create --compartment-id "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
    --display-name "$IGW_NAME" --is-enabled true --wait-for-state AVAILABLE --query 'data.id' --raw-output)
fi

RT_ID=$(oci network vcn get --vcn-id "$VCN_ID" --query 'data."default-route-table-id"' --raw-output)
oci network route-table update --rt-id "$RT_ID" --force \
  --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$IGW_ID\"}]" >/dev/null

# 기본 보안 목록에는 22 번만 있다. 22·80·443 과 ICMP(경로 MTU 탐색)를 둔다.
SL_ID=$(oci network vcn get --vcn-id "$VCN_ID" --query 'data."default-security-list-id"' --raw-output)
oci network security-list update --security-list-id "$SL_ID" --force --ingress-security-rules '[
  {"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":22,"max":22}}},
  {"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":80,"max":80}}},
  {"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":443,"max":443}}},
  {"protocol":"1","source":"0.0.0.0/0","icmpOptions":{"type":3,"code":4}},
  {"protocol":"1","source":"10.0.0.0/16","icmpOptions":{"type":3}}
]' >/dev/null

SUBNET_ID=$(oci network subnet list --compartment-id "$COMPARTMENT_ID" --vcn-id "$VCN_ID" --display-name "$SUBNET_NAME" \
  --query 'data[0].id' --raw-output 2>/dev/null || true)
if [ -z "$SUBNET_ID" ] || [ "$SUBNET_ID" = "null" ]; then
  log "공용 서브넷 생성"
  SUBNET_ID=$(oci network subnet create --compartment-id "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
    --display-name "$SUBNET_NAME" --cidr-block 10.0.0.0/24 --dns-label pub \
    --route-table-id "$RT_ID" --security-list-ids "[\"$SL_ID\"]" \
    --wait-for-state AVAILABLE --query 'data.id' --raw-output)
fi
log "네트워크 준비 완료 (subnet $SUBNET_ID)"

# --- 이미지 · 가용 도메인 ---------------------------------------------------
IMAGE_ID=$(oci compute image list --compartment-id "$TENANCY_ID" \
  --operating-system "Canonical Ubuntu" --operating-system-version "24.04" --shape "$SHAPE" \
  --sort-by TIMECREATED --sort-order DESC --limit 1 --query 'data[0].id' --raw-output)
[ -n "$IMAGE_ID" ] && [ "$IMAGE_ID" != "null" ] || { echo "Ubuntu 24.04 aarch64 이미지를 찾지 못했습니다." >&2; exit 1; }
log "이미지 $IMAGE_ID"

ADS=$(oci iam availability-domain list --compartment-id "$TENANCY_ID" --query 'data[].name' --raw-output \
  | tr -d '[]",' | awk 'NF{print $1}')

# --- 생성 반복 ----------------------------------------------------------------
attempt=0
backoff="$INTERVAL"
# Oracle 의 안내는 셰이프·폴트 도메인·가용 도메인을 바꿔 보라는 것이다. 도쿄는 가용 도메인이
# 하나라 폴트 도메인 셋을 돌아가며 지정한다. --no-retry: CLI 기본 재시도가 500(자리 없음)을
# 2분 가까이 다시 보내므로 끄고, 간격은 이 루프가 정한다.
FDS="FAULT-DOMAIN-1 FAULT-DOMAIN-2 FAULT-DOMAIN-3"
while :; do
  for ad in $ADS; do
   for fd in $FDS; do
    attempt=$((attempt + 1))
    log "시도 #$attempt ($ad / $fd)"
    if out=$(oci compute instance launch --no-retry \
        --compartment-id "$COMPARTMENT_ID" --availability-domain "$ad" --fault-domain "$fd" \
        --display-name "$INSTANCE_NAME" --shape "$SHAPE" \
        --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORY_GB}" \
        --image-id "$IMAGE_ID" --boot-volume-size-in-gbs "$BOOT_GB" \
        --subnet-id "$SUBNET_ID" --assign-public-ip true \
        --ssh-authorized-keys-file "$SSH_PUBKEY" \
        --wait-for-state RUNNING --query 'data.id' --raw-output 2>&1); then
      instance_id=$(printf '%s\n' "$out" | grep -o 'ocid1\.instance[^" ]*' | tail -1)
      ip=$(oci compute instance list-vnics --instance-id "$instance_id" --query 'data[0]."public-ip"' --raw-output)
      log "생성 완료: $instance_id"
      echo "PUBLIC_IP=$ip"
      exit 0
    fi
    reason=$(printf '%s\n' "$out" | grep -E '"(code|message)"' | tr -d '\n' | tr -s ' ')
    case "$out" in
      *"Out of host capacity"*|*"OutOfHostCapacity"*|*"out of capacity"*)
        log "자리 없음"; backoff="$INTERVAL" ;;
      *"TooManyRequests"*|*'"status": 429'*)
        # 무료 계정은 생성 호출 한도가 낮다. 다음 폴트 도메인으로 넘어가기 전에 쉰다.
        backoff=$(( backoff * 2 > 600 ? 600 : backoff * 2 ))
        log "요청 제한 — ${backoff}s 대기 (${reason})"; sleep "$backoff" ;;
      *)
        echo "$out" >&2; log "예상하지 못한 오류라 멈춥니다."; exit 1 ;;
    esac
    if [ "$MAX_ATTEMPTS" -gt 0 ] && [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
      log "최대 시도 횟수 도달"; exit 2
    fi
    sleep 20
   done
  done
  sleep $(( backoff + RANDOM % 15 ))
done
