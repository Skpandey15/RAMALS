# Remote state, created by ../../bootstrap.
#
# Values are literal because a backend block cannot interpolate -- Terraform reads it before
# variables exist. Run bootstrap first, then fill the bucket name in from its output.

terraform {
  backend "s3" {
    bucket         = "ramals-tfstate-REPLACE_WITH_ACCOUNT_ID"
    key            = "dev/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "ramals-tfstate-locks"
    encrypt        = true
  }
}
