#!/usr/bin/env bash
# box-run.sh <action> <arg>
#
# The one path from GitHub Actions to the box, used by every workflow that touches it
# (ansible-migration-plan.md §9). It finds the instance, sends a FIXED command through
# `ssm send-command`, waits for it, and reports.
#
#   converge <sha>    otj-converge <sha>             apply a release (app and box config)
#   check    <sha>    otj-converge <sha> --check     show what would change; change nothing
#   rollback <sha>    otj-converge <sha>, but with the otj-converge taken from <sha>'s image,
#                     so a broken copy on the box can't block the way back (plan R4)
#   restart  <unit>   restart one of hours-api, admin-api, haproxy, then check it's healthy
#
# Arguments come from workflow inputs, so each one is validated here before it goes near a
# command line: a full hex SHA, or a unit from a fixed list. Nothing else reaches the box.
#
# What it prints: the PLAY RECAP, the failed tasks, and for `check` the names of the tasks that
# would change. The FULL output (diffs included) goes to the /otj/converge CloudWatch log group
# instead, because GitHub job logs are readable by any signed-in GitHub user. Ansible never
# handles a secret (plan §7), so neither place can contain one.
set -euo pipefail

REGION=eu-west-2
STACK=OtjServicesStack
LOG_GROUP=/otj/converge
REPO=378849626815.dkr.ecr.eu-west-2.amazonaws.com/otj-hours-api
POLL_SECONDS=10
MAX_POLLS=180 # 30 minutes: a first converge on a blank box installs everything

action="${1:?usage: box-run.sh <converge|check|rollback|restart> <arg>}"
arg="${2:?usage: box-run.sh <converge|check|rollback|restart> <arg>}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

die() { echo "::error::$*" >&2; exit 1; }

case "$action" in
  converge | check | rollback)
    [[ "$arg" =~ ^[0-9a-f]{40}$ ]] || die "'$arg' is not a full 40-character commit SHA"
    ;;
  restart)
    [[ "$arg" =~ ^(hours-api|admin-api|haproxy)$ ]] || die "unknown unit '$arg'"
    ;;
  *) die "unknown action '$action'" ;;
esac

# ── The script the box runs, as root ────────────────────────────────────────────────────────
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

# Sent base64-encoded and piped to bash, so no quoting survives the trip into SSM's JSON and
# whatever shell AWS-RunShellScript uses.
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

# ── Report ───────────────────────────────────────────────────────────────────────────────────
# The agent uploads output as the command runs, but the last events can trail completion by a
# few seconds. By prefix, not by naming stdout and stderr: a stream only exists once something
# was written to it, and naming a missing one fails the whole call.
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
    # A restart, or a failure before Ansible got going (otj-converge itself, the ECR pull): the
    # last lines are the story.
    printf '%s\n' "$output" | tail -n 20
  else
    # Failed tasks, with the TASK line each belongs to.
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

# ── After a successful apply ─────────────────────────────────────────────────────────────────
if [[ "$action" == converge || "$action" == rollback ]]; then
  # What's live, for converge-check's default and for rebuilds (plan §9.1, §11).
  aws ssm put-parameter --region "$REGION" --name /otj/prod/image-tag --type String \
    --value "$arg" --overwrite >/dev/null
  echo "==> /otj/prod/image-tag = $arg"

  # Through Cloudflare, end to end. cf-ray proves it came through the proxy.
  headers=$(curl -sS -D - -o /dev/null --max-time 20 https://otj-services.com/health || true)
  if grep -q '^HTTP/[0-9.]* 200' <<<"$headers" && grep -qi '^cf-ray:' <<<"$headers"; then
    echo "==> https://otj-services.com/health: 200 through Cloudflare"
  else
    die "the box converged, but https://otj-services.com/health didn't answer 200 through Cloudflare"
  fi
fi
