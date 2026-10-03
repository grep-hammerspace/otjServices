import * as cdk from "aws-cdk-lib";
import { Construct } from "constructs";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as iam from "aws-cdk-lib/aws-iam";
import * as ecr from "aws-cdk-lib/aws-ecr";
import * as logs from "aws-cdk-lib/aws-logs";

// Keep in sync with github-oidc-stack.ts, which grants CI read access to it by name.
const CONVERGE_LOG_GROUP = "/otj/converge";

export class OtjServicesStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

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

    // 443 from Cloudflare's ranges is the only inbound rule. The Quadlets bind 8945/8946 to
    // 127.0.0.1, and AdminIdentityFilter depends on nothing here reaching 8946: read
    // deploy/README.md before adding a rule.
    const instanceSecurityGroup = new ec2.SecurityGroup(this, "InstanceSecurityGroup", {
      vpc,
      // DELIBERATELY STALE. GroupDescription is immutable, so editing it replaces the security
      // group, which can recreate the instance. Leave it.
      description: "otjServices EC2 host - no inbound; SSM for admin, tailscale serve for app access",
      allowAllOutbound: true,
    });

    // Cloudflare's published ranges, duplicated in deploy/haproxy/cloudflare-ips.lst: refresh both
    // together. With 0.0.0.0/0, anyone who finds the Elastic IP could skip Cloudflare and forge CF-
    // Connecting-IP.
    const CLOUDFLARE_IPV4 = [
      "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
      "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
      "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
      "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    ];
    const CLOUDFLARE_IPV6 = [
      "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
      "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
    ];

    // EC2 rejects ">" and most punctuation in rule descriptions, and only at UPDATE time. Keep them
    // to plain words.
    for (const cidr of CLOUDFLARE_IPV4) {
      instanceSecurityGroup.addIngressRule(
        ec2.Peer.ipv4(cidr),
        ec2.Port.tcp(443),
        "Cloudflare edge to Caddy on 443, proxied to 127.0.0.1:8945",
      );
    }
    for (const cidr of CLOUDFLARE_IPV6) {
      instanceSecurityGroup.addIngressRule(
        ec2.Peer.ipv6(cidr),
        ec2.Port.tcp(443),
        "Cloudflare edge v6 to Caddy on 443, proxied to 127.0.0.1:8945",
      );
    }

    // Port 80 is deliberately not opened: Cloudflare speaks HTTPS to the origin, and there's no
    // ACME challenge to serve.

    const instanceRole = new iam.Role(this, "InstanceRole", {
      assumedBy: new iam.ServicePrincipal("ec2.amazonaws.com"),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
      ],
    });

    // Pinned: resolving the latest AMI on every deploy replaced the instance on 2026-09-25. A newer
    // AMI is a deliberate rebuild (aws/README.md).
    const ubuntu = ec2.MachineImage.genericLinux({
      "eu-west-2": "ami-05a81b93a249716f9", // ubuntu 24.04 amd64 gp3, Canonical, 2026-09-23
    });

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

    // Not a CDK DNS record: the domain uses Cloudflare's nameservers. If this EIP is recreated,
    // update the A record and the Atlas allowlist by hand.
    const elasticIp = new ec2.CfnEIP(this, "InstanceEip", { domain: "vpc" });
    new ec2.CfnEIPAssociation(this, "InstanceEipAssociation", {
      allocationId: elasticIp.attrAllocationId,
      instanceId: instance.instanceId,
    });

    // Scopes the CI deploy role's SendCommand to this instance.
    cdk.Tags.of(instance).add("otj:role", "app-host");

    // Immutable: a SHA tag can never be repointed.
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

    // Read-only, and /otj/prod/ only. GetParameter too, for the one-off origin-key install.
    instanceRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "ReadOtjProdParameters",
        actions: ["ssm:GetParameters", "ssm:GetParameter"],
        resources: [`arn:aws:ssm:${this.region}:${this.account}:parameter/otj/prod/*`],
      }),
    );
    // Scoped by kms:ViaService: the aws/ssm key's ARN isn't known up front.
    instanceRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "DecryptViaParameterStoreOnly",
        actions: ["kms:Decrypt"],
        resources: ["*"],
        conditions: { StringEquals: { "kms:ViaService": `ssm.${this.region}.amazonaws.com` } },
      }),
    );

    // get-command-invocation truncates at 24 KB, so converge output comes here. Fixed name: github-
    // oidc-stack.ts grants read on it by ARN.
    const convergeLogGroup = new logs.LogGroup(this, "ConvergeLogGroup", {
      logGroupName: CONVERGE_LOG_GROUP,
      retention: logs.RetentionDays.THREE_MONTHS,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    instanceRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "WriteConvergeOutput",
        actions: ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"],
        resources: [convergeLogGroup.logGroupArn],
      }),
    );
    // The agent checks the group exists before writing, and DescribeLogGroups has no resource-level
    // scoping.
    instanceRole.addToPolicy(
      new iam.PolicyStatement({
        sid: "FindConvergeLogGroup",
        actions: ["logs:DescribeLogGroups"],
        resources: ["*"],
      }),
    );

    new cdk.CfnOutput(this, "InstanceId", { value: instance.instanceId });
    new cdk.CfnOutput(this, "ElasticIp", { value: elasticIp.ref });
    new cdk.CfnOutput(this, "SsmConnectCommand", {
      value: `aws ssm start-session --target ${instance.instanceId} --region eu-west-2`,
    });
    new cdk.CfnOutput(this, "EcrRepositoryUri", { value: repository.repositoryUri });
  }
}
