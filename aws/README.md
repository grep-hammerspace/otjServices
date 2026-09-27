# otjServices infra (AWS CDK)

Two stacks:

- **`OtjServicesStack`** — a VPC (`eu-west-2`, single AZ, public subnet only,
  no NAT gateway), a single EC2 instance, and a private ECR repo
  (`otj-hours-api`, immutable SHA-tagged images, lifecycle-capped at 20), for
  the "more permanent than podman-compose" deployment. Local dev keeps using
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
  **HAProxy on the host**, never the app directly. HAProxy terminates TLS and rate
  limits, then proxies to `127.0.0.1:8945`. Port 80 is not opened: Cloudflare
  terminates the visitor's HTTP at its own edge, and the origin's certificate is
  a Cloudflare Origin CA pair rather than ACME, so there is no HTTP-01 challenge
  to serve. See `deploy/ansible/README.md` for the box and
  `deploy/haproxy/haproxy.cfg` for the config.
  The CIDR list here and `deploy/haproxy/cloudflare-ips.lst` are the same set and
  must be refreshed together — see "Keeping the Cloudflare ranges current" in
  `deploy/ansible/README.md`.
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

## The instance must never be replaced by accident

On 2026-09-25 a routine merge replaced the EC2 instance and terminated the old one, root volume
and all, because the stack resolved Canonical's *current* Ubuntu AMI on every deploy and Canonical
had published a new one. Two things now stop that:

1. **The AMI is pinned** in `lib/otj-services-stack.ts`. A newer AMI is a deliberate PR.
2. **A stack policy** (`stack-policy.json`) denies `Update:Replace` and `Update:Delete` on the
   instance and the Elastic IP. Any update that would replace either one fails and rolls back,
   whatever caused it (an AMI, a block device, a security group swap, a subnet change). Nothing
   else is affected. Set it once, from a laptop; it persists across deploys:

   ```
   aws cloudformation set-stack-policy --stack-name OtjServicesStack --region eu-west-2 \
     --stack-policy-body file://stack-policy.json
   aws cloudformation get-stack-policy --stack-name OtjServicesStack --region eu-west-2
   ```

   `cdk deploy` does not manage stack policies, so this file is not applied by CI and a change
   to it has to be re-run by hand.

**When you do want a new instance** (a new AMI, a bigger disk), relax the policy for the
instance only, deploy that one change from a laptop, and put the policy back. The Elastic IP
stays denied, so it's carried over rather than recreated, and the Atlas allowlist and Cloudflare
record stay valid:

```
npx cdk diff OtjServicesStack          # the instance should be the ONLY replacement
aws cloudformation set-stack-policy --stack-name OtjServicesStack --region eu-west-2 \
  --stack-policy-body '{"Statement":[{"Effect":"Deny","Action":["Update:Replace","Update:Delete"],"Principal":"*","Resource":"LogicalResourceId/InstanceEip"},{"Effect":"Allow","Action":"Update:*","Principal":"*","Resource":"*"}]}'
npx cdk deploy OtjServicesStack
aws cloudformation set-stack-policy --stack-name OtjServicesStack --region eu-west-2 \
  --stack-policy-body file://stack-policy.json
```

Put the policy back even if the deploy fails. The replacement comes up as a blank Ubuntu box,
which is expected, so whatever the box needs has to be reproducible from the repo first.

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
the app image and pushes it to ECR tagged with the commit SHA — no static AWS
keys anywhere in GitHub. It no longer rolls the image out to the box: that went
with the instance replacement above, and comes back with the Ansible deploy
(`ansible-migration-plan.md`).

`GithubActionsDeployRole`'s permissions, beyond assuming the CDK bootstrap
roles:

- `ecr:BatchCheckLayerAvailability`, `PutImage`, `InitiateLayerUpload`,
  `UploadLayerPart`, `CompleteLayerUpload` scoped to the `otj-hours-api` repo,
  plus `ecr:GetAuthorizationToken` (`Resource: "*"` — required by the API,
  not a scoping mistake).
- `ssm:SendCommand` on `AWS-RunShellScript` and any EC2 instance, restricted
  via condition to instances tagged `otj:role=app-host`; `ssm:GetCommandInvocation`
  (`Resource: "*"` — this action has no resource-level scoping).
- `logs:GetLogEvents` / `FilterLogEvents` on `/otj/converge` only, where the box
  writes each converge's Ansible output.
- `ssm:PutParameter` / `GetParameter` on `/otj/prod/image-tag` only (the live
  SHA). The secrets under `/otj/prod/` are not readable by CI.
- `cloudformation:DescribeStacks` on `OtjServicesStack`, so the ops workflows can
  find the instance ID.

It trusts two OIDC subjects: `ref:refs/heads/master` (pushes, and workflows
dispatched from master) and `environment:production`. GitHub sends the second
**instead of** the first for any job that names an environment, so `deploy.yml`
needs it. The `production` environment only accepts deployments from `master`.

The **instance role** (in `OtjServicesStack`, deployed by CI) can read
`/otj/prod/*` from Parameter Store, and decrypt only through Parameter Store
(`kms:ViaService`), so `otj-render-env` can fetch secrets at unit start. It can
also write the `/otj/converge` log group.

### Rollback

There is nothing on the box to roll back until the Ansible deploy lands. Its `rollback`
workflow re-applies an older SHA (`ansible-migration-plan.md` §9.2). SHA tags in ECR are
immutable and the lifecycle rule keeps the last 20, so any recent image can be redeployed without
a rebuild.

If `GithubOidcStack` fails to deploy because an OIDC provider for
`token.actions.githubusercontent.com` already exists in the account (only one
is allowed per URL — likely if another repo already set this up), replace the
`new iam.OpenIdConnectProvider(...)` block in `lib/github-oidc-stack.ts` with
`iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(this, "GithubOidc", "<existing-arn>")`.
