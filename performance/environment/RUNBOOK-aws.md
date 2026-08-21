# Provisioning `perf-standard-01` on AWS

The one provider-specific step. Everything after it —
[`provision-sut.sh`](provision-sut.sh), [`provision-loadgen.sh`](provision-loadgen.sh),
[`attest.py`](attest.py) — runs unchanged on any fresh Ubuntu LTS x86_64 box, so moving off AWS
later costs this page and nothing else.

## Do not use a burstable instance

This is the one that silently ruins a performance baseline, so it comes first.

The `t2`/`t3`/`t3a`/`t4g` families are **credit-based**. They deliver full CPU only while credits
last, then throttle to a baseline fraction — 20–40% of a vCPU on the smaller sizes. A run that
starts with a full credit balance and one that starts empty produce different numbers on the same
instance, the same commit and the same load, and nothing in the result says which happened.

The attestation cannot catch this. Credits are invisible from inside the instance: `nproc` reports
the full vCPU count either way. It would certify a conforming host and the number would still be
unrepeatable.

Use a fixed-performance family — `m6i`, `m7i`, `c6i`, `c7i`.

## The two instances

| | Instance | Why |
| --- | --- | --- |
| **System under test** | `m6i.2xlarge` — 8 vCPU, 32 GiB | Meets the spec's 8 CPU / 16 GiB with room above it. The spec pins the containers to 6 CPU and 6 GiB between them; the rest is the headroom that makes those limits, rather than contention, decide the result |
| **Load generator** | `c6i.xlarge` — 4 vCPU, 8 GiB | k6 at the documented 60 rps mix is comfortable here. It only has to avoid becoming the bottleneck |

`c6i.2xlarge` (8 vCPU, 16 GiB) is the smallest instance that conforms. It leaves the SUT with 2 CPU
and 10 GiB above the container limits, which is thin once the kernel, the container runtime and
Postgres' own I/O threads are accounted for. Prefer `m6i.2xlarge` unless cost forces otherwise.

**An EC2 vCPU is a hyperthread, not a core.** 8 vCPU is 4 physical cores. That does not stop the
baseline being valid — it is the same on every conforming instance — but it is why the attestation
records the CPU model: two baselines are only strictly comparable when they share one.

## Storage, which the attestation does not check

Postgres I/O affects every write-path number in the mix, and nothing in `attest.py` can see what
kind of disk it is running on. Treat this as a manual item.

Use **gp3** and raise IOPS above the 3000 default — gp3 baseline throughput is a plausible
bottleneck for the assessment-write class, and worse, a variable one. 100 GiB at 6000 IOPS and
250 MB/s is ample and cheap. Do not use `gp2`, whose IOPS scale with volume size, or instance
store, which does not survive a stop.

Record what you chose; it belongs in the baseline's provenance even though the tooling cannot
enforce it.

## Networking

Both instances in the **same subnet and the same availability zone**. Cross-AZ adds roughly a
millisecond each way, which is a material fraction of the 250 ms `skill_map_read` budget and would
be measuring the network.

Security groups, rather than opening ports to the world:

- `ramals-perf-loadgen` — no inbound beyond your SSH access.
- `ramals-perf-sut` — inbound `8080` and `8081` **from the `ramals-perf-loadgen` group only**, plus
  your SSH access. The load fixtures provision Keycloak users, so `8081` reachable from anywhere is
  an open door onto an identity provider with known test credentials.

A cluster placement group tightens latency further and is worth setting if the numbers look
network-bound.

## Creating them

[`terraform/`](terraform/) declares both instances, the security groups and the storage. Prefer it
over console clicks or `aws ec2 run-instances`, for three reasons:

- **It enforces what the attestation cannot.** The burstable rule above is a `validation` block, so
  a `t`-family instance type is refused at plan time rather than discovered as unrepeatable numbers.
- **`terraform destroy` removes the security groups too.** Imperative creation reliably leaves them
  behind, and they are the part nobody remembers.
- **A reference environment has to be re-creatable.** That is what the word means: when the instance
  is replaced, the replacement must be identical. A runbook of commands drifts; a module does not.

```bash
cd performance/environment/terraform
cp terraform.tfvars.example terraform.tfvars   # fill in region, subnet, key pair, your IP
terraform init
terraform validate
terraform plan                                  # read it: both instances bill by the hour
terraform apply
```

### `aws login` credentials are invisible to Terraform

