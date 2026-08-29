# PostgreSQL, private, single-AZ.
#
# The authoritative store. M2-ADR-017 section 1 makes Spring and this database the system of record
# and the AI plane stateless, so everything here is arranged around one question: who can reach it.
# The answer is the platform security group and nothing else (see modules/security).

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db"
  subnet_ids = var.private_subnet_ids

  description = "Private subnets only. There is no public subnet in this group by construction."
  tags        = { Name = "${var.name_prefix}-db" }
}

# Parameters that belong to the schema rather than the instance size, so they survive a resize.
resource "aws_db_parameter_group" "this" {
  name        = "${var.name_prefix}-pg17"
  family      = "postgres17"
  description = "${var.name_prefix} PostgreSQL parameters"

  # Log any statement over a second. In DEV this is a development aid; the value is deliberately not
  # zero, which would log every statement including ones carrying learner-derived parameters.
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  # Connections are logged so an unexpected principal reaching the database is visible in the log
  # as well as blocked by the security group.
  parameter {
    name  = "log_connections"
    value = "1"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier = "${var.name_prefix}-postgres"

  engine                = "postgres"
  engine_version        = var.engine_version
  instance_class        = var.instance_class
  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  db_name  = var.database_name
  username = var.master_username

  # Managed by AWS, rotated by AWS, and never present in Terraform state as a plaintext value. The
  # alternative -- a password variable -- puts the master credential in state and in every plan.
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.kms_key_arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  parameter_group_name   = aws_db_parameter_group.this.name

  # The single most important line in this file. A publicly accessible database would be reachable
  # from the internet regardless of what the security groups say, because the address would resolve
  # publicly and only the group would stand between it and the world.
  publicly_accessible = false

  # Single-AZ: a DEV economy, and the thing that most obviously must change for production. Multi-AZ
  # roughly doubles the instance cost to buy an automatic failover this environment does not claim.
  multi_az = false

  backup_retention_period = var.backup_retention_days
  backup_window           = "18:00-19:00" # ~23:30 IST, outside working hours in ap-south-1
  maintenance_window      = "sun:19:30-sun:20:30"

  auto_minor_version_upgrade = true
  deletion_protection        = var.deletion_protection

  # A final snapshot on destroy. DEV data is disposable in principle and expensive to recreate in
  # practice once someone has built a scenario in it.
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name_prefix}-postgres-final-${var.snapshot_suffix}"

  performance_insights_enabled = false # billable beyond the free tier; not needed to qualify DEV
  monitoring_interval          = 0

  enabled_cloudwatch_logs_exports = ["postgresql"]

  tags = { Name = "${var.name_prefix}-postgres" }
}
