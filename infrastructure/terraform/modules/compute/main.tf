# ALB, ECS Fargate, and the two services.
#
# The deployment shape in one paragraph: the ALB is the only public thing and forwards to the Spring
# platform alone; the AI plane runs as a second service with no target group and no listener, so it
# is reachable only by service discovery from inside the VPC. That is the architectural statement --
# the AI plane is infrastructure the platform calls, not an endpoint anyone else can.

# -- cluster ---------------------------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = var.container_insights ? "enabled" : "disabled"
  }

  tags = { Name = "${var.name_prefix}-cluster" }
}

# FARGATE_SPOT is not in this list. Spot reclaims capacity with two minutes' notice, which is
# survivable for a stateless request handler and is not for a reconciliation worker holding a lease
# on durable work -- and the platform runs both in one task.
resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# -- service discovery: how the platform finds the AI plane ----------------------------------------
#
# A private DNS namespace rather than a second internal load balancer. One ALB is about a sixth of
# this environment's cost; a second one to front a service with exactly one client would double that
# for nothing. Cloud Map gives the platform a stable name and costs cents.

resource "aws_service_discovery_private_dns_namespace" "internal" {
  name        = "${var.name_prefix}.internal"
  description = "Internal service discovery. Not resolvable outside the VPC."
  vpc         = var.vpc_id
}

resource "aws_service_discovery_service" "ai" {
  name = "ai"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.internal.id

    dns_records {
      ttl  = 10 # short: tasks are replaced on every deploy
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

# -- load balancer ---------------------------------------------------------------------------------

resource "aws_lb" "this" {
  name               = "${var.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  subnets            = var.public_subnet_ids
  security_groups    = [var.alb_security_group_id]

  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
  idle_timeout               = 65

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_lb_target_group" "platform" {
  name        = "${var.name_prefix}-platform"
  port        = var.platform_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # Fargate awsvpc tasks register by address, not instance

  # Spring Boot's actuator, which reports readiness rather than mere liveness -- it fails while
  # Flyway is still migrating, which is exactly when traffic must not arrive.
  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # Long enough for in-flight requests to finish, short enough that a deploy is not a coffee break.
  deregistration_delay = 30

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn == "" ? 0 : 1

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.platform.arn
  }
}

# Port 80 redirects and never serves. Always -- there is no plaintext fallback.
#
# This previously forwarded to the target group when no certificate had been issued, so that the
# environment was reachable during bring-up. That was convenience bought with the one property a
# public listener should not trade: it served the application over plain HTTP, and a scanner caught
# it pointing straight at the forward action.
#
# Without a certificate there is now no HTTPS listener and this redirect leads nowhere, so the
# environment is simply unreachable until `certificate_arn` is set. That is the same fail-closed
# posture as `alb_ingress_cidrs` defaulting to empty: an environment you cannot reach yet is a
# smaller problem than one reachable in the clear.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# -- task definitions ------------------------------------------------------------------------------
#
# Secret injection is by ARN reference, never by value. ECS resolves each one at task start using
# the execution role and hands it to the container as an environment variable; the value never
# appears in the task definition, in Terraform state, in a plan, or in the console.

locals {
  platform_secrets = [
    { name = "RAMALS_DB_PASSWORD", valueFrom = var.secret_arns["db-app-password"] },
    { name = "RAMALS_DB_MIGRATION_PASSWORD", valueFrom = var.secret_arns["db-migration-password"] },
    { name = "RAMALS_AI_WORKLOAD_CLIENT_SECRET", valueFrom = var.secret_arns["oidc-client-secret"] },
  ]

  # The AI plane gets the provider key and nothing else. It has no database credential because it
  # has no database -- the same invariant the security groups enforce, stated again where an
  # operator adding a variable would see it.
  ai_secrets = [
    { name = "RAMALS_AI_PROVIDER_API_KEY", valueFrom = var.secret_arns["provider-api-key"] },
  ]

  # Contract B is off. All three switches are set explicitly rather than left to the image default,
  # so the deployed state is readable from the task definition instead of inferred from a jar.
  contract_b_environment = [
    { name = "RAMALS_CONTRACT_B_ENABLED", value = "false" },
    { name = "RAMALS_CONTRACT_B_RECONCILIATION_ENABLED", value = "false" },
    { name = "RAMALS_CONTRACT_B_PURGE_ENABLED", value = "false" },
  ]
}

resource "aws_ecs_task_definition" "platform" {
  family                   = "${var.name_prefix}-learning-platform"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.platform_cpu
  memory                   = var.platform_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.platform_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name      = "learning-platform"
    image     = "${var.platform_image}"
    essential = true

    portMappings = [{ containerPort = var.platform_port, protocol = "tcp" }]

    environment = concat([
      { name = "RAMALS_DB_URL", value = var.database_jdbc_url },
      { name = "RAMALS_DB_USER", value = var.database_app_user },
      { name = "RAMALS_DB_MIGRATION_USER", value = var.database_migration_user },
      # The AI plane by its service-discovery name. Not an address, and not a load balancer.
      { name = "RAMALS_AI_BASE_URL", value = "http://ai.${var.name_prefix}.internal:${var.ai_port}" },
      { name = "RAMALS_OIDC_ISSUER_URI", value = var.oidc_issuer_uri },
      { name = "RAMALS_OIDC_AUDIENCE", value = var.oidc_audience },
      { name = "RAMALS_WEB_ORIGIN", value = var.web_origin },
      { name = "SPRING_PROFILES_ACTIVE", value = var.spring_profile },
    ], local.contract_b_environment)

    secrets = local.platform_secrets

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = var.platform_log_group
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "platform"
      }
    }

    # A container-level check as well as the target group's. The ALB decides whether to send
    # traffic; this decides whether ECS replaces the task, and a process that is up but not serving
    # should be replaced rather than left in the rotation failing checks forever.
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsS http://localhost:${var.platform_port}/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 120 # Flyway migrations run at boot and must not count as failures
    }
  }])

  tags = { Name = "${var.name_prefix}-learning-platform" }
}

