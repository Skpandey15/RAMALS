output "deploy_role_arn" {
  description = "Set as AWS_DEPLOY_ROLE_ARN in the repository's Actions variables."
  value       = aws_iam_role.deploy.arn
}

output "plan_role_arn" {
  value = var.create_plan_role ? aws_iam_role.plan[0].arn : ""
}
