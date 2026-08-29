output "alb_dns_name" {
  description = "The environment's entry point. Nothing resolves here until alb_ingress_cidrs is set."
  value       = module.compute.alb_dns_name
}

output "ecr_repository_urls" {
  value = module.registry.repository_urls
}

output "ecs_cluster_name" {
  value = module.compute.cluster_name
}

output "github_deploy_role_arn" {
  description = "Set as an Actions variable; the pipeline assumes this by OIDC."
  value       = module.cicd.deploy_role_arn
}

output "github_plan_role_arn" {
  value = module.cicd.plan_role_arn
}

output "database_endpoint" {
  description = "Private. Not reachable from outside the VPC."
  value       = module.data.endpoint
}

output "database_master_secret_arn" {
  description = "AWS-managed. Application roles are created from it by migration, not used directly."
  value       = module.data.master_user_secret_arn
}

output "ai_service_discovery_name" {
  description = "How the platform addresses the AI plane. Resolvable only inside the VPC."
  value       = module.compute.ai_service_discovery_name
}
