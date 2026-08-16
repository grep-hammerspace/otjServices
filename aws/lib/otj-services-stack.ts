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

    // 80 and 443 are the only inbound rules, and they reach Caddy on the host — not the app.
    // Caddy terminates TLS and rate limits, then proxies to 127.0.0.1:8945
    // (see deploy/prod/Caddyfile). Admin access to the box is still SSM Session Manager, so
    // there is still no SSH port, and the admin API is still tailnet-only via the host's
    // `tailscale serve` rather than anything opened here.
    //
    // Nothing else is opened, and that is what the rest of the design rests on: the Quadlets
    // bind 8945/8946 to 127.0.0.1, so the only routes in are Caddy (public, main API) and
    // `tailscale serve` (tailnet). AdminIdentityFilter believes the Tailscale-User-Login header
    // precisely because no rule here can reach 8946 — see deploy/README.md before adding one.
    //
    // Outbound stays open (default) for package installs, the Tailscale control plane/DERP
    // relays, ECR pulls, Anthropic, and MongoDB Atlas connectivity.
    const instanceSecurityGroup = new ec2.SecurityGroup(this, "InstanceSecurityGroup", {
      vpc,
      description: "otjServices EC2 host - 80/443 to Caddy; SSM for shell, tailscale for the admin API",
      allowAllOutbound: true,
    });

    // Port 80 is not just a courtesy redirect: Caddy renews its certificate over the ACME
    // HTTP-01 challenge, which is served here. Closing it means moving to DNS-01, which would
    // put a Cloudflare API token on the box — a credential where there is currently none.
    instanceSecurityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(80),
      "ACME HTTP-01 renewal + Caddy's redirect to HTTPS",
    );
    instanceSecurityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(443),
      "Public API (api.otj-services.com) via Caddy -> 127.0.0.1:8945",
    );

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

    // Stable IP for the MongoDB Atlas allowlist and for the api.otj-services.com A record.
    //
    // The DNS record is deliberately NOT a CDK resource: the domain is registered with
    // Cloudflare Registrar, which requires Cloudflare's own nameservers, so there is no Route 53
    // hosted zone to put an ARecord in. That makes DNS a manual step — documented in
    // deploy/README.md alongside the Atlas allowlist, which was already manual for the same
    // reason. If this EIP is ever recreated, both have to be updated by hand.
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
