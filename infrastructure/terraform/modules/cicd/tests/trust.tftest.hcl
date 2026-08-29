# The OIDC trust subjects, asserted exactly.
#
# These run with a mocked AWS provider and need no account, no credentials and no network, so they
# gate every pull request the same way `validate` does. What they check is the one thing review is
# worst at: an IAM trust condition that reads as scoped and is not.
#
#   terraform test -chdir=infrastructure/terraform/modules/cicd

mock_provider "aws" {
  # The policy documents themselves are mocked with valid-but-empty JSON. These tests assert the
  # *subjects*, which are computed from locals and therefore real; rendering the surrounding IAM
  # document is the provider's job and is not what is under test here. Without this the mock returns
  # a placeholder string and aws_iam_role_policy rejects it as malformed.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

variables {
  name_prefix          = "ramals-dev"
  github_repository    = "Skpandey15/RAMALS"
  ecr_repository_arns  = ["arn:aws:ecr:ap-south-1:111122223333:repository/ramals-dev/learning-platform"]
  ecs_service_arns     = ["arn:aws:ecs:ap-south-1:111122223333:service/ramals-dev-cluster/ramals-dev-learning-platform"]
  passable_role_arns   = ["arn:aws:iam::111122223333:role/ramals-dev-ecs-execution"]
  state_bucket_arn     = "arn:aws:s3:::ramals-tfstate-111122223333"
  state_lock_table_arn = "arn:aws:dynamodb:ap-south-1:111122223333:table/ramals-tfstate-locks"
}

run "deploy_trusts_main_branch_and_dev_environment" {
  command = plan

  # The two forms GitHub actually mints for a deploy job. The environment form is not speculative:
  # adding `environment: dev` to the workflow switches the token to it and away from the ref form,
  # so a policy carrying only the ref would break the pipeline the day someone adds an approval gate.
  assert {
    condition = contains(
      output.deploy_trust_subjects, "repo:Skpandey15/RAMALS:ref:refs/heads/main"
    )
    error_message = "The deploy role must trust a push to main."
  }

  assert {
    condition = contains(
      output.deploy_trust_subjects, "repo:Skpandey15/RAMALS:environment:dev"
    )
    error_message = "The deploy role must trust the dev GitHub Environment."
  }

  # Exactly two. An extra subject is trust nobody asked for.
  assert {
    condition     = length(output.deploy_trust_subjects) == 2
    error_message = "The deploy role trusts more subjects than the two intended."
  }
}

run "plan_trusts_pull_requests_only" {
  command = plan

  assert {
    condition     = output.plan_trust_subjects == ["repo:Skpandey15/RAMALS:pull_request"]
    error_message = "The plan role must trust pull requests and nothing else."
  }
}

run "no_subject_is_wildcarded" {
  command = plan

  # The regression this file exists for. `repo:OWNER/REPO:*` is repository-scoped and still admits
  # every branch, tag, environment and pull_request_target run.
  assert {
    condition = alltrue([
      for subject in concat(output.deploy_trust_subjects, output.plan_trust_subjects) :
      !strcontains(subject, "*")
    ])
    error_message = "OIDC trust subjects must never contain a wildcard."
  }

  # Every subject names this repository. A subject that does not is trust extended to another one.
  assert {
    condition = alltrue([
      for subject in concat(output.deploy_trust_subjects, output.plan_trust_subjects) :
      startswith(subject, "repo:Skpandey15/RAMALS:")
    ])
    error_message = "Every trust subject must be scoped to this repository."
  }
}

run "a_wildcard_ref_is_refused" {
  command = plan

  variables {
    allowed_refs = ["refs/heads/*"]
  }

  # The precondition must reject this rather than render it. A module that quietly accepts a
  # wildcard would make every assertion above a statement about the default values only.
  expect_failures = [terraform_data.subject_guard]
}

run "an_empty_deploy_list_is_refused" {
  command = plan

  variables {
    allowed_refs         = []
    allowed_environments = []
  }

  # Fails closed and says why, rather than rendering an unsatisfiable condition that surfaces later
  # as an opaque AssumeRole denial.
  expect_failures = [terraform_data.subject_guard]
}
