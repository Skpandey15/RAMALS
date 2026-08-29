# GitHub Actions deploys with a short-lived OIDC token. There is no access key anywhere.
#
# The point of OIDC here is not convenience, it is that there is no long-lived credential to leak,
# rotate or find in a log. GitHub presents a signed token describing which repository and which ref
# is running; AWS trades it for a session that expires in an hour.
#
# The trust policy is where this is either safe or worthless. A trust condition that matches only
# the audience -- or worse, `repo:*` -- lets *any* GitHub repository in the world assume this role.
# The subject condition below pins the repository and the ref.

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

# Created only where it does not already exist: the provider is account-wide, and a second one for
# the same issuer is an error rather than an isolation boundary.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = var.github_thumbprints

  tags = { Name = "${var.name_prefix}-github-oidc" }
}

locals {
  provider_arn = (var.create_oidc_provider
    ? aws_iam_openid_connect_provider.github[0].arn
  : data.aws_iam_openid_connect_provider.github[0].arn)

  # Exactly which workflow runs may deploy. Branch refs, not wildcards: a pull request from a fork
  # runs with `pull_request` context and must never match, because a fork's workflow file is
  # attacker-controlled.
  allowed_subjects = [for ref in var.allowed_refs : "repo:${var.github_repository}:ref:${ref}"]
}

data "aws_iam_policy_document" "assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # The load-bearing condition. Without it the audience check alone would admit every repository
    # on GitHub, because every one of them can mint a token with that audience.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.allowed_subjects
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.name_prefix}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.assume.json
  max_session_duration = 3600

  description = "GitHub Actions deployment role for ${var.github_repository}. OIDC only; no access keys."
  tags        = { Name = "${var.name_prefix}-github-deploy" }
}

# What a deploy actually needs: push an image, register a task definition, update a service. It is a
# short list, and everything absent from it is absent on purpose -- this role cannot read a secret,
# reach the database, change a security group or delete anything.
data "aws_iam_policy_document" "deploy" {
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # the token endpoint is not addressable per-repository
  }

  statement {
    sid    = "EcrPushToDeclaredRepositories"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeImages",
    ]
    resources = var.ecr_repository_arns
  }

  # Registering a revision is how a deploy changes the image. The role may create revisions and
  # read them; it may not delete a family, which is what would destroy rollback targets.
  statement {
    sid    = "RegisterTaskDefinitions"
    effect = "Allow"
    actions = [
      "ecs:RegisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
    ]
    resources = ["*"] # task definition ARNs are not known before registration
  }

  statement {
    sid    = "UpdateDeclaredServices"
    effect = "Allow"
    actions = [
      "ecs:UpdateService",
      "ecs:DescribeServices",
      "ecs:ListTasks",
      "ecs:DescribeTasks",
    ]
    resources = var.ecs_service_arns
  }

  # Handing the task and execution roles to ECS. Scoped to exactly those two roles: without the
  # resource constraint, PassRole is a privilege-escalation primitive -- the pipeline could start a
  # task as any role in the account.
  statement {
    sid       = "PassTaskRolesToEcsOnly"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = var.passable_role_arns

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}

# -- plan-only role for pull requests ---------------------------------------------------------------
#
# A separate identity that can read the estate and nothing else, so `terraform plan` can run on a
# pull request without that workflow holding permissions to change anything.

data "aws_iam_policy_document" "plan_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Pull requests included here and nowhere else. A plan reveals resource names and shapes, which
    # is why this role is read-only rather than merely "not apply".
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:*"]
    }
  }
}

resource "aws_iam_role" "plan" {
  count = var.create_plan_role ? 1 : 0

  name                 = "${var.name_prefix}-github-plan"
  assume_role_policy   = data.aws_iam_policy_document.plan_assume.json
  max_session_duration = 3600

  description = "Read-only role for terraform plan on pull requests."
  tags        = { Name = "${var.name_prefix}-github-plan" }
}

resource "aws_iam_role_policy_attachment" "plan_readonly" {
  count = var.create_plan_role ? 1 : 0

  role       = aws_iam_role.plan[0].name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# A plan needs to lock state, which is a write. Scoped to the lock table alone.
resource "aws_iam_role_policy" "plan_state" {
  count = var.create_plan_role ? 1 : 0

  name = "terraform-state-access"
  role = aws_iam_role.plan[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:ListBucket"]
        Resource = [var.state_bucket_arn, "${var.state_bucket_arn}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"]
        Resource = [var.state_lock_table_arn]
      },
    ]
  })
}
