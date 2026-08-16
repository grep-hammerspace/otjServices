# otjServices infra (AWS CDK)

Two stacks:

- **`OtjServicesStack`** — a VPC (`eu-west-2`, single AZ, public subnet only,
  no NAT gateway), a single EC2 instance, and a private ECR repo
  (`otj-hours-api`, immutable SHA-tagged images, lifecycle-capped at 20), for
  the "more permanent than podman-compose" deployment discussed in
  `deployment-migration-plan.html`. Local dev keeps using
  `deploy/podman-compose.yaml` — unrelated to this. The instance is tagged
  `otj:role=app-host` so `GithubOidcStack`'s SSM permissions can scope to it.
- **`GithubOidcStack`** — lets `.github/workflows/ci-cd.yml` deploy
  `OtjServicesStack`, push the app image to ECR, and trigger a deploy on the
  box via SSM, all without any AWS credentials stored in GitHub. Deployed
  once, by hand, before CI can work (see below) — and redeployed by hand any
  time its IAM policy changes, since CI authenticates using the role this
  stack creates and can never be the one to update its own permissions.

## Access model

- **Inbound is 443 only, and only from Cloudflare's published ranges**, reaching
  **Caddy on the host**, never the app directly. Caddy terminates TLS and rate
  limits, then proxies to `127.0.0.1:8945`. Port 80 is not opened: Cloudflare
  terminates the visitor's HTTP at its own edge, and the origin's certificate is
  a Cloudflare Origin CA pair rather than ACME, so there is no HTTP-01 challenge
  to serve. See `deploy/prod/README.md` for the provisioning steps and
  `deploy/prod/Caddyfile` for the config.
  The CIDR list here and the `trusted_proxies` list in the Caddyfile are the same
  set and must be refreshed together — see "Keeping the Cloudflare ranges
  current" in `deploy/prod/README.md`.
- **Shell access** — SSM Session Manager, not SSH. IAM-gated, no open port:
  ```
  aws ssm start-session --target <instance-id> --region eu-west-2
  ```
  (printed as a stack output after deploy)
- **Admin API** — tailnet only, via the host's own `tailscale serve` on 8443.
  Nothing in the security group can reach it. This is load-bearing:
  `AdminIdentityFilter` trusts the `Tailscale-User-Login` header *because* 8946
  is bound to loopback and `tailscale serve` is the only thing that can reach
  it. Read `deploy/README.md` before adding an inbound rule.
- **DNS** — `otj-services.com`, an apex A record pointing at the Elastic IP and
  **proxied (orange cloud)**, so the name resolves to Cloudflare and the origin
  address is never published. It is **not** a CDK resource: the domain is
  registered with Cloudflare Registrar, which mandates Cloudflare's nameservers,
  so there is no Route 53 hosted zone to hold an `ARecord`. Like the MongoDB
  Atlas IP allowlist, it is a manual step that has to be redone by hand if the
  Elastic IP is ever recreated.
  Because the record is proxied, `dig` returns Cloudflare's addresses, not the
  Elastic IP — that is expected, and it means DNS cannot be used to check the
  origin. Read the record content in the Cloudflare dashboard instead.

## Commands

Run from inside this directory, using the `cdk`/`node`/`npm` provided by the
repo-root `nix-shell`. Requires AWS credentials in the environment (e.g.
`aws sso login` / `AWS_PROFILE`) with permission to deploy VPC, EC2, and IAM
resources.

```
npm install
npx cdk bootstrap             # once per AWS account/region

npm run synth                 # cdk synth OtjServicesStack — sanity-check the template
npm run diff                  # cdk diff  OtjServicesStack — compare against what's deployed
npm run deploy                # cdk deploy OtjServicesStack
```

## One-time: wire up CI/CD

`GithubOidcStack` isn't part of the app's ongoing deploys — deploy it once,
by hand, then point the GitHub repo at the role it creates:

```
npx cdk deploy GithubOidcStack   # prints DeployRoleArn in the outputs
```

Then in the GitHub repo, Settings → Secrets and variables → Actions →
Variables → New repository variable:

```
AWS_DEPLOY_ROLE_ARN = <the DeployRoleArn output above>
```

After that, `.github/workflows/ci-cd.yml` runs `mvn test` on every push/PR to
`master`, and on push to `master` (after tests pass) assumes that role via
OIDC and: runs `cdk deploy OtjServicesStack --require-approval never`, builds
the app image and pushes it to ECR tagged with the commit SHA, then triggers
`deploy.sh` on the box via `ssm:SendCommand` and polls for success/failure —
no static AWS keys anywhere in GitHub.

`GithubActionsDeployRole`'s permissions, beyond assuming the CDK bootstrap
roles:

- `ecr:BatchCheckLayerAvailability`, `PutImage`, `InitiateLayerUpload`,
  `UploadLayerPart`, `CompleteLayerUpload` scoped to the `otj-hours-api` repo,
  plus `ecr:GetAuthorizationToken` (`Resource: "*"` — required by the API,
  not a scoping mistake).
- `ssm:SendCommand` on `AWS-RunShellScript` and any EC2 instance, restricted
  via condition to instances tagged `otj:role=app-host`; `ssm:GetCommandInvocation`
  (`Resource: "*"` — this action has no resource-level scoping).

### Rollback

SHA tags in ECR are immutable, so rolling back is re-running the same deploy
trigger against an older SHA — from a laptop with the deploy role's
permissions, or directly on the box via an SSM session as `otjapp`:

```
aws ssm send-command \
  --instance-ids <instance-id> \
  --document-name AWS-RunShellScript \
  --parameters '{"commands":["sudo -u otjapp -i /home/otjapp/otj-deploy/deploy.sh <account>.dkr.ecr.eu-west-2.amazonaws.com/otj-hours-api:<good-sha>"]}' \
  --region eu-west-2
```

No rebuild needed — the lifecycle rule retains the last 20 SHA-tagged images.

If `GithubOidcStack` fails to deploy because an OIDC provider for
`token.actions.githubusercontent.com` already exists in the account (only one
is allowed per URL — likely if another repo already set this up), replace the
`new iam.OpenIdConnectProvider(...)` block in `lib/github-oidc-stack.ts` with
`iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(this, "GithubOidc", "<existing-arn>")`.