If `terraform plan` fails with **"No valid credential sources found"** and **"no EC2 IMDS role
found"** while the AWS CLI works perfectly, this is why: AWS CLI v2's `aws login` stores credentials
in a session cache the CLI understands and the Terraform provider does not. `aws configure list`
shows their type as `login` rather than a file, and `~/.aws/config` carries a `login_session` line
instead of a profile the SDK can resolve.

Export them for the run:

```bash
# PowerShell
aws configure export-credentials --format env-no-export | ForEach-Object {
  if ($_ -match '^([A-Z_]+)=(.*)$') { Set-Item -Path "env:$($matches[1])" -Value $matches[2] }
}

# bash
eval "$(aws configure export-credentials --format env)"
```

They are session credentials and expire, so this is a per-shell step rather than something to put in
a file.

`terraform output next_steps` then prints the whole sequence below with the real addresses filled in.

State is gitignored. It records what exists right now, which a commit cannot keep true, and it can
carry values that were never meant to be published.

## Sequence

If you created the instances by hand instead, the same sequence applies — use Ubuntu LTS x86_64 and
substitute your own addresses:

```bash
# 1. copy what the SUT needs -- the harness, the deploy topology, and the image build contexts.
#    Not 'scp -r performance': after terraform init that tree carries the AWS provider and is
#    over 600 MB, none of which either host uses. And performance/ alone is not enough: the
#    compose builds postgres and keycloak from infrastructure/, so those must be present too.
git archive HEAD performance deploy infrastructure | ssh ubuntu@<sut> 'tar -x'
git archive HEAD performance | ssh ubuntu@<loadgen> 'tar -x'   # the generator only runs k6

# 2. prepare each host
ssh ubuntu@<sut>     'bash performance/environment/provision-sut.sh'
ssh ubuntu@<loadgen> 'bash performance/environment/provision-loadgen.sh'
```

`provision-sut.sh` checks capacity **before installing anything** and refuses a host that is too
small, so a wrongly sized instance costs a few seconds rather than a full setup.

### The stack needs an .env, and builds two of its four images

`deploy/compose.deploy.yml` declares twelve variables with `:?Set X`, so it refuses to start until
every one is present -- database and Keycloak credentials among them. Copy `.env.example` on the SUT
and fill it in before step 3; there is no default that works.

`postgres` and `keycloak` are **built** on the host from `infrastructure/docker/`, not pulled. The
Keycloak build runs an `--optimized` augmentation step and is the slowest part of bringing the stack
up -- budget ten to twenty minutes for step 3 the first time. `learning-platform` and `web-ui` are
pulled by digest and are public, so no registry login is needed.

```bash
# 3. bring the stack up on the SUT, pinned to the spec's resources and bound only to its private IP
ssh ubuntu@<sut> 'RAMALS_PERF_SUT_BIND_ADDRESS=<sut-private-ip> \
  RAMALS_PULL_CMD="docker compose --env-file deploy/.env \
    -f deploy/compose.deploy.yml \
    -f performance/compose.perf-fixed.yml \
    -f performance/compose.perf-two-host.yml pull" \
  RAMALS_UP_CMD="docker compose --env-file deploy/.env \
    -f deploy/compose.deploy.yml \
    -f performance/compose.perf-fixed.yml \
    -f performance/compose.perf-two-host.yml up -d" \
  RAMALS_HEALTH_CMD="bash deploy/health-gates.sh" \
  bash deploy/deploy-controller.sh'

# 4. the SUT attests itself — this must exit 0
ssh ubuntu@<sut> 'python3 performance/environment/attest.py --require \
    --load-generator-off-host --out /tmp/attestation.json'
```

Step 4 is the gate. Until it exits 0 the environment is not registered, and any run made against it
is recorded as `local-unqualified` no matter what `RAMALS_PERF_ENV` says.

