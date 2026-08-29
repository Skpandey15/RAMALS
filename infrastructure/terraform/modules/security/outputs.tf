output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "platform_security_group_id" {
  value = aws_security_group.platform.id
}

output "ai_security_group_id" {
  value = aws_security_group.ai.id
}

output "database_security_group_id" {
  value = aws_security_group.database.id
}
