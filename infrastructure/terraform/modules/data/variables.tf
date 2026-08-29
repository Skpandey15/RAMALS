variable "name_prefix" {
  type = string
}

variable "private_subnet_ids" {
  description = "Private subnets across at least two AZs -- RDS requires two even when single-AZ."
  type        = list(string)
}

variable "security_group_id" {
  description = "The database group. Its only ingress is the platform."
  type        = string
}

variable "kms_key_arn" {
  description = "Key for storage and for the managed master password."
  type        = string
}

variable "engine_version" {
  description = "Matches the PostgreSQL the migrations are tested against."
  type        = string
  default     = "17.6"
}

variable "instance_class" {
  description = <<-EOT
    Instance size.

    db.t4g.micro: Graviton, burstable, and adequate for a DEV estate whose load is a test suite and
    a handful of qualification runs. Burstable is the right trade here for the same reason it is the
    wrong one in the performance environment -- there, credit exhaustion silently invalidates
    measurements; here, nothing is being measured.
  EOT
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Initial gp3 storage in GB."
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Autoscaling ceiling. Bounded so a runaway write cannot bill without limit."
  type        = number
  default     = 100
}

variable "database_name" {
  type    = string
  default = "ramals"
}

variable "master_username" {
  type    = string
  default = "ramals_master"
}

variable "backup_retention_days" {
  description = "Automated backup retention. Seven is the smallest window that survives a long weekend."
  type        = number
  default     = 7
}

variable "deletion_protection" {
  description = "On even in DEV: the cost of the extra step is a few seconds, the cost of the mistake is the estate."
  type        = bool
  default     = true
}

variable "snapshot_suffix" {
  description = "Makes the final snapshot name unique, since the name persists after the instance is gone."
  type        = string
}
