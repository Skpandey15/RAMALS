output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "alb_zone_id" {
  value = aws_lb.this.zone_id
}

output "platform_service_name" {
  value = aws_ecs_service.platform.name
}

output "ai_service_name" {
  value = aws_ecs_service.ai.name
}

output "platform_target_group_suffix" {
  description = "For CloudWatch alarm dimensions, which want the suffix rather than the ARN."
  value       = aws_lb_target_group.platform.arn_suffix
}

output "load_balancer_suffix" {
  value = aws_lb.this.arn_suffix
}

output "execution_role_arn" {
  value = aws_iam_role.execution.arn
}

output "platform_task_role_arn" {
  value = aws_iam_role.platform_task.arn
}

output "ai_task_role_arn" {
  value = aws_iam_role.ai_task.arn
}

output "ai_service_discovery_name" {
  value = "ai.${aws_service_discovery_private_dns_namespace.internal.name}"
}
