#!/usr/bin/env bash
# Source this from the repo root; do not execute it.
#
#     export AWS_REGION=eu-west-2
#     INSTANCE_ID=$(aws cloudformation describe-stacks --stack-name OtjServicesStack \
#       --query "Stacks[0].Outputs[?OutputKey=='InstanceId'].OutputValue" --output text)
#     source deploy/prod/push-file.sh
#
# SSM Session Manager gives you a shell on the box but no scp or sftp, so there is no ordinary
# way to copy a file there. These helpers send a command that reconstructs the file on the far
# side.
#
# The content is base64'd rather than sent as text because it has to survive JSON encoding *and*
# two layers of shell quoting on the way through ssm:SendCommand. deploy.sh alone contains
# $HOME, ${IMAGE_URI} and "${1:?usage: ...}", every one of which would be expanded or mangled in
# transit. Base64 output is [A-Za-z0-9+/=] only, so nothing in it can be interpreted by anything.
#
# This is not fastidiousness. The box's hours-api.container.template was originally installed by
# pasting it into a terminal, and whatever rendered the source padded all 20 lines with trailing
# whitespace: 389 bytes in the repo, 2116 on the box, same 20 lines. systemd strips trailing
# whitespace from unit-file values so it never broke — but nothing would ever have told you the
# two differed. `verify_files` below is the check that would have. See issue #40.

# push_file <local-path> <remote-path> [owner] [mode]
#
# Prints the SSM command id. Note that send-command returns as soon as the command is *queued*,
# so the id is not evidence that anything worked — pass it to ssm_wait.
push_file() {
    local b64 cmd
    if [ -z "${INSTANCE_ID:-}" ]; then
        echo "INSTANCE_ID is not set" >&2
        return 1
    fi
    b64=$(base64 -w0 "$1") || return 1   # GNU coreutils; on macOS use: base64 | tr -d '\n'
    cmd="echo $b64 | base64 -d | sudo -u ${3:-otjapp} tee $2 >/dev/null"
    [ -n "${4:-}" ] && cmd="$cmd && sudo chmod $4 $2"
    aws ssm send-command --instance-ids "$INSTANCE_ID" \
        --document-name AWS-RunShellScript \
        --parameters "commands=[\"$cmd\"]" \
        --query Command.CommandId --output text
}

# ssm_wait <command-id>
#
# Polls until the command finishes, then prints its stdout (or its stderr, and fails, if it did).
ssm_wait() {
    local cid="$1" status
    for _ in $(seq 1 30); do
        status=$(aws ssm get-command-invocation --command-id "$cid" \
            --instance-id "$INSTANCE_ID" --query Status --output text 2>/dev/null || echo Pending)
        case "$status" in
            Success)
                aws ssm get-command-invocation --command-id "$cid" \
                    --instance-id "$INSTANCE_ID" --query StandardOutputContent --output text
                return 0 ;;
            Failed|Cancelled|TimedOut)
                echo "command $cid: $status" >&2
                aws ssm get-command-invocation --command-id "$cid" \
                    --instance-id "$INSTANCE_ID" --query StandardErrorContent --output text >&2
                return 1 ;;
        esac
        sleep 2
    done
    echo "timed out waiting for $cid" >&2
    return 1
}

# ssm_run <shell-command>
#
# Send one command and wait for its output. For read-only checks on the box.
ssm_run() {
    local cid
    cid=$(aws ssm send-command --instance-ids "$INSTANCE_ID" \
        --document-name AWS-RunShellScript \
        --parameters "commands=[\"$1\"]" \
        --query Command.CommandId --output text) || return 1
    ssm_wait "$cid"
}

# verify_files [repo-dir]
#
# Compares the three box-side deploy files against this checkout, by checksum. Run it after any
# push_file and before merging to master — "I copied it" and "it is identical" are different
# claims, and only the second one is worth anything.
verify_files() {
    local dir="${1:-deploy/prod}" remote f lh rh rc=0
    remote=$(ssm_run "cd /home/otjapp/otj-deploy && md5sum deploy.sh hours-api.container.template admin-api.container.template") || return 1
    for f in deploy.sh hours-api.container.template admin-api.container.template; do
        lh=$(md5sum "$dir/$f" | cut -d' ' -f1)
        rh=$(printf '%s\n' "$remote" | awk -v n="$f" '$2 == n { print $1 }')
        if [ -z "$rh" ]; then
            printf '  MISSING %s  (not on the box)\n' "$f"; rc=1
        elif [ "$lh" = "$rh" ]; then
            printf '  MATCH   %s\n' "$f"
        else
            printf '  DIFFERS %s  (repo %s…, box %s…)\n' "$f" "${lh:0:8}" "${rh:0:8}"; rc=1
        fi
    done
    return $rc
}
