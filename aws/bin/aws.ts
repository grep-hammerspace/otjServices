#!/usr/bin/env node
import * as cdk from "aws-cdk-lib";
import { OtjServicesStack } from "../lib/otj-services-stack";
import { GithubOidcStack } from "../lib/github-oidc-stack";

const app = new cdk.App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: "eu-west-2", // London
};

new OtjServicesStack(app, "OtjServicesStack", { env });

// Deployed once, by hand — see aws/README.md. Not part of the CI/CD workflow,
// since CI needs the role this stack creates to already exist to authenticate.
new GithubOidcStack(app, "GithubOidcStack", { env });
