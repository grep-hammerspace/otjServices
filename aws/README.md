# otjServices infra (AWS CDK)

Two stacks:

- **`OtjServicesStack`** — a VPC (`eu-west-2`, single AZ, public subnet only,
  no NAT gateway) and a single EC2 instance, for the "more permanent than
  podman-compose" deployment discussed in `deployment-migration-plan.html`.
  Local dev keeps using `deploy/podman-compose.yaml` — unrelated to this.
- **`GithubOidcStack`** — lets `.github/workflows/ci-cd.yml` deploy
  `OtjServicesStack` without any AWS credentials stored in GitHub. Deployed
  once, by hand, before CI can work (see below).

## Access model

- **No inbound rules** on the instance's security group at all.
- **Admin access** — SSM Session Manager, not SSH. IAM-gated, no open port:
  ```
  aws ssm start-session --target <instance-id> --region eu-west-2
  ```
  (printed as a stack output after deploy)
- **App access** — the host's own `tailscale serve`, once Tailscale is
  installed and podman/Quadlets are set up on the box (manual, see
  `deployment-migration-plan.html` steps 05–07). Same trust model as the
  current local setup: nothing is listening on the public interface.

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
OIDC and runs `cdk deploy OtjServicesStack --require-approval never` — no
static AWS keys anywhere in GitHub.

If `GithubOidcStack` fails to deploy because an OIDC provider for
`token.actions.githubusercontent.com` already exists in the account (only one
is allowed per URL — likely if another repo already set this up), replace the
`new iam.OpenIdConnectProvider(...)` block in `lib/github-oidc-stack.ts` with
`iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(this, "GithubOidc", "<existing-arn>")`.
