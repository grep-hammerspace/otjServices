#!/usr/bin/env bash
# box-run.sh <converge|check|rollback|restart> <arg>: sends a fixed command to the box through SSM
# and reports.
# Arguments come from workflow inputs, so each is validated before it nears a command line. The full
# output goes to CloudWatch, not here: GitHub job logs are readable by any signed-in GitHub user.
set -euo pipefail

REGION=eu-west-2
STACK=OtjServicesStack
LOG_GROUP=/otj/converge
REPO=378849626815.dkr.ecr.eu-west-2.amazonaws.com/otj-hours-api
POLL_SECONDS=10
MAX_POLLS=180

action="${1:?usage: box-run.sh <converge|check|rollback|restart> <arg>}"
arg="${2:?usage: box-run.sh <converge|check|rollback|restart> <arg>}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

die() { echo "::error::$*" >&2; exit 1; }

case "$action" in
  converge | check | rollback)
    [[ "$arg" =~ ^[0-9a-f]{40}$ ]] || die "'$arg' is not a full 40-character commit SHA"
    ;;
  restart)
    [[ "$arg" =~ ^(hours-api|admin-api|haproxy|alloy)$ ]] || die "unknown unit '$arg'"
    ;;
  *) die "unknown action '$action'" ;;
esac

case "$action" in
  converge) remote="/usr/local/sbin/otj-converge $arg" ;;
  check) remote="/usr/local/sbin/otj-converge $arg --check" ;;
  rollback)
    remote=$(cat <<EOF
set -euo pipefail
aws ecr get-login-password --region $REGION | podman login -u AWS --password-stdin ${REPO%%/*} >/dev/null
podman pull --quiet $REPO:$arg >/dev/null
cid=\$(podman create $REPO:$arg)
podman cp "\$cid:/deploy/bin/otj-converge" /tmp/otj-converge-$arg
podman rm "\$cid" >/dev/null
exec bash /tmp/otj-converge-$arg $arg
EOF
)
    ;;
  restart)
    if [[ "$arg" == haproxy ]]; then
      remote="set -euo pipefail; systemctl restart haproxy; sleep 2; systemctl is-active haproxy"
    elif [[ "$arg" == alloy ]]; then
      remote=$(cat <<EOF
set -euo pipefail
systemctl restart alloy
for _ in \$(seq 1 45); do curl -sf http://127.0.0.1:12345/-/ready >/dev/null && { echo "alloy ready"; exit 0; }; sleep 2; done
echo "alloy did not become ready" >&2; exit 1
EOF
)
    else
      port=$([[ "$arg" == hours-api ]] && echo 8945 || echo 8946)
      remote=$(cat <<EOF
set -euo pipefail
uid=\$(id -u otjapp)
sudo -u otjapp env XDG_RUNTIME_DIR=/run/user/\$uid DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/\$uid/bus \
  systemctl --user restart $arg.service
for _ in \$(seq 1 45); do curl -sf http://127.0.0.1:$port/health >/dev/null && { echo "$arg healthy"; exit 0; }; sleep 2; done
echo "$arg did not become healthy" >&2; exit 1
EOF
)
    fi
    ;;
esac

# Base64 and piped to bash, so no quoting has to survive SSM's JSON.
encoded=$(printf '%s\n' "$remote" | base64 -w0)
params=$(jq -nc --arg c "echo $encoded | base64 -d | bash" \
  '{commands: [$c], executionTimeout: ["1800"]}')

instance=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
  --query "Stacks[0].Outputs[?OutputKey=='InstanceId'].OutputValue" --output text)
[[ "$instance" =~ ^i-[0-9a-f]+$ ]] || die "no instance ID in $STACK's outputs"

echo "==> $action $arg on $instance"
command_id=$(aws ssm send-command --region "$REGION" \
  --instance-ids "$instance" \
  --document-name AWS-RunShellScript \
  --comment "box-run $action $arg (${GITHUB_RUN_ID:-local})" \
  --parameters "$params" \
  --cloud-watch-output-config "CloudWatchOutputEnabled=true,CloudWatchLogGroupName=$LOG_GROUP" \
  --query Command.CommandId --output text)
echo "    SSM command $command_id; full output in CloudWatch $LOG_GROUP, streams $command_id/*"

status=Pending
for _ in $(seq 1 "$MAX_POLLS"); do
  status=$(aws ssm get-command-invocation --region "$REGION" \
    --command-id "$command_id" --instance-id "$instance" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$status" in
    Pending | InProgress | Delayed) sleep "$POLL_SECONDS" ;;
    *) break ;;
  esac
done
echo "==> $status"

# By prefix: a stream exists only once written to, and naming a missing one fails the call.
stream="$command_id/$instance/aws-runShellScript"
output=""
for _ in 1 2 3 4 5 6; do
  output=$(aws logs filter-log-events --region "$REGION" --log-group-name "$LOG_GROUP" \
    --log-stream-name-prefix "$stream/" \
    --query 'events[].message' --output json 2>/dev/null | jq -r '.[]' || true)
  if [[ -n "$output" && ( "$status" != Success || "$action" == restart || "$output" == *"PLAY RECAP"* ) ]]; then
    break
  fi
  sleep 5
done

{
  echo "### box-run \`$action $arg\`: $status"
  echo
  echo "Full output: CloudWatch log group \`$LOG_GROUP\`, streams \`$stream/{stdout,stderr}\`."
  echo
  echo '```'
  if [[ "$action" == restart || "$output" != *"PLAY RECAP"* ]]; then
    printf '%s\n' "$output" | tail -n 20
  else
    printf '%s\n' "$output" | awk '/^TASK \[/{t=$0} /^(fatal|failed):/{print t; print}' | cut -c1-300
    if [[ "$action" == check ]]; then
      echo "--- would change:"
      printf '%s\n' "$output" | awk '/^TASK \[/{t=$0} /^changed:/{print t}' | sort -u
    fi
    printf '%s\n' "$output" | sed -n '/^PLAY RECAP/,$p' | head -n 5
  fi
  echo '```'
} | tee -a "$summary"

[[ "$status" == Success ]] || die "$action $arg ended $status"

if [[ "$action" == converge || "$action" == rollback ]]; then
  aws ssm put-parameter --region "$REGION" --name /otj/prod/image-tag --type String \
    --value "$arg" --overwrite >/dev/null
  echo "==> /otj/prod/image-tag = $arg"

  # cf-ray proves it came through Cloudflare.
  headers=$(curl -sS -D - -o /dev/null --max-time 20 https://otj-services.com/health || true)
  if grep -q '^HTTP/[0-9.]* 200' <<<"$headers" && grep -qi '^cf-ray:' <<<"$headers"; then
    echo "==> https://otj-services.com/health: 200 through Cloudflare"
  else
    die "the box converged, but https://otj-services.com/health didn't answer 200 through Cloudflare"
  fi
fi
