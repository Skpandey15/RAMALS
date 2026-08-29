output "endpoint" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "database_name" {
  value = aws_db_instance.this.db_name
}

output "master_user_secret_arn" {
  description = "AWS-managed master credential. Application roles are created from it, not as it."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "jdbc_url" {
  value = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${aws_db_instance.this.db_name}"
}