```bash
# 5. carry the attestation to the load generator and run from there
scp ubuntu@<sut>:/tmp/attestation.json ./attestation.json
scp ./attestation.json ubuntu@<loadgen>:~/attestation.json

ssh ubuntu@<loadgen>
  export RAMALS_BASE_URL=http://<sut-private-ip>:8080
  export RAMALS_TOKEN_URL=http://<sut-private-ip>:8081/realms/ramals/protocol/openid-connect/token
  export RAMALS_PERF_ENV=perf-standard-01
  export RAMALS_PERF_ATTESTATION=~/attestation.json
  export RAMALS_PERF_LOAD_GENERATOR_OFF_HOST=true
  # Both values below must match the SUT's deploy/.env exactly. RAMALS_KEYCLOAK_ADMIN is whatever
  # was put there when the environment was built -- it is NOT necessarily 'admin', and a mismatch
  # fails fixture provisioning after the environment has already been provisioned and attested.
  export RAMALS_KEYCLOAK_ADMIN=<the value in the SUT's deploy/.env>
  export RAMALS_KEYCLOAK_ADMIN_PASSWORD=<the value in the SUT's deploy/.env>
  # A run-scoped password for the simulated learners. It is not part of the deployment contract and
  # has no entry in deploy/.env -- provision-load-fixtures.py sets it on the users it creates and
  # k6 authenticates with it, so it only has to be consistent within one run. Generate a fresh one:
  #   export RAMALS_LOAD_PASSWORD="$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')"
  export RAMALS_LOAD_PASSWORD=<generated, see above>

  # Prove the setup path before committing to a measured run. This provisions fixtures, acquires the
  # full learner token pool exactly as the scenarios do, restores the realm, and measures nothing.
  ./performance/preflight-r1.sh
  ./performance/run-baseline.sh mixed-learning
```

Use the **private** IP. The public path leaves the VPC and comes back.

`RAMALS_KEYCLOAK_URL` is deliberately absent from that list. `fixtures.sh` derives it from
`RAMALS_TOKEN_URL`, because the two used to be supplied separately and only one of them ever was:
the fixtures fell back to the in-network default `http://keycloak:8080`, which resolves on the SUT
and on no other machine, so provisioning failed on a hostname while k6 held a working address for
the same server. Set it explicitly only if the admin API lives somewhere other than the issuer.

### Two credentials have to cross to the load generator

`deploy/.env` is generated on the SUT and stays there, with one unavoidable exception: fixture
provisioning runs on the **load generator**, and it drives the Keycloak admin API. So
`RAMALS_KEYCLOAK_ADMIN` and `RAMALS_KEYCLOAK_ADMIN_PASSWORD` must be present in that shell too.

Type them into the interactive session rather than writing a second `.env`, and do not pass them on
a command line — arguments are visible in `/proc` to every user on the box. They are destroyed with
the instances; they are valid nowhere else and are never reused between environments.

### Before the measured run, check the network contract

The canonical topology publishes on loopback and the runbook targets the private interface;
`compose.perf-two-host.yml` is what reconciles them. Run the two together before spending a run:

```bash
performance/environment/check-two-host-network.sh \
  --sut-public <ip> --sut-private <ip> --loadgen-public <ip> --key ~/.ssh/<key>.pem
```

It asserts that the load generator reaches backend and Keycloak over the private interface, that
neither port answers publicly, and that `compose.deploy.yml` still binds loopback only. Add
`--prove-guard` to also confirm that removing the override genuinely breaks the path — without that,
a passing connectivity check could be measuring the security group rather than the binding. It
restarts the stack twice, so run it before attestation rather than after.

The two-host override requires the private bind address and replaces, rather than implicitly merges
with, the canonical port list. It explicitly retains loopback for local health gates and adds only
the SUT private interface for the load generator. Verify the resulting bindings with
`docker compose ps` or `docker inspect`: they must name `127.0.0.1` and `<sut-private-ip>`, never
`0.0.0.0`. AWS ingress for both ports remains restricted to the load-generator security group.

The runner re-checks the carried attestation — right spec, records conformance, no older than 24
hours — rather than taking it at its word.

## What to send back

The output of step 4, and the baseline JSON from step 5. Between them they say which machine this
was, that it conformed, and what it measured.

The spec is `status: proposed`: its container limits are reasoned from the MVP-0 workstation run,
not measured. If the first calibrated run shows 4 CPU is the wrong size for the backend, **the spec
changes** — a number explained away against a spec nobody revisits is how a baseline stops meaning
anything.

## Cost

Both instances only need to exist for a run. Stop them between baselines; an idle `m6i.2xlarge` and
`c6i.xlarge` left running are the sort of thing that quietly funds itself into a monthly bill.

Stopping and starting preserves the EBS volume, and the attestation is re-run each time anyway —
`attest.py` reads the host as it is, so a restarted instance re-proves itself rather than relying on
having conformed once.
