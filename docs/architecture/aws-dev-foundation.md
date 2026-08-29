# AWS DEV foundation

- **Scope:** the minimum production-grade AWS foundation needed to deploy and qualify RAMALS in a
  development environment. Region `ap-south-1`.
- **Not in scope:** production HA, EKS, learner traffic, Contract B activation, provider
  qualification.
- **Status:** infrastructure as code only. Nothing in this document has been applied to an AWS
  account — see [What has not happened](#what-has-not-happened).

## Shape

```
                          Internet
                              │
                              ▼
                    ┌───────────────────┐
        PUBLIC      │        ALB        │  :80 → :443
        SUBNETS     │  sg: alb          │  ingress: alb_ingress_cidrs (default: none)
        (2 AZ)      └─────────┬─────────┘
                              │  only egress rule: → platform:8080
                              ▼
   ─────────────────────────────────────────────────────────────────────
                    ┌───────────────────┐
        PRIVATE     │  learning-platform│  ECS Fargate · :8080
        SUBNETS     │  sg: platform     │  /actuator/health
        (2 AZ)      └──┬─────────────┬──┘
                       │             │
        ┌──────────────┘             └──────────────┐
        │ :8000                                     │ :5432
        ▼                                           ▼
  ┌─────────────┐                            ┌─────────────┐
  │  ramals-ai  │  ECS Fargate               │     RDS     │  PostgreSQL 17
  │  sg: ai     │  no target group           │ sg: database│  publicly_accessible = false
  │  /health/*  │  no listener               │             │  single-AZ
  └──────┬──────┘  Cloud Map: ai.<env>.internal└────────────┘
         │                                          ▲
         │ :443 → model providers                   │
         ▼                                     ✗ NO RULE ✗
    NAT → Internet                    the AI plane has no path here

   ─────────────────────────────────────────────────────────────────────
   Supporting: ECR (immutable tags) · Secrets Manager + 2 KMS keys ·
               CloudWatch logs + 3 alarms · GitHub OIDC roles
```

## Security boundaries

Four security groups, and the interesting parts are the rules that **do not exist**.

| From → To | Allowed | Why it matters |
|---|---|---|
| Internet → ALB | `:443`, `:80` from `alb_ingress_cidrs` | Defaults to **empty** — an inert load balancer, not an open one |
| ALB → platform | `:8080` | The ALB's *only* egress rule |
| ALB → AI plane | **none** | The AI plane is not internet-reachable at any port |
| platform → AI plane | `:8000` | The only inter-plane path, one direction |
| platform → database | `:5432` | The only database ingress |
| **AI plane → database** | **none** | M2-ADR-017 §1, enforced by the network |
| database → anywhere | **none** | A database has no reason to originate a connection |

**The AI plane cannot reach the authoritative database.** Not "is configured not to" — there is no
security group rule permitting it, so a connection attempt fails at the network. That invariant is
already asserted by a unit test (`test_no_database_access.py`, no driver present) and recorded in an
ADR; here it is a third, independent enforcement that does not depend on either.

Rules reference **security groups, not CIDRs**, so they keep holding as Fargate tasks come and go
with new addresses, and cannot be widened by an out-of-date address list.

## Terraform layout

```
infrastructure/terraform/
├── bootstrap/              S3 state bucket + DynamoDB lock table. Runs once, local state.
├── modules/
│   ├── network/            VPC, 2×public + 2×private subnets, 1 NAT, S3 endpoint, reject flow logs
│   ├── security/           The four security groups above
│   ├── secrets/            2 KMS keys + 5 secret containers (never values)
│   ├── registry/           2 ECR repos, immutable tags, scan on push, lifecycle policy
│   ├── data/               RDS PostgreSQL 17, private, encrypted, managed master password
│   ├── compute/            ALB, ECS cluster, 2 task definitions, 2 services, 3 IAM roles
│   ├── observability/      Log groups + 3 alarms
│   └── cicd/               GitHub OIDC provider, deploy role, read-only plan role
└── environments/dev/       Wires the modules once. One directory per environment, not workspaces.
```

**Directories, not workspaces.** Workspaces share one configuration and differ only by variable
values, which makes "what is deployed to dev" a question about which workspace was selected rather
than a file anyone can read.

## Remote state

`bootstrap/` creates the S3 bucket and DynamoDB lock table, and is the one configuration that cannot
use remote state because it creates it. It runs once with local state that is committed nowhere.

The bucket is versioned (a corrupted state file is recovered by rolling back an object version),
encrypted, public access blocked, and `prevent_destroy` — destroying it would orphan every resource
it tracks. The lock table prevents two applies interleaving writes and producing a state file
describing an estate that never existed.

```bash
cd infrastructure/terraform/bootstrap
terraform init && terraform apply
terraform output -raw state_bucket        # ramals-tfstate-<account-id>
```

### Initialising DEV against that state

`environments/dev/backend.tf` is a **partial** backend: `key`, `region`, `dynamodb_table` and
`encrypt` are the same on every machine and are committed; `bucket` is not, because its name embeds
the AWS account id. So `bucket` is supplied at init time, and `init` fails rather than guessing if
it is missing.

Locally, once per checkout:

```bash
cd infrastructure/terraform/environments/dev
cp backend.hcl.example backend.hcl
# set bucket = the state_bucket output above; backend.hcl is gitignored
terraform init -backend-config=backend.hcl
```

In CI there is no file to copy, and none is needed — the name is derivable from the account the
workflow has already assumed a role into, which makes it deterministic rather than configured:

```bash
terraform init -input=false \
  -backend-config="bucket=ramals-tfstate-$(aws sts get-caller-identity --query Account --output text)"
```

That form takes no repository variable and no secret, so the plan and deploy jobs cannot drift from
each other or from a developer's machine: all three resolve the same bucket from the same account.
A workflow that assumed the wrong account would fail to find the state rather than quietly
initialising a second one.

If `init` reports a backend change after switching to this layout, `-reconfigure` re-reads the
backend block without attempting to migrate state.

## Secret injection

**No secret value passes through Terraform.** The `secrets` module creates *containers* — a secret
whose value is in a `.tf` file is in git, in every plan output and in state, so the only way for a
credential not to leak through Terraform is for Terraform never to have held it. There is no
`aws_secretsmanager_secret_version` resource anywhere in this repository, and that absence is the
control.

Values are written once, out of band:

```bash
aws secretsmanager put-secret-value --secret-id ramals-dev/provider-api-key \
  --secret-string "$(read -rs KEY && echo "$KEY")" --region ap-south-1
```

At runtime ECS resolves each ARN using the **execution** role and hands the value to the container
as an environment variable. The value never appears in the task definition, in state, or in the
console.

The RDS master password is AWS-managed (`manage_master_user_password`) and never enters Terraform at
all.

## IAM: three roles, and one distinction that matters

| Role | Holder | Permissions |
|---|---|---|
| **Execution** | the ECS *agent*, before the container starts | ECR pull, log writes, `GetSecretValue` on an **enumerated list** of ARNs, `kms:Decrypt` conditioned on `ViaService = secretsmanager` |
| **Platform task** | the running Spring process | ECS Exec only |
| **AI task** | the running Python process | ECS Exec only |

Conflating execution and task roles is the common mistake: it hands the application every permission
the agent needs — including reading every secret in the environment, forever, from a process that
reaches the internet.

Both task roles are near-empty because neither application calls an AWS API. Their credentials
arrive as environment variables; they need no AWS identity of their own.

## GitHub OIDC

No access keys. GitHub presents a signed token; AWS trades it for a session that expires in an hour.

**The trust condition is where this is either safe or worthless.** Matching only the audience — or
`repo:*` — lets *any* GitHub repository assume the role, because every one of them can mint a token
with that audience. The deploy role pins `repo:<owner>/<repo>:ref:refs/heads/main`.

### The exact `sub` claims

GitHub mints one of these per run, and the environment form **replaces** the ref form rather than
accompanying it — a deploy job that gains an `environment:` key stops matching a ref-only policy:

| Trigger | `sub` claim | Trusted by |
| --- | --- | --- |
| push to `main`, no environment | `repo:OWNER/REPO:ref:refs/heads/main` | deploy role |
| job declaring `environment: dev` | `repo:OWNER/REPO:environment:dev` | deploy role |
| pull request from a branch in this repo | `repo:OWNER/REPO:pull_request` | plan role |

All matched with **`StringEquals` over an enumerated list**. No wildcards: `repo:OWNER/REPO:*` reads
as repository-scoped and admits every branch, tag, environment and `pull_request_target` run.

A pull request from a **fork** never reaches either role — GitHub refuses `id-token: write` to fork
pull requests, so no token exists to present.

> **Required manual step.** `repo:OWNER/REPO:environment:dev` encodes **no branch**. It is minted
> for that environment from any ref, so the branch restriction must come from the GitHub
> Environment's own **deployment branch policy**. Configure `dev` to allow only `main` before using
> an environment-gated deploy; leaving it unset is the one way to widen this trust without editing
> Terraform.

The deploy role can push images, register task definitions and update the two named services. It
**cannot** read a secret, reach the database, change a security group, or delete anything.
`iam:PassRole` is scoped to exactly the three task roles and conditioned on
`PassedToService = ecs-tasks.amazonaws.com` — unscoped, it is a privilege-escalation primitive.

A separate **read-only plan role** lets pull requests run `terraform plan` without holding any
permission to change anything.

Trust is asserted by `terraform test` (`modules/cicd/tests/trust.tftest.hcl`, mocked provider, no
account needed): the two deploy subjects and the one plan subject exactly, no wildcard in any
subject, every subject scoped to this repository, and two fail-closed cases — a wildcard ref and an
empty allow-list are both refused by a module precondition rather than rendered.

## Health checks and rollback

**Port 80 always redirects to 443 and never serves.** An earlier revision forwarded to the target
group when no certificate existed, so the environment was reachable during bring-up; that traded the
one property a public listener should not trade, and a scanner caught it pointing at the forward
action. With no certificate the environment is now simply unreachable, which is the same fail-closed
posture as `alb_ingress_cidrs` defaulting to empty.

Two layers, answering different questions:

- **Target group** → *should traffic go here?* `/actuator/health` on the platform. Spring's actuator
  reports unhealthy while Flyway migrates, which is exactly when traffic must not arrive.
- **Container** → *should ECS replace this task?* `startPeriod` 120s on the platform so migrations
  do not count as failures; 30s on the AI plane.

**Rollback is automatic.** Both services set `deployment_circuit_breaker { enable, rollback }`: a
deployment whose tasks fail health checks is rolled back to the last known-good task definition
without anyone being paged. Manual rollback is deterministic because ECR tags are **immutable** —
re-deploying a previous revision lands on exactly the content it did before.

`deployment_minimum_healthy_percent = 100` with `maximum = 200` means the new task must pass health
checks before the old one stops. There is no window with zero tasks serving.

## Cost-conscious defaults

Estimated **≈ $95–110/month** for `ap-south-1`, idle DEV:

| Item | Choice | ≈ $/mo |
|---|---|---|
| NAT Gateway | **one**, shared, single AZ | 32 |
| ALB | one, minimum LCU | 18 |
| RDS | `db.t4g.micro`, single-AZ, 20 GB gp3 | 15 |
| Fargate | 0.5 vCPU/1 GB + 0.25/0.5, **ARM64**, 1 task each | 20 |
| Secrets Manager | 5 secrets | 2 |
| KMS | 2 keys | 2 |
| CloudWatch | logs + 3 alarms | 5 |
| ECR, Cloud Map, flow logs | | 3 |

Deliberate economies, each with its production counterpart named: **one NAT** (three would be the
largest line item; an AZ failure takes egress with it), **single-AZ RDS** (multi-AZ roughly doubles
instance cost), **ARM64 Fargate** (materially cheaper, both images build for it), **no interface
VPC endpoints** (four would cost more than the NAT they would save data charges on — revisit at
production traffic), **Container Insights off**, **Performance Insights off**.

`FARGATE_SPOT` is deliberately **not** used: Spot reclaims with two minutes' notice, which a
reconciliation worker holding a lease on durable work cannot absorb.

An `EstimatedCharges` alarm at $150 guards the real DEV risk — something left running.

## Contract B in this environment

**Off, and stated explicitly in the task definition** rather than inherited from the image default,
so the deployed state is readable without decompiling a jar:

```
RAMALS_CONTRACT_B_ENABLED=false
RAMALS_CONTRACT_B_RECONCILIATION_ENABLED=false
RAMALS_CONTRACT_B_PURGE_ENABLED=false
RAMALS_AI_DURABLE_EXECUTION_ENABLED=false   # the durable router is not even mounted
RAMALS_AI_AI_ENABLED=false                  # no provider call, no spend
RAMALS_AI_MODEL_ROUTE=ci-fake
```

The `provider-api-key` secret container exists but is expected to hold a placeholder: the container
reads it at startup, and an empty secret is a clearer failure than a missing one. **No provider
credential is committed anywhere in this repository.**

### Proving it, and what does not prove it

There were two claims here and only one of them was evidence. A `contract_b_state` **output** used
to report `enabled/reconciliation/purge = false` — but it was a hardcoded literal in
`environments/dev/outputs.tf`, not read from the task definition it appeared to describe. It would
have kept printing `false` after someone set the flags to `true`, which makes it worse than absent:
a control that cannot fail is one people stop checking. It has been removed.

What actually holds, at three different times:

| When | Check | What it proves |
|---|---|---|
| Every push | `contract-b and secret guardrails` in `terraform-ci.yml` greps `modules/compute/main.tf` | The declaration says `false` |
| Plan | *nothing* | `container_definitions` is unknown at plan time — it embeds secret ARNs resolved on apply — so the flags cannot be read from a plan. Do not claim otherwise |
| **Post-apply** | `aws ecs describe-task-definition` | **The deployed runtime state.** This is the only runtime proof |

```bash
aws ecs describe-task-definition \
  --task-definition ramals-dev-learning-platform --region ap-south-1 \
  --query "taskDefinition.containerDefinitions[0].environment[?starts_with(name,'RAMALS_CONTRACT_B')]"

aws ecs describe-task-definition \
  --task-definition ramals-dev-ramals-ai --region ap-south-1 \
  --query "taskDefinition.containerDefinitions[0].environment[?name=='RAMALS_AI_DURABLE_EXECUTION_ENABLED']"
```

Run both after any apply that touches the compute module, and record the output in the activation
evidence. Contract B may not be activated in any environment until residual S2 is resolved and
separately reviewed (`docs/mvp2-contract-b-approval.md`).

`platform_desired_count` is **1**, and that is a correctness note rather than a resilience one:
the platform hosts the Contract B reconciliation worker, whose inspection budget is per process
(M2-ADR-020 §7). A second task would double the provider request rate against an organisation-wide
limit. Contract B is off, so the constraint is not yet live — this is the note that stops it being
breached silently when it is.

## Architecture invariants preserved

| Invariant | How this foundation holds it |
|---|---|
| Deterministic Spring core is authoritative | The platform is the only ALB target and the only principal with database access |
| AI plane is proposal/execution infrastructure only | No target group, no listener, no public path; reachable only from the platform |
| No direct AI access to the authoritative DB | **No security group rule exists**, and no database credential is injected into its task |
| Contract A semantics unchanged | No application code is touched by this change |

## What has not happened

- **Nothing has been applied.** No AWS account has been touched; `terraform apply` has not run.
  `validate` and `fmt` pass; `plan` requires credentials and a bootstrapped backend.
- **No domain or certificate.** `certificate_arn` is empty, so there is no HTTPS listener and port
  80's redirect leads nowhere — the environment is **unreachable** rather than reachable in the
  clear. Issuing a certificate is what makes it serve at all.
- **No identity provider.** `oidc_issuer_uri` is empty; Keycloak is not yet deployed to AWS, so the
  platform's own authentication cannot be exercised here yet.
- **`alb_ingress_cidrs` defaults to empty**, so a first apply produces a load balancer nothing can
  reach. That is intended: opening it should be a line someone wrote.
- **No production HA, no EKS, no learner traffic, no Contract B activation.**
