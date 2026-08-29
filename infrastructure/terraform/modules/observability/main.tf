# Log groups and a small number of alarms that mean something.
#
# Log groups are declared here rather than left to ECS, because a group created implicitly by the
# first task has no retention policy and keeps logs forever at full price.

resource "aws_cloudwatch_log_group" "service" {
  for_each = toset(var.services)

  name              = "/ecs/${var.name_prefix}/${each.value}"
  retention_in_days = var.retention_days
  kms_key_id        = var.kms_key_arn

  tags = { Name = "${var.name_prefix}-${each.value}" }
}

# Alarms deliberately cover only conditions an operator would act on tonight. A DEV environment with
# a wall of alarms nobody reads is worse than one with three that mean something.

resource "aws_cloudwatch_metric_alarm" "platform_unhealthy" {
  alarm_name          = "${var.name_prefix}-platform-unhealthy-hosts"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 2
  metric_name         = "UnHealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Maximum"
  threshold           = 1
  treat_missing_data  = "notBreaching"

  alarm_description = "The platform is failing its health check behind the load balancer."
  dimensions = {
    TargetGroup  = var.platform_target_group_suffix
    LoadBalancer = var.load_balancer_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "database_storage" {
  alarm_name          = "${var.name_prefix}-database-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Minimum"
  threshold           = 2147483648 # 2 GiB
  treat_missing_data  = "notBreaching"

  alarm_description = "The database is running out of space; autoscaling has a ceiling."
  dimensions        = { DBInstanceIdentifier = var.database_identifier }
}

# The cost guard. A DEV account's real risk is not an outage, it is a resource left running.
resource "aws_cloudwatch_metric_alarm" "estimated_charges" {
  count = var.monthly_budget_usd > 0 ? 1 : 0

  alarm_name          = "${var.name_prefix}-estimated-charges"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "EstimatedCharges"
  namespace           = "AWS/Billing"
  period              = 21600
  statistic           = "Maximum"
  threshold           = var.monthly_budget_usd
  treat_missing_data  = "notBreaching"

  alarm_description = "Estimated charges exceeded the DEV budget."
  dimensions        = { Currency = "USD" }
}
