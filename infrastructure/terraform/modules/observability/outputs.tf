output "log_group_names" {
  value = { for name, group in aws_cloudwatch_log_group.service : name => group.name }
}

output "log_group_arns" {
  value = { for name, group in aws_cloudwatch_log_group.service : name => group.arn }
}
