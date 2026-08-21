# The perf-standard-01 reference environment, as code.
#
# perf-standard-01.json declares what the environment must be; this declares how to create one that
# conforms. Both are reviewed artifacts, which is the point -- a reference environment that only
# exists as a sequence of console clicks cannot be recreated identically when the instance is
# replaced, and "recreated identically" is the whole meaning of the word reference.
#
# It also enforces the one rule the attestation cannot. Burstable instances deliver full CPU only
# while credits last; credits are invisible from inside the VM, so attest.py would certify a
# conforming host whose numbers were still unrepeatable. Terraform can refuse the instance type at
# plan time, which turns a rule somebody has to remember into one the tooling holds.
#
#   terraform init
#   terraform apply     # review the plan; both instances bill by the hour
#   terraform destroy   # removes the instances AND the security groups

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

# Ubuntu LTS x86_64, resolved rather than pinned to an AMI id: AMI ids differ per region and are
# replaced as the image is patched, so a hardcoded one is wrong everywhere except where it was
# written and stale everywhere after a while.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    # Both the hvm-ssd and hvm-ssd-gp3 publication paths are matched. Canonical uses both
    # depending on release and region, and pinning to one makes the data source fail
    # outright in regions that only carry the other -- a confusing error for a detail that
    # does not matter here.
    values = ["ubuntu/images/hvm-ssd*/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

data "aws_subnet" "selected" {
  id = var.subnet_id
}

# -- security groups -------------------------------------------------------------------------------
#
# Scoped to each other rather than to the world. The load fixtures provision Keycloak users, so an
# identity provider reachable from the internet would be carrying known test credentials -- a
# perf environment is still a real deployment of the platform.

resource "aws_security_group" "loadgen" {
  name_prefix = "ramals-perf-loadgen-"
  description = "RAMALS perf load generator"
  vpc_id      = data.aws_subnet.selected.vpc_id

  ingress {
    description = "SSH from the operator"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.operator_cidr]
  }

  egress {
    description = "outbound, for package installation and the run itself"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "ramals-perf-loadgen", Purpose = "perf-standard-01" }
}

resource "aws_security_group" "sut" {
  name_prefix = "ramals-perf-sut-"
  description = "RAMALS perf system under test"
  vpc_id      = data.aws_subnet.selected.vpc_id

  ingress {
    description = "SSH from the operator"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.operator_cidr]
  }

  ingress {
    description     = "application, from the load generator only"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.loadgen.id]
  }

  ingress {
    description     = "identity provider, from the load generator only"
    from_port       = 8081
    to_port         = 8081
    protocol        = "tcp"
    security_groups = [aws_security_group.loadgen.id]
  }

  egress {
    description = "outbound, for package and image pulls"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "ramals-perf-sut", Purpose = "perf-standard-01" }
}

# -- instances -------------------------------------------------------------------------------------

resource "aws_instance" "sut" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.sut_instance_type
  subnet_id              = var.subnet_id
  key_name               = var.key_pair_name
  vpc_security_group_ids = [aws_security_group.sut.id]

  # gp3 with raised IOPS. Postgres I/O moves every write-path number in the mix, and the 3000-IOPS
  # default is a plausible bottleneck for the assessment-write class -- worse, a variable one. gp2
  # is avoided because its IOPS scale with volume size, which would make the bottleneck a function
  # of how large somebody made the disk.
  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_gib
    iops        = var.root_volume_iops
    throughput  = var.root_volume_throughput_mbps
    encrypted   = true
  }

  # IMDSv2 only. The instance metadata service answers unauthenticated GETs at a link-local address
  # under IMDSv1, so any server-side request forgery in something running here can read it -- and
  # what it returns includes credentials for whatever role the instance carries. IMDSv2 requires a
  # session token obtained by PUT, which a forged GET cannot produce.
  #
  # This host runs the platform itself, which makes outbound HTTP calls of its own, so the SSRF
  # surface is real rather than theoretical.
  #
  # hop_limit 1 stops containers reaching the metadata service at all: bridge networking adds a hop,
  # so a limit of one keeps IMDS reachable from the host and not from inside a container. Nothing in
  # this stack has any business reading instance metadata.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  tags = { Name = "ramals-perf-sut", Purpose = "perf-standard-01", Role = "system-under-test" }
}

resource "aws_instance" "loadgen" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.loadgen_instance_type
  # The same subnet, so the same availability zone. Cross-AZ adds roughly a millisecond each way,
  # which is a material fraction of the 250 ms skill_map_read budget: the run would be measuring
  # the network rather than the platform.
  subnet_id              = var.subnet_id
  key_name               = var.key_pair_name
  vpc_security_group_ids = [aws_security_group.loadgen.id]

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  # IMDSv2 only. The instance metadata service answers unauthenticated GETs at a link-local address
  # under IMDSv1, so any server-side request forgery in something running here can read it -- and
  # what it returns includes credentials for whatever role the instance carries. IMDSv2 requires a
  # session token obtained by PUT, which a forged GET cannot produce.
  #
  # This host runs the platform itself, which makes outbound HTTP calls of its own, so the SSRF
  # surface is real rather than theoretical.
  #
  # hop_limit 1 stops containers reaching the metadata service at all: bridge networking adds a hop,
  # so a limit of one keeps IMDS reachable from the host and not from inside a container. Nothing in
  # this stack has any business reading instance metadata.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  tags = { Name = "ramals-perf-loadgen", Purpose = "perf-standard-01", Role = "load-generator" }
}
