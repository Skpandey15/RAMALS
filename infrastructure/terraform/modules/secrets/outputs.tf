output "secret_arns" {
  description = "Keyed by short name, so IAM policies can reference exactly the secrets a task needs."
  value       = { for name, secret in aws_secretsmanager_secret.app : name => secret.arn }
}

output "secrets_kms_key_arn" {
  value = aws_kms_key.secrets.arn
}

output "data_kms_key_arn" {
  value = aws_kms_key.data.arn
}
