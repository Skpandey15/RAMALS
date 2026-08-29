# The VPC, and the public/private split that every other boundary depends on.
#
# Two tiers, not three. Public subnets hold the load balancer and the NAT gateway and nothing else;
# private subnets hold both ECS services and the database. There is no "app tier" separate from a
# "data tier" at the subnet level, because subnet placement is not what keeps the AI plane away from
# the database -- security groups are, and they say so explicitly (see modules/security).
#
# Subnetting by hand rather than by module. The address plan is small enough to read in one screen,
# and a plan you can read is a plan you can audit.

locals {
  # /16 split into /24s. Deliberately generous per subnet and deliberately sparse overall: the gaps
  # are where a future tier goes without renumbering anything that already exists.
  public_cidrs  = [for index in range(var.az_count) : cidrsubnet(var.vpc_cidr, 8, index)]
  private_cidrs = [for index in range(var.az_count) : cidrsubnet(var.vpc_cidr, 8, index + 100)]
}

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "this" {
  cidr_block = var.vpc_cidr

  # Both required for VPC interface endpoints and for RDS to resolve by name inside the VPC.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.name_prefix}-vpc" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-igw" }
}

# -- public: load balancer and NAT only ------------------------------------------------------------

resource "aws_subnet" "public" {
  count = var.az_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = local.public_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]

  # The ALB needs a public address; nothing else in this VPC gets one. Tasks and the database are
  # private and reach outward through NAT.
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name_prefix}-public-${data.aws_availability_zones.available.names[count.index]}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = var.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# -- private: every workload, and the database -----------------------------------------------------

resource "aws_subnet" "private" {
  count = var.az_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = local.private_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = {
    Name = "${var.name_prefix}-private-${data.aws_availability_zones.available.names[count.index]}"
    Tier = "private"
  }
}

# -- egress --------------------------------------------------------------------------------------
#
# ONE NAT gateway, shared by every private subnet, and that is a deliberate DEV economy rather than
# an oversight. One NAT is roughly a third of this environment's monthly bill; three would be the
# single largest line item by a wide margin. The cost is that an AZ failure takes outbound traffic
# with it, which for a development environment is an inconvenience and for production would not be
# acceptable -- production HA is explicitly out of scope here and this is the main thing that would
# have to change.

resource "aws_eip" "nat" {
  count  = var.enable_nat_gateway ? 1 : 0
  domain = "vpc"
  tags   = { Name = "${var.name_prefix}-nat" }
}

resource "aws_nat_gateway" "this" {
  count = var.enable_nat_gateway ? 1 : 0

  allocation_id = aws_eip.nat[0].id
  subnet_id     = aws_subnet.public[0].id
  tags          = { Name = "${var.name_prefix}-nat" }

  depends_on = [aws_internet_gateway.this]
}

# One route table per private subnet even though they currently share a NAT, so that giving an AZ
# its own gateway later is a one-line change rather than a re-plumbing.
resource "aws_route_table" "private" {
  count = var.az_count

  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name_prefix}-private-${count.index}" }
}

resource "aws_route" "private_nat" {
  count = var.enable_nat_gateway ? var.az_count : 0

  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[0].id
}

resource "aws_route_table_association" "private" {
  count          = var.az_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# S3 through a gateway endpoint: free, and it keeps ECR layer pulls -- which are S3 reads -- off the
# NAT gateway entirely. Image pulls are the bulk of this environment's egress bytes, so this is the
# one endpoint that pays for itself immediately.
#
# Interface endpoints for ECR, Secrets Manager and CloudWatch are deliberately NOT created. Each
# bills per-AZ per-hour, and four of them would cost more than the NAT gateway they would be saving
# data charges on. That trade reverses at production traffic; it is recorded as a revisit trigger.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id

  tags = { Name = "${var.name_prefix}-s3" }
}

# -- flow logs -------------------------------------------------------------------------------------
#
# Rejected traffic only. The security groups below are the access-control statement; flow logs are
# how you find out they are wrong. Logging accepts as well would multiply the volume for traffic
# that, by construction, was already allowed.
resource "aws_cloudwatch_log_group" "flow" {
  name              = "/aws/vpc/${var.name_prefix}/flow-logs"
  retention_in_days = var.flow_log_retention_days
}

resource "aws_flow_log" "rejects" {
  vpc_id               = aws_vpc.this.id
  traffic_type         = "REJECT"
  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.flow.arn
  iam_role_arn         = aws_iam_role.flow_logs.arn
}

resource "aws_iam_role" "flow_logs" {
  name = "${var.name_prefix}-vpc-flow-logs"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "flow_logs" {
  name = "write-flow-logs"
  role = aws_iam_role.flow_logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams",
      ]
      # Scoped to this VPC's own log group rather than "*", which is the difference between a role
      # that can write flow logs and a role that can write anywhere in CloudWatch.
      Resource = "${aws_cloudwatch_log_group.flow.arn}:*"
    }]
  })
}
