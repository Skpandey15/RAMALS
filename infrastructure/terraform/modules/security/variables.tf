variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "ingress_cidrs" {
  description = <<-EOT
    Who may reach the load balancer.

    Defaults to nobody, deliberately. An empty list creates a load balancer with no ingress rule at
    all, which is inert rather than open -- the safe direction for a default. A DEV environment
    should carry the operator's own address range here; 0.0.0.0/0 is a decision to be made
    explicitly in tfvars, never inherited from a module default.
  EOT
  type        = list(string)
  default     = []
}

variable "platform_port" {
  description = "The Spring platform's container port."
  type        = number
  default     = 8080
}

variable "ai_port" {
  description = "The AI plane's container port."
  type        = number
  default     = 8000
}
