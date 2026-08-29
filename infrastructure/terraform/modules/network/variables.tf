variable "name_prefix" {
  description = "Prefix for every resource name, so one account can hold more than one environment."
  type        = string
}

variable "region" {
  description = "AWS region. Fixes which AZs the subnets land in."
  type        = string
}

variable "vpc_cidr" {
  description = "The VPC address range. A /16 leaves room for tiers that do not exist yet."
  type        = string
  default     = "10.40.0.0/16"
}

variable "az_count" {
  description = <<-EOT
    Availability zones to spread subnets across.

    Two, not three. RDS requires a subnet group spanning at least two AZs even when the instance is
    single-AZ, so two is the floor rather than a choice. A third buys resilience this environment
    does not claim and pays for it in NAT and endpoint charges.
  EOT
  type        = number
  default     = 2
}

variable "enable_nat_gateway" {
  description = <<-EOT
    Whether private subnets get outbound internet.

    True by default, and expensive: one NAT gateway is roughly a third of this environment's bill.
    It stays on because ECR pulls, the identity provider and model providers all need egress, and a
    foundation that cannot reach any of them is a foundation nothing can be deployed onto.
  EOT
  type        = bool
  default     = true
}

variable "flow_log_retention_days" {
  description = "How long rejected-traffic logs are kept. Short: they are a debugging aid, not evidence."
  type        = number
  default     = 14
}
