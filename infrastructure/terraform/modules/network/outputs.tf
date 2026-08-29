output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "Load balancer and NAT only. Nothing else belongs here."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Every workload and the database."
  value       = aws_subnet.private[*].id
}

output "vpc_cidr" {
  value = aws_vpc.this.cidr_block
}
