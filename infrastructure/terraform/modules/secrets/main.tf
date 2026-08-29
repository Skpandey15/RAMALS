# Customer-managed keys and the secret containers, with no secret values.
#
# Terraform creates the *containers* and never the contents. A secret whose value is in a .tf file
# is in git, in every plan output and in state; the only way for a credential not to leak through
# Terraform is for Terraform never to have held it. Values are written out of band -- see the
# environment README -- and this configuration deliberately ignores them thereafter.

resource "aws_kms_key" "secrets" {
  description             = "${var.name_prefix} application secrets"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days

  tags = { Name = "${var.name_prefix}-secrets" }
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${var.name_prefix}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}

# A separate key for data at rest. Same account, same rotation, different blast radius: revoking or
# rotating the secrets key must not be an event that touches the database's encryption.
resource "aws_kms_key" "data" {
  description             = "${var.name_prefix} data at rest"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days

  tags = { Name = "${var.name_prefix}-data" }
}

resource "aws_kms_alias" "data" {
  name          = "alias/${var.name_prefix}-data"
  target_key_id = aws_kms_key.data.key_id
}

locals {
  # One entry per credential the tasks need. The AI plane's provider key is present because the
  # container reads it at startup; Contract B being disabled does not change what the process
  # expects to find, and an empty secret is a clearer failure than a missing one.
  secrets = {
    db-app-password       = "PostgreSQL password for the application role"
    db-migration-password = "PostgreSQL password for the Flyway migration role"
    oidc-client-secret    = "Workload client secret for platform-to-AI-plane authentication"
    provider-api-key      = "Model provider API key, read by the AI plane only"
    contract-b-key        = "Contract B result encryption key material"
  }
}

resource "aws_secretsmanager_secret" "app" {
  for_each = local.secrets

  name        = "${var.name_prefix}/${each.key}"
  description = each.value
  kms_key_id  = aws_kms_key.secrets.arn

  # Short in DEV so a mistaken name can be recreated the same day. Production wants the full window.
  recovery_window_in_days = var.secret_recovery_window_days

  tags = { Name = "${var.name_prefix}-${each.key}" }
}

# No aws_secretsmanager_secret_version anywhere in this repository, and that absence is the control.
# Values are written once with the CLI by an operator and rotated the same way.
