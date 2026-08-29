# Three roles, and the distinction between two of them is the point.
#
# The EXECUTION role belongs to the ECS agent: it pulls the image, resolves secrets and writes logs,
# all before the container starts. The TASK role belongs to the running process. Conflating them is
# the common mistake, and it hands the application every permission the agent needs -- including
# reading every secret in the environment, forever, from inside a process that reaches the internet.

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  description        = "ECS agent: image pull, secret resolution, log writes. Not the application."
}

# The AWS-managed policy covers ECR pull and log writes and nothing else. Using it rather than
# hand-rolling those permissions means they track AWS' own changes.
resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Secret access is scoped to the exact ARNs this environment declares. Not "secretsmanager:*", not
# a path wildcard: an enumerated list, so a secret added for another purpose is not readable here
# until someone says so.
data "aws_iam_policy_document" "execution_secrets" {
  statement {
    sid       = "ReadDeclaredSecrets"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.all_secret_arns
  }

  statement {
    sid       = "DecryptWithSecretsKey"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [var.secrets_kms_key_arn]

    # Decrypt only in the course of resolving a secret. The role cannot use the key for anything
    # else it might be handed.
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-declared-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# -- task roles: what the running processes may do --------------------------------------------------
#
# Both are deliberately near-empty. Neither application calls an AWS API: the platform talks to
# PostgreSQL and the AI plane, the AI plane talks to a model provider. Their credentials arrive as
# environment variables from the execution role, so the processes themselves need no AWS identity
# at all beyond what makes them debuggable.

resource "aws_iam_role" "platform_task" {
  name               = "${var.name_prefix}-platform-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  description        = "The Spring platform process. Holds no AWS permissions beyond ECS Exec."
}

resource "aws_iam_role" "ai_task" {
  name               = "${var.name_prefix}-ai-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  description        = "The AI plane process. Holds no data-plane permissions of any kind."
}

# ECS Exec, for getting a shell into a task during DEV bring-up. The SSM actions are the whole of
# it; there is no s3, no secretsmanager, no rds. Gated so a future environment can withhold it
# without editing policy.
data "aws_iam_policy_document" "task_exec" {
  count = var.enable_ecs_exec ? 1 : 0

  statement {
    sid    = "EcsExecChannel"
    effect = "Allow"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"] # session channels are not addressable resources
  }
}

resource "aws_iam_role_policy" "platform_exec" {
  count  = var.enable_ecs_exec ? 1 : 0
  name   = "ecs-exec"
  role   = aws_iam_role.platform_task.id
  policy = data.aws_iam_policy_document.task_exec[0].json
}

resource "aws_iam_role_policy" "ai_exec" {
  count  = var.enable_ecs_exec ? 1 : 0
  name   = "ecs-exec"
  role   = aws_iam_role.ai_task.id
  policy = data.aws_iam_policy_document.task_exec[0].json
}
