output "state_bucket" {
  description = <<-EOT
    The remote state bucket. Do NOT paste this into environments/dev/backend.tf -- `bucket` was
    deliberately removed from that block because this name embeds the AWS account id and the file is
    tracked. It belongs in the gitignored environments/dev/backend.hcl, or in an `init
    -backend-config="bucket=..."` argument. See docs/architecture/aws-dev-foundation.md.
  EOT
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
