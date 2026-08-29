# Remote state, created by ../../bootstrap.
#
# `bucket` is deliberately ABSENT. Its name is `ramals-tfstate-<account-id>`, so writing it here
# would commit the AWS account id, and a placeholder that must be hand-edited before the first
# `init` is a step that gets skipped or, worse, committed with a real value by someone who edited it
# to get their own run working.
#
# This is a Terraform *partial* backend: everything that is the same on every machine lives here,
# and the one account-specific value is supplied at init time. `init` without it fails rather than
# guessing, which is the behaviour worth having.
#
#   cd infrastructure/terraform/environments/dev
#   cp backend.hcl.example backend.hcl        # once, then fill in the bucket
#   terraform init -backend-config=backend.hcl
#
# backend.hcl is gitignored. See docs/architecture/aws-dev-foundation.md for deriving the bucket
# name from the bootstrap output, and for the CI form that needs no file at all.

terraform {
  backend "s3" {
    key            = "dev/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "ramals-tfstate-locks"
    encrypt        = true
  }
}
