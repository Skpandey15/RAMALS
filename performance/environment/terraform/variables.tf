# Inputs, with the spec's rules expressed as validation rather than as advice.
#
# A variable that merely documents a constraint is a comment. These refuse.

variable "region" {
  description = "AWS region. Both instances live in one subnet, so this also fixes the AZ."
  type        = string
}

variable "subnet_id" {
  description = <<-EOT
    The subnet both instances share. One subnet means one availability zone, which is deliberate:
    cross-AZ latency is a material fraction of the tightest request-class budget.
  EOT
  type        = string
}

variable "key_pair_name" {
  description = "An existing EC2 key pair. Provisioning is driven over SSH from the operator's machine."
  type        = string
}

variable "operator_cidr" {
  description = <<-EOT
    Where SSH is allowed from, as a CIDR. Your public address with /32, not 0.0.0.0/0 -- these hosts
    run a real deployment of the platform with seeded identity-provider credentials.
  EOT
  type        = string

  validation {
    condition     = var.operator_cidr != "0.0.0.0/0"
    error_message = "operator_cidr must not be 0.0.0.0/0: these hosts run Keycloak with known load-test users."
  }
}

variable "sut_instance_type" {
  description = <<-EOT
    The system under test. Must meet perf-standard-01: at least 8 vCPU and 16 GiB.
    m6i.2xlarge (8 vCPU / 32 GiB) leaves comfortable headroom above the 6 vCPU and 6 GiB the
    containers are pinned to. c6i.2xlarge (8 / 16) is the smallest that conforms.
  EOT
  type        = string
  default     = "m6i.2xlarge"

  validation {
    # The rule attest.py cannot enforce. Burstable instances deliver full CPU only while credits
    # last and then throttle to a fraction of a vCPU. Credits are invisible from inside the VM --
    # nproc reports the full count either way -- so a burstable host passes attestation and still
    # produces numbers that differ run to run for reasons nothing records.
    condition     = !can(regex("^t[0-9]", var.sut_instance_type))
    error_message = "Burstable (t-family) instances are credit-throttled, so two runs of the same commit give different numbers and nothing records which was which. Use a fixed-performance family: m6i, m7i, c6i, c7i."
  }
}

variable "loadgen_instance_type" {
  description = <<-EOT
    The load generator. It does not have to meet the SUT spec -- it only has to avoid becoming the
    bottleneck. c6i.xlarge is comfortable for the documented 60 rps mix.
  EOT
  type        = string
  default     = "c6i.xlarge"

  validation {
    # Same reasoning, and arguably worse here: a throttled load generator silently stops applying
    # the load it reports, and the run looks like the platform coping well.
    condition     = !can(regex("^t[0-9]", var.loadgen_instance_type))
    error_message = "Burstable (t-family) instances are credit-throttled. A throttled load generator stops applying the load it claims to, and the result reads as the platform coping."
  }
}

variable "root_volume_gib" {
  description = "SUT root volume. Holds the database as well as the images."
  type        = number
  default     = 100
}

variable "root_volume_iops" {
  description = <<-EOT
    Provisioned IOPS for the SUT volume. Above the gp3 default of 3000, because Postgres I/O moves
    every write-path number and the default is a plausible -- and variable -- bottleneck.
  EOT
  type        = number
  default     = 6000

  validation {
    condition     = var.root_volume_iops >= 3000
    error_message = "root_volume_iops below the gp3 baseline of 3000 would make storage the bottleneck being measured."
  }
}

variable "root_volume_throughput_mbps" {
  description = "Provisioned throughput for the SUT volume, MB/s."
  type        = number
  default     = 250
}
