# The DEV environment: every module, wired once.
#
# One environment per directory rather than workspaces. Workspaces share a configuration and differ
# only by variables, which is exactly wrong for environments that should be allowed to diverge --
# and it makes "what is actually deployed to dev" a question about which workspace was selected
# rather than a file you can read.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "ramals"
      Environment = "dev"
      ManagedBy   = "terraform"
      # So an untagged resource in this account is immediately suspicious.
      Repository = var.github_repository
    }
  }
}

locals {
  name_prefix = "${var.project}-${var.environment}"
}

module "network" {
  source = "../../modules/network"

  name_prefix        = local.name_prefix
  region             = var.region
  vpc_cidr           = var.vpc_cidr
  az_count           = 2
  enable_nat_gateway = true
}

module "security" {
  source = "../../modules/security"

  name_prefix   = local.name_prefix
  vpc_id        = module.network.vpc_id
  ingress_cidrs = var.alb_ingress_cidrs
}

module "secrets" {
  source = "../../modules/secrets"

  name_prefix = local.name_prefix
}

module "registry" {
  source = "../../modules/registry"

  name_prefix = local.name_prefix
  kms_key_arn = module.secrets.data_kms_key_arn
}

module "data" {
  source = "../../modules/data"

  name_prefix        = local.name_prefix
  private_subnet_ids = module.network.private_subnet_ids
  security_group_id  = module.security.database_security_group_id
  kms_key_arn        = module.secrets.data_kms_key_arn
  snapshot_suffix    = var.snapshot_suffix
}

module "observability" {
  source = "../../modules/observability"

  name_prefix                  = local.name_prefix
  kms_key_arn                  = module.secrets.secrets_kms_key_arn
  platform_target_group_suffix = module.compute.platform_target_group_suffix
  load_balancer_suffix         = module.compute.load_balancer_suffix
  database_identifier          = "${local.name_prefix}-postgres"
  monthly_budget_usd           = var.monthly_budget_usd
}

module "compute" {
  source = "../../modules/compute"

  name_prefix = local.name_prefix
  region      = var.region

  vpc_id             = module.network.vpc_id
  public_subnet_ids  = module.network.public_subnet_ids
  private_subnet_ids = module.network.private_subnet_ids

  alb_security_group_id      = module.security.alb_security_group_id
  platform_security_group_id = module.security.platform_security_group_id
  ai_security_group_id       = module.security.ai_security_group_id

  # Placeholders until the first pipeline run pushes a real tag. The services ignore task_definition
  # changes thereafter, so Terraform never fights the pipeline over which image is deployed.
  platform_image = var.platform_image
  ai_image       = var.ai_image

  secret_arns         = module.secrets.secret_arns
  all_secret_arns     = values(module.secrets.secret_arns)
  secrets_kms_key_arn = module.secrets.secrets_kms_key_arn

  database_jdbc_url = module.data.jdbc_url

  platform_log_group = module.observability.log_group_names["learning-platform"]
  ai_log_group       = module.observability.log_group_names["ramals-ai"]

  # Live provider execution is OPT-IN and stays OFF here.
  #
  # The compute module defaults `ai_enabled` to "false" and `ai_model_route` to "ci-fake", so the AI
  # plane runs, answers health checks and serves the capability gate without spending anything. That
  # default is deliberate: an environment that bills by existing is one nobody can leave running.
  #
  # To enable it for an approved OpenAI-backed qualification, set these three here in that change --
  # not as a standing default -- and record the approval alongside it:
  #
  #   ai_enabled     = "true"
  #   ai_model_route = "diagnostic-default"
  #   ai_model_pins  = jsonencode({
  #     "tutor-default" = "gpt-4.1-2025-04-14", "diagnostic-default" = "gpt-4.1-2025-04-14",
  #     "assessment-default" = "gpt-4.1-2025-04-14", "adaptation-default" = "gpt-4.1-2025-04-14"
  #   })
  #
  # The credential never appears here, in any manifest, or in state. `provider-api-key` is a Secrets
  # Manager container created empty by the secrets module; an operator writes the value into it out
  # of band, and CI fails the build if an `aws_secretsmanager_secret_version` ever appears in the
  # Terraform. Contract B is a separate switch and remains false regardless.

  oidc_issuer_uri = var.oidc_issuer_uri
  web_origin      = var.web_origin
  certificate_arn = var.certificate_arn
}

module "cicd" {
  source = "../../modules/cicd"

  name_prefix       = local.name_prefix
  github_repository = var.github_repository

  ecr_repository_arns = values(module.registry.repository_arns)

  ecs_service_arns = [
    "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/${module.compute.cluster_name}/${module.compute.platform_service_name}",
    "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/${module.compute.cluster_name}/${module.compute.ai_service_name}",
  ]

  passable_role_arns = [
    module.compute.execution_role_arn,
    module.compute.platform_task_role_arn,
    module.compute.ai_task_role_arn,
  ]

  state_bucket_arn     = var.state_bucket_arn
  state_lock_table_arn = var.state_lock_table_arn
}

data "aws_caller_identity" "current" {}
