# Remote state, and nothing else.
#
# This is the one configuration that cannot use remote state, because it creates it. It runs once,
# with a local state file that is committed nowhere, and afterwards every other configuration keys
# off the bucket and table it made. Keeping it in a separate directory is what stops that
# chicken-and-egg from becoming a circular dependency somebody discovers during an incident.
#
#   cd infrastructure/terraform/bootstrap
#   terraform init && terraform apply
#
# Its own state is disposable on purpose: everything here is either recreatable from this file or
# protected by prevent_destroy. Losing bootstrap/terraform.tfstate costs an import, not an outage.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "ramals"
      ManagedBy = "terraform"
      Component = "tf-state"
    }
  }
}

# The state bucket holds database endpoints, security group ids and secret ARNs. It is not secret
# material, but it is a map of the estate, so it is encrypted, versioned and closed to the public
# with no exceptions.
resource "aws_s3_bucket" "state" {
  bucket = "${var.name_prefix}-tfstate-${data.aws_caller_identity.current.account_id}"

  # State is the one thing whose accidental deletion is unrecoverable by re-running Terraform:
  # destroying the bucket orphans every resource it tracked.
  lifecycle {
    prevent_destroy = true
  }
}

data "aws_caller_identity" "current" {}

# Versioning is the actual recovery mechanism. A corrupted or truncated state file is restored by
# rolling back an object version; without it the only recovery is a hand-written import of every
# resource in the estate.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Old versions are kept long enough to recover from a bad apply noticed days later, then expire so
# the bucket does not grow without bound.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# State locking. Two applies against one environment without this interleave writes and produce a
# state file describing an estate that never existed -- the failure mode that is hardest to unpick
# because Terraform itself believes the result.
resource "aws_dynamodb_table" "locks" {
  name         = "${var.name_prefix}-tfstate-locks"
  billing_mode = "PAY_PER_REQUEST" # a lock table sees single-digit requests per apply
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  # Point-in-time recovery, enabled with a caveat worth stating plainly: the rows here are in-flight
  # locks, and recovering yesterday's lock is meaningless. The thing that actually needs recovery is
  # the state bucket, and that is versioned above.
  #
  # It is on anyway because it costs effectively nothing on a table measured in bytes, and because a
  # security baseline everyone has to remember the exceptions to stops being a baseline.
  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }
}
