variable "project" {
  type    = string
  default = "ramals"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "alb_ingress_cidrs" {
  description = <<-EOT
    Who may reach the load balancer.

    Empty by default, which creates a load balancer nothing can reach. That is the intended default:
    opening a DEV environment to the internet should be a line someone wrote, not one they inherited.
  EOT
  type        = list(string)
  default     = []
}

variable "platform_image" {
  description = "Replaced by the pipeline on first deploy."
  type        = string
  default     = "public.ecr.aws/docker/library/busybox:latest"
}

variable "ai_image" {
  type    = string
  default = "public.ecr.aws/docker/library/busybox:latest"
}

variable "oidc_issuer_uri" {
  description = "Keycloak realm issuer."
  type        = string
  default     = ""
}

variable "web_origin" {
  type    = string
  default = "http://localhost:5173"
}

variable "certificate_arn" {
  description = "ACM certificate. Empty until a domain exists; see the compute module."
  type        = string
  default     = ""
}

variable "github_repository" {
  type    = string
  default = "Skpandey15/RAMALS"
}

variable "snapshot_suffix" {
  description = "Final-snapshot name suffix; must be unique across deletes."
  type        = string
  default     = "v1"
}

variable "monthly_budget_usd" {
  description = "Alarm threshold on estimated charges."
  type        = number
  default     = 150
}

variable "state_bucket_arn" {
  description = "From the bootstrap output."
  type        = string
  default     = ""
}

variable "state_lock_table_arn" {
  type    = string
  default = ""
}
