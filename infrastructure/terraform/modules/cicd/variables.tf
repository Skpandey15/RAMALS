variable "name_prefix" { type = string }

variable "github_repository" {
  description = "owner/repo. The trust policy pins to exactly this."
  type        = string
}

variable "allowed_refs" {
  description = <<-EOT
    Refs whose workflow runs may deploy.

    Branch refs only, and deliberately not a wildcard. A fork's pull request runs with a workflow
    file the fork controls; if it could match this condition it could deploy.
  EOT
  type        = list(string)
  default     = ["refs/heads/main"]
}

variable "create_oidc_provider" {
  description = "False where the account already has the GitHub OIDC provider; it is account-wide."
  type        = bool
  default     = true
}

variable "github_thumbprints" {
  description = "GitHub OIDC intermediate CA thumbprints."
  type        = list(string)
  default = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]
}

variable "ecr_repository_arns" {
  description = "Exactly the repositories a deploy may push to."
  type        = list(string)
}

variable "ecs_service_arns" {
  description = "Exactly the services a deploy may update."
  type        = list(string)
}

variable "passable_role_arns" {
  description = "The task and execution roles. Unscoped PassRole is privilege escalation."
  type        = list(string)
}

variable "create_plan_role" {
  description = "A read-only role so pull requests can plan without holding apply permissions."
  type        = bool
  default     = true
}

variable "state_bucket_arn" { type = string }
variable "state_lock_table_arn" { type = string }
