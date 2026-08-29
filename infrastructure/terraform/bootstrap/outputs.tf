output "state_bucket" {
  description = "Put this in environments/dev/backend.tf."
  value       = aws_s3_bucket.state.id
}

output "state_bucket_arn" {
  value = aws_s3_bucket.state.arn
}

output "lock_table" {
  value = aws_dynamodb_table.locks.name
}

output "lock_table_arn" {
  value = aws_dynamodb_table.locks.arn
}
