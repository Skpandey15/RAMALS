# Security groups: the architecture invariants, made enforceable.
#
# This file is the shortest one in the estate and the most load-bearing. Three of RAMALS' standing
# architectural rules are documented in ADRs and asserted by unit tests, and both of those can be
# argued with. Here they are network rules, and a violation is a connection refused:
#
#   1. The AI plane never reaches the authoritative database (M2-ADR-017 section 1). There is no
#      rule permitting ai -> rds. Not a scoped one, not a commented-out one -- none.
#   2. The AI plane is not internet-facing. The ALB cannot reach it; only the platform can.
#   3. The database is not public. Its only ingress is the platform security group.
#
# Rules are written as standalone aws_vpc_security_group_*_rule resources rather than inline blocks,
# because inline rules are replaced wholesale on change: a diff that reads "one rule modified"
# briefly removes every rule on the group. Standalone rules diff one at a time.

# -- the load balancer: the only thing the internet talks to ---------------------------------------

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Public entry point. The only group with ingress from outside the VPC."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each = toset(var.ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from permitted networks"
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# Port 80 exists only to redirect to 443. It never serves content.
resource "aws_vpc_security_group_ingress_rule" "alb_http_redirect" {
  for_each = toset(var.ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = "HTTP, redirected to HTTPS"
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

# The ALB may reach the platform and nothing else. Egress is written as one explicit rule rather
# than allow-all precisely so that "the ALB cannot reach the AI plane" is a fact about the
# configuration rather than a consequence of the AI plane's own ingress rules.
resource "aws_vpc_security_group_egress_rule" "alb_to_platform" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to the Spring platform"
  referenced_security_group_id = aws_security_group.platform.id
  from_port                    = var.platform_port
  to_port                      = var.platform_port
  ip_protocol                  = "tcp"
}

# -- the platform: authoritative, and the only caller of the AI plane -------------------------------

resource "aws_security_group" "platform" {
  name        = "${var.name_prefix}-platform"
  description = "Spring platform. Authoritative core; the only principal that may reach the database."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-platform" }
}

resource "aws_vpc_security_group_ingress_rule" "platform_from_alb" {
  security_group_id            = aws_security_group.platform.id
  description                  = "Only the load balancer may reach the platform"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = var.platform_port
  to_port                      = var.platform_port
  ip_protocol                  = "tcp"
}

# INVARIANT 1, first half: the platform reaches the AI plane. This is the only path between the two
# planes, and it runs in one direction.
resource "aws_vpc_security_group_egress_rule" "platform_to_ai" {
  security_group_id            = aws_security_group.platform.id
  description                  = "Platform to AI plane: the only inter-plane path"
  referenced_security_group_id = aws_security_group.ai.id
  from_port                    = var.ai_port
  to_port                      = var.ai_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "platform_to_database" {
  security_group_id            = aws_security_group.platform.id
  description                  = "Platform to PostgreSQL"
  referenced_security_group_id = aws_security_group.database.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# Outbound HTTPS for the identity provider, ECR pulls and AWS APIs. Narrower than allow-all, and
# still broad: restricting by destination address is not meaningful against endpoints that publish
# rotating address ranges.
resource "aws_vpc_security_group_egress_rule" "platform_https" {
  security_group_id = aws_security_group.platform.id
  description       = "HTTPS to AWS APIs, ECR and the identity provider"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# -- the AI plane: reachable only from the platform, and holding no database access -----------------

resource "aws_security_group" "ai" {
  name        = "${var.name_prefix}-ai"
  description = "AI plane. Not internet-facing, and deliberately has no path to the database."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-ai" }
}

# INVARIANT 2: the AI plane's only ingress is the platform. The ALB is not referenced here, so there
# is no route from the internet to this service at any port.
resource "aws_vpc_security_group_ingress_rule" "ai_from_platform" {
  security_group_id            = aws_security_group.ai.id
  description                  = "Only the Spring platform may reach the AI plane"
  referenced_security_group_id = aws_security_group.platform.id
  from_port                    = var.ai_port
  to_port                      = var.ai_port
  ip_protocol                  = "tcp"
}

# Outbound HTTPS for model providers, ECR and AWS APIs.
#
# INVARIANT 1, second half: there is NO egress rule from this group to the database group. The AI
# plane could not open a PostgreSQL connection if it were configured to try -- which is the point.
# M2-ADR-017 section 1 makes the AI plane stateless and the platform authoritative; a test asserts
# it holds no driver, and this asserts it could not use one.
resource "aws_vpc_security_group_egress_rule" "ai_https" {
  security_group_id = aws_security_group.ai.id
  description       = "HTTPS to model providers, ECR and AWS APIs"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# -- the database: private, and reachable from exactly one place -----------------------------------

resource "aws_security_group" "database" {
  name        = "${var.name_prefix}-database"
  description = "PostgreSQL. No public access; ingress from the platform only."
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-database" }
}

# INVARIANT 3. One ingress rule, from one security group. Not a CIDR -- referencing the group means
# the rule keeps holding as tasks come and go with new addresses, and it cannot be widened by
# accident the way a hand-maintained CIDR list can.
resource "aws_vpc_security_group_ingress_rule" "database_from_platform" {
  security_group_id            = aws_security_group.database.id
  description                  = "Only the Spring platform may reach PostgreSQL"
  referenced_security_group_id = aws_security_group.platform.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# No egress rule at all. A database has no reason to originate a connection, and the absence of a
# rule is a stronger statement than a narrow one.
