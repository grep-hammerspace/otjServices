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

**Superseded.** The box is no longer provisioned by hand. It's built and deployed by Ansible:
follow `ansible-deploy-checklist.md`, and see `deploy/ansible/README.md` for what runs on the
box. The hand-provisioning steps that used to be here described the box lost on 2026-09-25;
git history has them.

## 7. Verify end-to-end

- [ ] From a tailnet-joined device: `curl https://hours-api.<tailnet>.ts.net:8444/health`
      → 200. **Port 8444, not the bare name**: HAProxy owns 443 on the box
      (`deploy/ansible/README.md`).
- [ ] A real register/log-activity call resolves the correct user in the app
      logs. Identity on the main API is the **bearer token**, not the tailnet
      header — `Tailscale-User-Login` is read by `AdminIdentityFilter` alone,
      i.e. the admin API on 8946. A tailnet-identity check here tests nothing.
- [ ] Admin API reachable and gated. From an allowlisted device:
      ```
      curl -X POST https://hours-api.<tailnet>.ts.net:8443/admin/invites \
        -H 'Content-Type: application/json' -d '{"note":"first code"}'
      ```
      → 201 with a code. From a tailnet device that is *not* on the allowlist,
      the same call → 403. If the second one succeeds, the allowlist is not
      being applied — stop and fix it before minting anything real.
- [ ] `curl https://hours-api.<tailnet>.ts.net:8443/admin/invites` from off the
      tailnet → no route at all (not a 403). The port must not be publicly
      routable; 403 would mean it is reachable and only the app is stopping it.
