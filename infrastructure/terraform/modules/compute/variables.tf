variable "name_prefix" { type = string }
variable "region" { type = string }
variable "vpc_id" { type = string }
variable "public_subnet_ids" { type = list(string) }
variable "private_subnet_ids" { type = list(string) }

variable "alb_security_group_id" { type = string }
variable "platform_security_group_id" { type = string }
variable "ai_security_group_id" { type = string }

variable "platform_image" {
  description = "Fully qualified image reference. The pipeline replaces this per deploy."
  type        = string
}

variable "ai_image" {
  type = string
}

variable "platform_port" {
  type    = number
  default = 8080
}

variable "ai_port" {
  type    = number
  default = 8000
}

# 0.25 vCPU / 512 MB is the smallest Fargate combination. Adequate for a DEV platform serving a test
# suite; the JVM is the reason the platform gets more memory than the AI plane.
variable "platform_cpu" {
  type    = number
  default = 512
}

variable "platform_memory" {
  type    = number
  default = 1024
}

variable "ai_cpu" {
  type    = number
  default = 256
}

variable "ai_memory" {
  type    = number
  default = 512
}

variable "cpu_architecture" {
  description = "ARM64: Graviton Fargate is materially cheaper and both images build for it."
  type        = string
  default     = "ARM64"
}

variable "platform_desired_count" {
  description = <<-EOT
    Tasks for the platform.

    One. Not a resilience choice -- a correctness one for this phase: the platform runs the Contract
    B reconciliation worker, whose inspection budget is per process (M2-ADR-020 section 7). A second
    task would double the provider request rate against an organisation-wide limit. Contract B is off
    here, so the constraint is not yet live, and this is the note that stops it being raised silently
    when it is.
  EOT
  type        = number
  default     = 1
}

variable "ai_desired_count" {
  description = "The AI plane is stateless and could scale; one is enough for DEV."
  type        = number
  default     = 1
}

# The execution and task roles are created in iam.tf rather than passed in. They exist only to
# serve these two task definitions, so owning them here keeps the trust policy and the thing it
# trusts in one place -- and removes a module input that could be wired to the wrong role.

variable "secret_arns" {
  description = "Short name to ARN. Injected by reference; values never enter Terraform."
  type        = map(string)
}

variable "all_secret_arns" {
  description = "Every secret ARN, for the execution role's enumerated read policy."
  type        = list(string)
}

variable "secrets_kms_key_arn" { type = string }

variable "database_jdbc_url" { type = string }

variable "database_app_user" {
  type    = string
  default = "ramals_core_runtime"
}

variable "database_migration_user" {
  type    = string
  default = "ramals_core_migration"
}

variable "platform_log_group" { type = string }
variable "ai_log_group" { type = string }

variable "oidc_issuer_uri" {
  description = "Identity provider issuer. Both planes validate tokens against it."
  type        = string
}

variable "oidc_audience" {
  type    = string
  default = "ramals-api"
}

variable "web_origin" {
  description = "Permitted browser origin for CORS."
  type        = string
}

variable "spring_profile" {
  type    = string
  default = "dev"
}

variable "ai_environment" {
  type    = string
  default = "dev"
}

variable "ai_enabled" {
  description = <<-EOT
    Whether the AI plane may call a real model provider.

    False for the foundation phase. The service runs, answers health checks and serves the capability
    gate; it does not spend money. Turning it on is a separate, deliberate decision.
  EOT
  type        = string
  default     = "false"
}

variable "ai_model_route" {
  description = "Deterministic fake by default: no provider call, no spend."
  type        = string
  default     = "ci-fake"
}

variable "certificate_arn" {
  description = <<-EOT
    ACM certificate for the HTTPS listener.

    Empty until a domain exists. While empty there is no HTTPS listener at all, and port 80's
    redirect leads nowhere -- the environment is unreachable rather than served in the clear. There
    is deliberately no plaintext fallback.
  EOT
  type        = string
  default     = ""
}

variable "container_insights" {
  description = "Per-container CloudWatch metrics. Billable; off by default in DEV."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  type    = bool
  default = false
}

variable "enable_ecs_exec" {
  description = "Shell access into a running task, for DEV bring-up."
  type        = bool
  default     = true
}
