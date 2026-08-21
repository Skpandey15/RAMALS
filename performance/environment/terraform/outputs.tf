# The commands to run next, rather than raw ids somebody has to assemble by hand.
#
# The step order is the part that is easy to get wrong -- particularly that the attestation must run
# on the system under test and the baseline on the load generator, because attesting the machine k6
# runs on measures the wrong host and says nothing about the one being tested.

output "sut_public_ip" {
  description = "System under test, for SSH from the operator."
  value       = aws_instance.sut.public_ip
}

output "sut_private_ip" {
  description = <<-EOT
    System under test, for the load generator to target. Use this and not the public address: the
    public path leaves the VPC and comes back, and the run would be measuring that.
  EOT
  value       = aws_instance.sut.private_ip
}

output "loadgen_public_ip" {
  description = "Load generator, for SSH from the operator."
  value       = aws_instance.loadgen.public_ip
}

output "next_steps" {
  description = "The provisioning sequence, with the host each command runs against."
  value       = <<-EOT

    Everything below is driven from your own machine; nothing needs an interactive login.

    1. copy the required tracked deployment inputs to both hosts.
       Not 'scp -r performance': after terraform init that tree carries the AWS provider and is
       over 600 MB, none of which either host uses.
       The SUT also needs deploy/ and infrastructure/ because the canonical topology reads the
       desired manifest and builds the PostgreSQL and Keycloak infrastructure images locally.
       git archive HEAD performance deploy infrastructure | ssh ubuntu@${aws_instance.sut.public_ip} 'tar -x'
       git archive HEAD performance | ssh ubuntu@${aws_instance.loadgen.public_ip} 'tar -x'

    2. prepare each host
       ssh ubuntu@${aws_instance.sut.public_ip} 'bash performance/environment/provision-sut.sh'
       ssh ubuntu@${aws_instance.loadgen.public_ip} 'bash performance/environment/provision-loadgen.sh'

    3. bring the stack up on the SUT, pinned to the spec's resources and private interface
       ssh ubuntu@${aws_instance.sut.public_ip} 'RAMALS_PERF_SUT_BIND_ADDRESS=${aws_instance.sut.private_ip} \
         RAMALS_PULL_CMD="docker compose --env-file deploy/.env -f deploy/compose.deploy.yml -f performance/compose.perf-fixed.yml -f performance/compose.perf-two-host.yml pull" \
         RAMALS_UP_CMD="docker compose --env-file deploy/.env -f deploy/compose.deploy.yml -f performance/compose.perf-fixed.yml -f performance/compose.perf-two-host.yml up -d" \
         RAMALS_HEALTH_CMD="bash deploy/health-gates.sh" bash deploy/deploy-controller.sh'

    4. the SUT attests itself -- this must exit 0 before the environment is registered
       ssh ubuntu@${aws_instance.sut.public_ip} 'python3 performance/environment/attest.py --require --load-generator-off-host --out /tmp/attestation.json'

    5. carry the attestation to the load generator
       scp ubuntu@${aws_instance.sut.public_ip}:/tmp/attestation.json ./attestation.json
       scp ./attestation.json ubuntu@${aws_instance.loadgen.public_ip}:~/attestation.json

    6. run the baseline from the load generator, targeting the SUT's PRIVATE address
       ssh ubuntu@${aws_instance.loadgen.public_ip}
         export RAMALS_BASE_URL=http://${aws_instance.sut.private_ip}:8080
         export RAMALS_TOKEN_URL=http://${aws_instance.sut.private_ip}:8081/realms/ramals/protocol/openid-connect/token
         export RAMALS_PERF_ENV=perf-standard-01
         export RAMALS_PERF_ATTESTATION=~/attestation.json
         export RAMALS_PERF_LOAD_GENERATOR_OFF_HOST=true
         ./performance/run-baseline.sh mixed-learning

    Both instances bill by the hour. 'terraform destroy' removes them and the security groups.

  EOT
}
