import * as cdk from "aws-cdk-lib";
import { Construct } from "constructs";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as iam from "aws-cdk-lib/aws-iam";
import * as ecr from "aws-cdk-lib/aws-ecr";

export class OtjServicesStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // Single AZ, public subnet only, no NAT gateway — the instance gets its own
    // public IP and reaches the internet directly via the VPC's internet gateway.
    const vpc = new ec2.Vpc(this, "Vpc", {
      maxAzs: 1,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: "public",
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
      ],
    });

    // No inbound rules at all. Admin access is via SSM Session Manager (IAM-gated,
    // no open port); app access is via the host's `tailscale serve`, which binds
    // only the tailnet interface — see deploy/README.md for that trust model.
    // Outbound stays open (default) for package installs, the Tailscale control
    // plane/DERP relays, and MongoDB Atlas connectivity.
    const instanceSecurityGroup = new ec2.SecurityGroup(this, "InstanceSecurityGroup", {
      vpc,
      description: "otjServices EC2 host - no inbound; SSM for admin, tailscale serve for app access",
      allowAllOutbound: true,
    });

    const instanceRole = new iam.Role(this, "InstanceRole", {
      assumedBy: new iam.ServicePrincipal("ec2.amazonaws.com"),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
      ],
    });

    const ubuntu = ec2.MachineImage.fromSsmParameter(
      "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id",
      { os: ec2.OperatingSystemType.LINUX },
    );

    const instance = new ec2.Instance(this, "Instance", {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.SMALL),
      machineImage: ubuntu,
      securityGroup: instanceSecurityGroup,
      role: instanceRole,
      blockDevices: [
        {
          deviceName: "/dev/sda1",
          volume: ec2.BlockDeviceVolume.ebs(20, { volumeType: ec2.EbsDeviceVolumeType.GP3 }),
        },
      ],
    });

    // Stable IP for the MongoDB Atlas allowlist.
    const elasticIp = new ec2.CfnEIP(this, "InstanceEip", { domain: "vpc" });
    new ec2.CfnEIPAssociation(this, "InstanceEipAssociation", {
      allocationId: elasticIp.attrAllocationId,
      instanceId: instance.instanceId,
    });

    // Tag used to scope the GitHub Actions deploy role's SSM SendCommand permission
    // (github-oidc-stack.ts) to this instance without hardcoding its ID there.
    cdk.Tags.of(instance).add("otj:role", "app-host");

    // Every tag is a commit SHA, pushed once by CI — immutable so a SHA can never
    // silently be repointed at a different image.
    const repository = new ecr.Repository(this, "AppRepository", {
      repositoryName: "otj-hours-api", // keep in sync with ECR_REPOSITORY_NAME in github-oidc-stack.ts
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [
        { tagStatus: ecr.TagStatus.UNTAGGED, maxImageAge: cdk.Duration.days(1) },
        { tagStatus: ecr.TagStatus.TAGGED, tagPatternList: ["*"], maxImageCount: 20 },
      ],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    repository.grantPull(instanceRole);

    new cdk.CfnOutput(this, "InstanceId", { value: instance.instanceId });
    new cdk.CfnOutput(this, "ElasticIp", { value: elasticIp.ref });
    new cdk.CfnOutput(this, "SsmConnectCommand", {
      value: `aws ssm start-session --target ${instance.instanceId} --region eu-west-2`,
    });
    new cdk.CfnOutput(this, "EcrRepositoryUri", { value: repository.repositoryUri });
  }
}
