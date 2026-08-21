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

    1. copy the harness to both hosts
       scp -r performance ubuntu@${aws_instance.sut.public_ip}:~/
       scp -r performance ubuntu@${aws_instance.loadgen.public_ip}:~/

    2. prepare each host
       ssh ubuntu@${aws_instance.sut.public_ip} 'bash performance/environment/provision-sut.sh'
       ssh ubuntu@${aws_instance.loadgen.public_ip} 'bash performance/environment/provision-loadgen.sh'

    3. bring the stack up on the SUT, pinned to the spec's resources
       ssh ubuntu@${aws_instance.sut.public_ip} 'docker compose -f deploy/compose.deploy.yml -f performance/compose.perf-fixed.yml up -d'

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
         ./performance/run-baseline.sh mixed-learning

    Both instances bill by the hour. 'terraform destroy' removes them and the security groups.

  EOT
}
