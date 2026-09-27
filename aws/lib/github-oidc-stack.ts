import * as cdk from "aws-cdk-lib";
import { Construct } from "constructs";
import * as iam from "aws-cdk-lib/aws-iam";

const GITHUB_REPO = "grep-hammerspace/otjServices";
const CDK_QUALIFIER = "hnb659fds";
const DEPLOY_REGION = "eu-west-2";
const ECR_REPOSITORY_NAME = "otj-hours-api"; // keep in sync with otj-services-stack.ts
const EC2_TAG_KEY = "otj:role";
const EC2_TAG_VALUE = "app-host"; // keep in sync with otj-services-stack.ts
const CONVERGE_LOG_GROUP = "/otj/converge"; // keep in sync with otj-services-stack.ts
const STACK_NAME = "OtjServicesStack"; // keep in sync with bin/aws.ts

export class GithubOidcStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Only one OIDC provider per URL: if one already exists, import it with
    // fromOpenIdConnectProviderArn instead.
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
          // A job naming an environment presents environment:<name> instead of the ref, so
          // deploy.yml needs the second subject. production accepts only master; PRs match neither.
          StringLike: {
            "token.actions.githubusercontent.com:sub": [
              `repo:${GITHUB_REPO}:ref:refs/heads/master`,
              `repo:${GITHUB_REPO}:environment:production`,
            ],
          },
        },
        "sts:AssumeRoleWithWebIdentity",
      ),
      maxSessionDuration: cdk.Duration.hours(1),
    });

    // file-publishing-role is needed in CI. A local admin deploy silently falls back to direct
    // bucket access, which hides the gap.
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

    // A deterministic ARN: this stack deploys before the ECR repository exists.
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "EcrPush",
        actions: [
          "ecr:BatchCheckLayerAvailability",
          "ecr:PutImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          // So a re-run doesn't hit ECR's immutable-tag rejection.
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

    // Scoped by tag, not instance ID, so it survives the instance being replaced.
    // Two statements: SendCommand evaluates the instance and the untagged AWS document together, so
    // one tag-conditioned statement would deny every call.
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "SsmTriggerDeployInstance",
        actions: ["ssm:SendCommand"],
        resources: [`arn:aws:ec2:${DEPLOY_REGION}:${account}:instance/*`],
        conditions: {
          StringEquals: { [`ssm:resourceTag/${EC2_TAG_KEY}`]: EC2_TAG_VALUE },
        },
      }),
    );
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "SsmTriggerDeployDocument",
        actions: ["ssm:SendCommand"],
        // AWS-owned public document — empty account segment (note the double colon).
        resources: [`arn:aws:ssm:${DEPLOY_REGION}::document/AWS-RunShellScript`],
      }),
    );
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "SsmPollDeployResult",
        actions: ["ssm:GetCommandInvocation"], // this action has no resource-level scoping, Resource: "*" is expected here
        resources: ["*"],
      }),
    );

    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "ReadConvergeOutput",
        actions: ["logs:GetLogEvents", "logs:FilterLogEvents"],
        resources: [
          `arn:aws:logs:${DEPLOY_REGION}:${account}:log-group:${CONVERGE_LOG_GROUP}`,
          `arn:aws:logs:${DEPLOY_REGION}:${account}:log-group:${CONVERGE_LOG_GROUP}:log-stream:*`,
        ],
      }),
    );

    // This one parameter only: the secrets under /otj/prod/ stay unreadable to CI.
    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "LiveImageTag",
        actions: ["ssm:PutParameter", "ssm:GetParameter"],
        resources: [`arn:aws:ssm:${DEPLOY_REGION}:${account}:parameter/otj/prod/image-tag`],
      }),
    );

    deployRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "FindInstanceId",
        actions: ["cloudformation:DescribeStacks"],
        resources: [`arn:aws:cloudformation:${DEPLOY_REGION}:${account}:stack/${STACK_NAME}/*`],
      }),
    );

    new cdk.CfnOutput(this, "DeployRoleArn", {
      value: deployRole.roleArn,
      description: "Set as the AWS_DEPLOY_ROLE_ARN repo variable in GitHub Actions",
    });
  }
}
