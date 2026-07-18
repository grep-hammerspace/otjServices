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
- [ ] Set up the Quadlet unit
      (`~/.config/containers/systemd/hours-api.container`) and the
      `EnvironmentFile` holding `MONGO_URI`, `ANTHROPIC_API_KEY`,
      `PASSWORD_ENCRYPTION_KEY` — per `deployment-migration-plan.html` step 06,
      not yet written as code
- [ ] `tailscale serve --bg --https=443 http://127.0.0.1:8945`

## 7. Verify end-to-end

- [ ] From a tailnet-joined device: `curl https://hours-api.<tailnet>.ts.net/health`
      → 200
- [ ] A real register/log-activity call resolves the correct tailnet identity
      in the app logs
