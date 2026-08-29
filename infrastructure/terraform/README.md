# RAMALS infrastructure as code

Terraform for the AWS DEV foundation. Architecture, security boundaries and cost notes are in
[`docs/architecture/aws-dev-foundation.md`](../../docs/architecture/aws-dev-foundation.md).

**Nothing here has been applied.** `fmt` and `validate` pass; `plan` and `apply` need an account.

## Order of operations

```bash
# 1. Remote state. Runs once, with local state, because it creates the remote state it would use.
cd infrastructure/terraform/bootstrap
terraform init && terraform apply
terraform output state_bucket        # put this into environments/dev/backend.tf

# 2. The environment.
cd ../environments/dev
terraform init
terraform plan     # read it
terraform apply

# 3. Secret values, once, out of band. Terraform never holds them.
aws secretsmanager put-secret-value --region ap-south-1 \
  --secret-id ramals-dev/db-app-password --secret-string '...'
```

`alb_ingress_cidrs` defaults to empty, so a first apply produces a load balancer nothing can reach.
That is intentional — opening a DEV environment should be a line someone wrote.

## What each directory is

| Path | Purpose |
|---|---|
| `bootstrap/` | S3 state bucket + DynamoDB lock table. Local state, run once. |
| `modules/network/` | VPC, public/private subnets, one NAT, S3 endpoint, reject flow logs |
| `modules/security/` | The four security groups. The architecture invariants, as network rules. |
| `modules/secrets/` | KMS keys and secret *containers*. Never values. |
| `modules/registry/` | ECR, immutable tags, scan on push |
| `modules/data/` | RDS PostgreSQL, private, encrypted, AWS-managed master password |
| `modules/compute/` | ALB, ECS cluster, task definitions, services, IAM roles |
| `modules/observability/` | Log groups and three alarms that mean something |
| `modules/cicd/` | GitHub OIDC provider, deploy role, read-only plan role |
| `environments/dev/` | Wires the modules once |

## Rules this code holds

- **No secret value in Terraform.** There is no `aws_secretsmanager_secret_version` resource, and CI
  fails if one appears.
- **The database is never public**, and the AI plane has no security group rule reaching it.
- **Contract B is off**, stated explicitly in the task definition and asserted in CI.
- **Immutable ECR tags**, so a rollback lands on the content it did before.
