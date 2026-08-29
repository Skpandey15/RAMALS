variable "name_prefix" {
  type = string
}

variable "services" {
  type    = list(string)
  default = ["learning-platform", "ramals-ai"]
}

variable "retention_days" {
  description = "Log retention. Thirty days is long enough to investigate and short enough to be cheap."
  type        = number
  default     = 30
}

variable "kms_key_arn" {
  description = "Logs can carry request context; they are encrypted with the same key as secrets."
  type        = string
}

variable "platform_target_group_suffix" {
  type = string
}

variable "load_balancer_suffix" {
  type = string
}

variable "database_identifier" {
  type = string
}

variable "monthly_budget_usd" {
  description = "Alarm threshold on estimated charges. Zero disables the alarm."
  type        = number
  default     = 150
}
