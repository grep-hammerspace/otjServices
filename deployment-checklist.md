# Deployment checklist — VPC + EC2, via CDK

Path from where things stand today to a running app reachable over the
tailnet. Steps 1–4 are built and tested (`aws/`, `.github/workflows/ci-cd.yml`).
Steps 5–6 are still manual — nothing here automates Atlas setup or the box's
Podman/Tailscale/Quadlet provisioning yet.

## 1. AWS credentials (one-time)

- [ ] Create an IAM user with `AdministratorAccess`, generate a CLI access key
      (not root account keys)
- [ ] `nix-shell` (repo root) → `aws configure` → verify with
      `aws sts get-caller-identity`

## 2. Bootstrap CDK + deploy the OIDC role (one-time)

```
cd aws
npm install
npx cdk bootstrap
npx cdk deploy GithubOidcStack
```

- [ ] Copy the `DeployRoleArn` output

**Redeploy `GithubOidcStack` by hand (`npx cdk deploy GithubOidcStack`) any time
`aws/lib/github-oidc-stack.ts` changes** — CI authenticates *using* the role this
stack creates, so it can never be the one to update its own permissions. Required
once now, since this stack was just extended with ECR push and SSM SendCommand
permissions for step 6 below.

## 3. Wire up GitHub Actions (one-time)

- [ ] GitHub repo → Settings → Secrets and variables → Actions → Variables →
      **New repository variable**: `AWS_DEPLOY_ROLE_ARN` = the ARN from step 2

## 4. Deploy the VPC + EC2 box

Either:

```
npx cdk deploy OtjServicesStack
```

…or push to `master` and let `.github/workflows/ci-cd.yml` do it (runs tests
first, then deploys).

- [ ] Note the `InstanceId`, `ElasticIp`, and `SsmConnectCommand` outputs

## 5. MongoDB Atlas (not part of the CDK stack — still fully manual)

- [ ] Create a free M0 cluster, a DB user scoped to `otjdb`, get the SRV
      connection string
- [ ] Add the EC2 box's Elastic IP (from step 4) to the Atlas IP access list

## 6. Provision the box itself (manual — SSM, no SSH)

```
aws ssm start-session --target <instance-id> --region eu-west-2
```

On the box:

- [ ] `curl -fsSL https://tailscale.com/install.sh | sh` then
      `sudo tailscale up --hostname=hours-api`
- [ ] One-time: `sudo tailscale set --operator=$USER` (lets `tailscale serve`
      run without sudo)
- [ ] Install Podman (`apt install podman` on Ubuntu 24.04)
- [ ] Install AWS CLI v2 (needed for `aws ecr get-login-password` — not covered
      by `apt`'s stale v1):
      ```
      curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
      unzip awscliv2.zip && sudo ./aws/install
      ```
      No credentials to configure — it picks up the instance role automatically.
- [ ] Create the `otjapp` user and enable lingering so `systemd --user` survives
      logout/reboot:
      ```
      sudo useradd -m -s /bin/bash otjapp
      sudo loginctl enable-linger otjapp
      ```
- [ ] As `otjapp`, create `~/otj-deploy/` and paste in the contents of
      `deploy/prod/deploy.sh` and `deploy/prod/hours-api.container.template`
      from this repo, then `chmod +x ~/otj-deploy/deploy.sh`
- [ ] As `otjapp`, create `~/otj-hours-api.env` (`chmod 600`) holding
      `MONGO_URI=`, `ANTHROPIC_API_KEY=`, `PASSWORD_ENCRYPTION_KEY=`
- [ ] `tailscale serve --bg --https=443 http://127.0.0.1:8945`

Once this is done, merges to `master` build the app image, push it to ECR
tagged with the commit SHA, and run `deploy.sh` on the box via SSM
automatically (see `.github/workflows/ci-cd.yml`) — no further manual steps
for ordinary deploys. To roll back, re-run `deploy.sh` on the box (or via
`aws ssm send-command`) pointed at an older SHA tag; ECR retains the last 20.

## 7. Verify end-to-end

- [ ] From a tailnet-joined device: `curl https://hours-api.<tailnet>.ts.net/health`
      → 200
- [ ] A real register/log-activity call resolves the correct tailnet identity
      in the app logs
