import * as cdk from "aws-cdk-lib";
import { Construct } from "constructs";
import * as iam from "aws-cdk-lib/aws-iam";

const GITHUB_REPO = "grep-hammerspace/otjServices";
const CDK_QUALIFIER = "hnb659fds"; // default `cdk bootstrap` qualifier
const DEPLOY_REGION = "eu-west-2";

/**
 * One-time, deployed by hand (not by CI — CI needs this role to already exist
 * before it can authenticate). Sets up federated trust so GitHub Actions can
 * assume a role scoped to nothing but the CDK bootstrap's own deploy/lookup
 * roles for this account+region; no long-lived AWS credentials are stored in
 * GitHub. See aws/README.md.
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

    // Deliberately narrow: this role has no direct AWS permissions of its own,
    // only permission to assume the roles `cdk bootstrap` already created —
    // those already carry exactly what `cdk deploy`/`cdk diff` need.
    const account = cdk.Stack.of(this).account;
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        actions: ["sts:AssumeRole"],
        resources: [
          `arn:aws:iam::${account}:role/cdk-${CDK_QUALIFIER}-deploy-role-${account}-${DEPLOY_REGION}`,
          `arn:aws:iam::${account}:role/cdk-${CDK_QUALIFIER}-lookup-role-${account}-${DEPLOY_REGION}`,
        ],
      }),
    );

    new cdk.CfnOutput(this, "DeployRoleArn", {
      value: deployRole.roleArn,
      description: "Set as the AWS_DEPLOY_ROLE_ARN repo variable in GitHub Actions",
    });
  }
}
