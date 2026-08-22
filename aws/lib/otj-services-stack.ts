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

    // 443 from Cloudflare's edge ranges is the only inbound rule, and it reaches Caddy on the
    // host — not the app. Caddy terminates TLS and rate limits, then proxies to 127.0.0.1:8945
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
      // DELIBERATELY STALE — do not "fix" this to describe the current rules.
      //
      // GroupDescription is immutable in CloudFormation, so editing this string replaces the
      // security group. That is survivable in itself, but the replacement changes GroupId, which
      // feeds the instance's SecurityGroupIds, which CloudFormation marks
      // `RequiresRecreation: Conditionally` — i.e. it decides at execution time whether to
      // recreate the instance. For a VPC instance it should not, and EC2-Classic is long retired,
      // but "should" is not a guarantee worth making against this box: Caddy, its rate-limit
      // plugin build, the Cloudflare Origin CA pair, the Caddyfile and the `tailscale serve`
      // config were all installed by hand and exist nowhere in this repo. Losing the instance
      // loses all of it.
      //
      // Keeping the original string means the group is updated in place — the ingress rules below
      // are simply added — and the instance drops out of the changeset entirely. Verified with
      // `cdk deploy --no-execute` plus `describe-change-set` on 2026-08-22.
      //
      // Once the box is reproducible from code (userdata, Ansible, an AMI — see issue #40's
      // direction of travel), this becomes safe to correct. Until then the accurate description
      // of the access model lives in aws/README.md and deploy/prod/README.md, which cost nothing
      // to change.
      description: "otjServices EC2 host - no inbound; SSM for admin, tailscale serve for app access",
      allowAllOutbound: true,
    });

    // Cloudflare's published edge ranges (https://www.cloudflare.com/ips-v4 and /ips-v6), as of
    // 2026-08-16. Duplicated in the `trusted_proxies` block of deploy/prod/Caddyfile, which must
    // list the same set — refresh both together.
    //
    // Narrowing to these is what makes the orange-cloud proxy mean anything. otj-services.com
    // resolves to Cloudflare, so the Elastic IP is not published — but "not published" is not
    // "not findable" (certificate transparency logs, old DNS history, scanning). With 0.0.0.0/0
    // here, anyone who turns it up connects straight to the origin, skipping Cloudflare's WAF
    // and DDoS protection, and — because Caddy trusts the forwarded header from any Cloudflare
    // range — could hand it a CF-Connecting-IP of their choosing and forge the rate-limit key.
    // The allowlist and the Caddyfile's trusted_proxies only work as a pair.
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

    // Rule descriptions are validated by EC2 against a fixed character set:
    //     a-zA-Z0-9. _-:/()#,@[]+=&;{}!$*
    // and a space. Notably absent is ">", so the obvious "edge -> Caddy" arrow is rejected —
    // with a 400 at UPDATE time, not at synth. CDK will happily build a template containing one,
    // CloudFormation gets as far as creating the replacement security group, and only then fails
    // and rolls the whole stack back. Keep these to plain words.
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

    // Port 80 is deliberately NOT opened. Cloudflare terminates the visitor's HTTP at its own
    // edge and speaks HTTPS to this origin, and the certificate here is a Cloudflare Origin CA
    // pair rather than ACME — so there is no HTTP-01 challenge to serve and nothing to redirect.
    // Opening 80 would only add an unauthenticated surface. If you ever move back to Let's
    // Encrypt on the origin, that is a return to DNS-01 or a grey-cloud record, not a port here.

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

    // Stable IP for the MongoDB Atlas allowlist and for the otj-services.com A record.
    //
    // The DNS record is deliberately NOT a CDK resource: the domain is registered with
    // Cloudflare Registrar, which requires Cloudflare's own nameservers, so there is no Route 53
    // hosted zone to put an ARecord in. That makes DNS a manual step — documented in
    // deploy/README.md alongside the Atlas allowlist, which was already manual for the same
    // reason. If this EIP is ever recreated, both have to be updated by hand.
    //
    // The A record is proxied (orange cloud), so this address is not what the name resolves to —
    // `dig otj-services.com` returns Cloudflare. To read the origin back, look at the record
    // content in the Cloudflare dashboard; DNS cannot tell you while the proxy is on.
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