resource "aws_ecs_task_definition" "ai" {
  family                   = "${var.name_prefix}-ramals-ai"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.ai_cpu
  memory                   = var.ai_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.ai_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name      = "ramals-ai"
    image     = "${var.ai_image}"
    essential = true

    portMappings = [{ containerPort = var.ai_port, protocol = "tcp" }]

    environment = [
      { name = "RAMALS_AI_ENVIRONMENT", value = var.ai_environment },
      # Contract B's durable surface is off, so the durable router is not even mounted.
      { name = "RAMALS_AI_DURABLE_EXECUTION_ENABLED", value = "false" },
      { name = "RAMALS_AI_WORKLOAD_AUTH_ENABLED", value = "true" },
      { name = "RAMALS_AI_OIDC_ISSUER", value = var.oidc_issuer_uri },
      { name = "RAMALS_AI_OIDC_AUDIENCE", value = "ramals-ai" },
      { name = "RAMALS_AI_AI_ENABLED", value = var.ai_enabled },
      { name = "RAMALS_AI_MODEL_ROUTE", value = var.ai_model_route },
      { name = "RAMALS_AI_MODEL_PINS", value = var.ai_model_pins },
    ]

    secrets = local.ai_secrets

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = var.ai_log_group
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "ai"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "python -c \"import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:${var.ai_port}/health/live').status==200 else 1)\""]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 30
    }
  }])

  tags = { Name = "${var.name_prefix}-ramals-ai" }
}

# -- services --------------------------------------------------------------------------------------
#
# Rolling deployment with a circuit breaker and automatic rollback. This is the rollback story: a
# deployment whose tasks fail their health checks is rolled back by ECS to the last known-good task
# definition without anyone being paged. Manual rollback remains available -- ECR tags are immutable,
# so re-deploying a previous task definition revision is deterministic.

resource "aws_ecs_service" "platform" {
  name            = "${var.name_prefix}-learning-platform"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.platform.arn
  desired_count   = var.platform_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.platform_security_group_id]
    assign_public_ip = false # private subnets; egress is through NAT
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.platform.arn
    container_name   = "learning-platform"
    container_port   = var.platform_port
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # 100/200 with desired_count 1 means: start the new task, wait for it to pass health checks, then
  # stop the old one. No window in which zero tasks are serving.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  health_check_grace_period_seconds = 180 # migrations again

  # The pipeline updates the image; Terraform owns the shape. Without this, every plan after a
  # deployment would propose reverting to the image Terraform last knew about.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener.http]

  tags = { Name = "${var.name_prefix}-learning-platform" }
}

resource "aws_ecs_service" "ai" {
  name            = "${var.name_prefix}-ramals-ai"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.ai.arn
  desired_count   = var.ai_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.ai_security_group_id]
    assign_public_ip = false
  }

  # No load_balancer block, deliberately. The AI plane has no target group and no listener; it is
  # reachable only through service discovery, from inside the VPC, by a caller the security groups
  # permit -- which is the platform and nothing else.
  service_registries {
    registry_arn = aws_service_discovery_service.ai.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  tags = { Name = "${var.name_prefix}-ramals-ai" }
}
