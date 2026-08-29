output "deploy_role_arn" {
  description = "Set as AWS_DEPLOY_ROLE_ARN in the repository's Actions variables."
  value       = aws_iam_role.deploy.arn
}

output "plan_role_arn" {
  value = var.create_plan_role ? aws_iam_role.plan[0].arn : ""
}

# Exposed so the trust subjects can be asserted by `terraform test` without an AWS account, and so
# an operator can read what the policy will actually match without rendering a plan.
output "deploy_trust_subjects" {
  description = "Exact `sub` claims the deploy role accepts."
  value       = local.deploy_subjects
}

output "plan_trust_subjects" {
  description = "Exact `sub` claims the read-only plan role accepts."
  value       = local.plan_subjects
}
