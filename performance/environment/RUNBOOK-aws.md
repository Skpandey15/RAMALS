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

`terraform output next_steps` then prints the whole sequence below with the real addresses filled in.

State is gitignored. It records what exists right now, which a commit cannot keep true, and it can
carry values that were never meant to be published.

## Sequence

If you created the instances by hand instead, the same sequence applies — use Ubuntu LTS x86_64 and
substitute your own addresses:

```bash
# 1. copy the harness to both
scp -r performance ubuntu@<sut>:~/
scp -r performance ubuntu@<loadgen>:~/

# 2. prepare each host
ssh ubuntu@<sut>     'bash performance/environment/provision-sut.sh'
ssh ubuntu@<loadgen> 'bash performance/environment/provision-loadgen.sh'
```

`provision-sut.sh` checks capacity **before installing anything** and refuses a host that is too
small, so a wrongly sized instance costs a few seconds rather than a full setup.

```bash
# 3. bring the stack up on the SUT, pinned to the spec's resources
ssh ubuntu@<sut> 'docker compose -f deploy/compose.deploy.yml \
    -f performance/compose.perf-fixed.yml up -d'

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
  export RAMALS_KEYCLOAK_ADMIN=admin RAMALS_KEYCLOAK_ADMIN_PASSWORD=...
  export RAMALS_LOAD_PASSWORD=...
  ./performance/run-baseline.sh mixed-learning
```

Use the **private** IP. The public path leaves the VPC and comes back.

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
