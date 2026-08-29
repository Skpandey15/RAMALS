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

  # The `sub` claim GitHub actually mints, per trigger. Getting these exactly right matters more
  # than anything else in this file, because a subject that never matches locks the pipeline out and
  # one that matches too much is the whole security control gone:
  #
  #   push to a branch, no environment      repo:OWNER/REPO:ref:refs/heads/main
  #   job declaring `environment: dev`      repo:OWNER/REPO:environment:dev
  #   pull_request event                    repo:OWNER/REPO:pull_request
  #
  # The environment form REPLACES the ref form -- it does not accompany it. A deploy job that gains
  # an `environment:` key stops matching a ref-only trust policy and starts failing to assume the
  # role, which is why both forms are enumerated rather than only the one in use today.
  deploy_ref_subjects = [
    for ref in var.allowed_refs : "repo:${var.github_repository}:ref:${ref}"
  ]

  # Environment subjects carry NO branch information. `repo:R:environment:dev` is minted for a job
  # declaring that environment from *any* ref, so the branch restriction has to come from the
  # GitHub Environment's own deployment-branch policy. That is a required configuration step and is
  # recorded in docs/architecture/aws-dev-foundation.md; leaving it unset is the one way to widen
  # this trust without editing Terraform.
  deploy_environment_subjects = [
    for environment in var.allowed_environments :
    "repo:${var.github_repository}:environment:${environment}"
  ]

  deploy_subjects = concat(local.deploy_ref_subjects, local.deploy_environment_subjects)

  # The plan role's contexts, enumerated rather than wildcarded. `pull_request` is the subject for a
  # PR raised from a branch in this repository. A PR from a *fork* cannot reach here at all: GitHub
  # refuses `id-token: write` to fork pull requests, so no token is minted to present.
  plan_subjects = [
    for context in var.plan_contexts : "repo:${var.github_repository}:${context}"
  ]
}

# Fail closed rather than trust-nothing-by-accident. An empty subject list would render a condition
# with no values, which IAM treats as unsatisfiable -- safe, but it fails at apply time with a
# confusing error rather than here with a clear one.
resource "terraform_data" "subject_guard" {
  lifecycle {
    precondition {
      condition     = length(local.deploy_subjects) > 0
      error_message = "At least one deploy ref or environment must be allowed; an empty list locks the pipeline out."
    }

    precondition {
      condition     = length(local.plan_subjects) > 0 || !var.create_plan_role
      error_message = "The plan role needs at least one context, or set create_plan_role = false."
    }

    # The rule this module exists to hold. A `*` anywhere in a subject widens trust in a way that is
    # invisible in review -- `repo:owner/repo:*` reads as scoped and admits every branch, tag,
    # environment and pull_request_target run in the repository.
    precondition {
      condition = alltrue([
        for subject in concat(local.deploy_subjects, local.plan_subjects) :
        !strcontains(subject, "*")
      ])
      error_message = "OIDC trust subjects must not contain wildcards; enumerate refs, environments and contexts."
    }
  }
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
    #
    # StringEquals over an enumerated list, never StringLike: exact matching is what makes this
    # reviewable, and a wildcard here would be indistinguishable from correct at a glance.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.deploy_subjects
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

    # Was StringLike "repo:OWNER/REPO:*", which is repository-scoped and still far too wide: it
    # admits every branch, every tag, every environment, and any `pull_request_target` run -- the
    # last of which executes with base-repository permissions on attacker-authored content.
    #
    # Enumerated exactly, so the plan role can be assumed by a pull-request workflow and by nothing
    # else in the repository.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.plan_subjects
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
