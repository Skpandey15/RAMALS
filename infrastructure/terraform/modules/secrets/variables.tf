variable "name_prefix" {
  type = string
}

variable "kms_deletion_window_days" {
  description = "Grace period before a scheduled key deletion completes. Seven is the minimum AWS allows."
  type        = number
  default     = 7
}

variable "secret_recovery_window_days" {
  description = <<-EOT
    Grace period before a deleted secret is unrecoverable.

    Zero would make `terraform destroy` on a DEV environment instant and irreversible; seven costs
    nothing and buys a week to notice.
  EOT
  type        = number
  default     = 7
}
