variable "name_prefix" { type = string }

variable "github_repository" {
  description = "owner/repo. The trust policy pins to exactly this."
  type        = string
}

variable "allowed_refs" {
  description = <<-EOT
    Refs whose workflow runs may deploy, for jobs that declare no GitHub Environment.

    Produces `repo:OWNER/REPO:ref:<ref>`. Enumerated exactly; a wildcard here is refused by a
    precondition rather than merely discouraged.
  EOT
  type        = list(string)
  default     = ["refs/heads/main"]
}

variable "allowed_environments" {
  description = <<-EOT
    GitHub Environments whose deploy jobs may assume the deploy role.

    Produces `repo:OWNER/REPO:environment:<name>`. This form REPLACES the ref form in the token: a
    job that gains an `environment:` key stops minting a `ref:` subject entirely, so both are
    enumerated and a future move to Environments does not lock the pipeline out.

    **The environment subject encodes no branch.** It is minted for that environment from any ref,
    so the branch restriction must come from the Environment's own deployment-branch policy in
    GitHub. Configuring that is a required step, not an optional hardening.
  EOT
  type        = list(string)
  default     = ["dev"]
}

variable "plan_contexts" {
  description = <<-EOT
    Token contexts allowed to assume the read-only plan role.

    `pull_request` is the subject GitHub mints for a PR raised from a branch in this repository. A
    PR from a fork cannot reach here regardless: GitHub refuses `id-token: write` to fork pull
    requests, so no token exists to present.

    Deliberately not `ref:refs/heads/main`: the Terraform CI that runs on main is credential-free
    validation and has no reason to assume any AWS role.
  EOT
  type        = list(string)
  default     = ["pull_request"]
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
