import * as cdk from "aws-cdk-lib";
import { Construct } from "constructs";
import * as iam from "aws-cdk-lib/aws-iam";

const GITHUB_REPO = "grep-hammerspace/otjServices";
const CDK_QUALIFIER = "hnb659fds"; // default `cdk bootstrap` qualifier
const DEPLOY_REGION = "eu-west-2";
const ECR_REPOSITORY_NAME = "otj-hours-api"; // keep in sync with otj-services-stack.ts
const EC2_TAG_KEY = "otj:role";
const EC2_TAG_VALUE = "app-host"; // keep in sync with otj-services-stack.ts

/**
 * One-time, deployed by hand (not by CI — CI needs this role to already exist
 * before it can authenticate). Sets up federated trust so GitHub Actions can
 * assume a role scoped to the CDK bootstrap's deploy/lookup roles, plus direct
 * ECR push and SSM SendCommand permissions for publishing/deploying the app
 * image; no long-lived AWS credentials are stored in GitHub. See aws/README.md.
 */
export class GithubOidcStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // If a GitHub OIDC provider already exists in this account (only one is
    // allowed per URL), delete this block and import it instead:
    //   iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(this, "GithubOidc", "<arn>")
    const githubOidcProvider = new iam.OpenIdConnectProvider(this, "GithubOidc", {
      url: "https://token.actions.githubusercontent.com",
      clientIds: ["sts.amazonaws.com"],
    });

    const deployRole = new iam.Role(this, "GithubActionsDeployRole", {
      roleName: "otjServices-github-actions-deploy",
      description: "Assumed by GitHub Actions (OIDC) to deploy the otjServices CDK stack",
      assumedBy: new iam.FederatedPrincipal(
        githubOidcProvider.openIdConnectProviderArn,
        {
          StringEquals: {
            "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          },
          // Restrict to the workflow run on pushes to master — no other branch,
          // PR, or repo can assume this role.
          StringLike: {
            "token.actions.githubusercontent.com:sub": `repo:${GITHUB_REPO}:ref:refs/heads/master`,
          },
        },
        "sts:AssumeRoleWithWebIdentity",
      ),
      maxSessionDuration: cdk.Duration.hours(1),
    });

    // Assume the roles `cdk bootstrap` already created — those carry exactly
    // what `cdk deploy`/`cdk diff` need. file-publishing-role is required to
    // upload the synthesized template to the bootstrap S3 bucket; easy to miss
    // since a locally-run `cdk deploy` with AdministratorAccess creds silently
    // falls back to direct bucket access when it can't assume this role, so the
    // gap only surfaces once CI (with no such fallback) tries it. (Direct
    // ECR/SSM permissions for image publishing and box deploys are added
    // separately below.)
    const account = cdk.Stack.of(this).account;
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        actions: ["sts:AssumeRole"],
        resources: [
          `arn:aws:iam::${account}:role/cdk-${CDK_QUALIFIER}-deploy-role-${account}-${DEPLOY_REGION}`,
          `arn:aws:iam::${account}:role/cdk-${CDK_QUALIFIER}-lookup-role-${account}-${DEPLOY_REGION}`,
          `arn:aws:iam::${account}:role/cdk-${CDK_QUALIFIER}-file-publishing-role-${account}-${DEPLOY_REGION}`,
        ],
      }),
    );

    // Push access for the app image CI builds on every merge to master. Direct
    // permissions, not an assumed role — the CDK bootstrap roles above only cover
    // CloudFormation deploys, not arbitrary `docker push` API calls. Deterministic
    // ARN, same reasoning as the bootstrap-role ARNs above: this stack deploys
    // before the ECR repo (otj-services-stack.ts) exists, so there's nothing to
    // grantPush() against yet.
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "EcrPush",
        actions: [
          "ecr:BatchCheckLayerAvailability",
          "ecr:PutImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          // Lets CI skip re-pushing a SHA tag that already exists, so re-running
          // a failed job (which re-runs the whole job, including this step)
          // doesn't hit ECR's immutable-tag rejection on the second attempt.
          "ecr:DescribeImages",
        ],
        resources: [`arn:aws:ecr:${DEPLOY_REGION}:${account}:repository/${ECR_REPOSITORY_NAME}`],
      }),
    );
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "EcrAuth",
        actions: ["ecr:GetAuthorizationToken"], // ECR requires Resource: "*" for this action, no repo-level scoping exists
        resources: ["*"],
      }),
    );

    // Lets CI trigger the box's deploy.sh (via AWS-RunShellScript) after a
    // successful image push, and poll for its result. Scoped by instance tag
    // rather than instance ID since the instance doesn't exist yet at this
    // stack's first deploy either, and tag-scoping survives the instance being
    // replaced by a future `cdk deploy OtjServicesStack`.
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "SsmTriggerDeploy",
        actions: ["ssm:SendCommand"],
        resources: [
          `arn:aws:ec2:${DEPLOY_REGION}:${account}:instance/*`,
          // AWS-owned public document — empty account segment (note the double colon).
          `arn:aws:ssm:${DEPLOY_REGION}::document/AWS-RunShellScript`,
        ],
        conditions: {
          StringEquals: { [`ssm:resourceTag/${EC2_TAG_KEY}`]: EC2_TAG_VALUE },
        },
      }),
    );
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "SsmPollDeployResult",
        actions: ["ssm:GetCommandInvocation"], // this action has no resource-level scoping, Resource: "*" is expected here
        resources: ["*"],
      }),
    );

    new cdk.CfnOutput(this, "DeployRoleArn", {
      value: deployRole.roleArn,
      description: "Set as the AWS_DEPLOY_ROLE_ARN repo variable in GitHub Actions",
    });
  }
}
