[CmdletBinding()]
param(
  [string]$ClusterName = "t15",
  [string]$Namespace = "ramals-t15",
  [switch]$SkipClusterCreation
)

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
Set-Location $repositoryRoot

function Invoke-Checked {
  param(
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][string[]]$Arguments
  )

  & $Command @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$Command failed with exit code $LASTEXITCODE"
  }
}

function New-RandomSecret {
  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  return [Convert]::ToBase64String($bytes)
}

function EnvironmentOrRandom {
  param([Parameter(Mandatory = $true)][string]$Name)

  $value = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($value)) {
    return New-RandomSecret
  }
  return $value
}

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
  throw "kubectl is required"
}
if (-not (Get-Command k3d -ErrorAction SilentlyContinue)) {
  throw "k3d is required"
}

$registryContainer = "k3d-ramals-t15-registry"
$registryListing = (& k3d registry list 2>$null) -join "`n"
if ($LASTEXITCODE -ne 0 -or $registryListing -notmatch [regex]::Escape($registryContainer)) {
  Write-Host "Creating the isolated local registry on localhost:5111"
  Invoke-Checked "k3d" @("registry", "create", "ramals-t15-registry", "--port", "5111")
}

$clusterListing = (& k3d cluster list 2>$null) -join "`n"
$clusterExists = $clusterListing -match ("(?m)^\s*" + [regex]::Escape($ClusterName) + "\s")
if (-not $clusterExists) {
  if ($SkipClusterCreation) {
    throw "k3d cluster '$ClusterName' does not exist and -SkipClusterCreation was supplied"
  }

  Write-Host "Creating isolated k3d cluster '$ClusterName' with two agents"
  Invoke-Checked "k3d" @(
    "cluster", "create", $ClusterName,
    "--servers", "1",
    "--agents", "2",
    "--registry-use", "$registryContainer`:5000",
    "--wait"
  )
}

$context = "k3d-$ClusterName"
Invoke-Checked "kubectl" @("config", "use-context", $context)

Write-Host "Applying the qualification namespace"
Invoke-Checked "kubectl" @("apply", "-f", "deploy/k8s/t15/namespace.yaml")

$secretName = "ramals-t15-runtime"
$existingSecret = (& kubectl get secret $secretName -n $Namespace --ignore-not-found=true -o name 2>$null) -join ""
if ([string]::IsNullOrWhiteSpace($existingSecret)) {
  # The secret is intentionally generated outside the repository. Values supplied through the
  # environment are useful for a repeatable local run; otherwise every fresh namespace receives
  # independent credentials. No value is written to stdout or to a repository file.
  $dbAdminPassword = EnvironmentOrRandom "RAMALS_T15_DB_ADMIN_PASSWORD"
  $dbMigrationPassword = EnvironmentOrRandom "RAMALS_T15_DB_MIGRATION_PASSWORD"
  $dbRuntimePassword = EnvironmentOrRandom "RAMALS_T15_DB_RUNTIME_PASSWORD"
  $keycloakDbPassword = EnvironmentOrRandom "RAMALS_T15_KEYCLOAK_DB_PASSWORD"
  $keycloakAdminPassword = EnvironmentOrRandom "RAMALS_T15_KEYCLOAK_ADMIN_PASSWORD"
  $workloadClientSecret = EnvironmentOrRandom "RAMALS_T15_WORKLOAD_CLIENT_SECRET"

  $secretArguments = @(
    "create", "secret", "generic", $secretName, "-n", $Namespace,
    "--from-literal=db-name=ramals",
    "--from-literal=db-admin-user=ramals_admin",
    "--from-literal=db-admin-password=$dbAdminPassword",
    "--from-literal=db-runtime-user=ramals_core_runtime",
    "--from-literal=db-runtime-password=$dbRuntimePassword",
    "--from-literal=db-migration-user=ramals_core_migration",
    "--from-literal=db-migration-password=$dbMigrationPassword",
    "--from-literal=keycloak-db-name=keycloak",
    "--from-literal=keycloak-db-user=keycloak",
    "--from-literal=keycloak-db-password=$keycloakDbPassword",
    "--from-literal=keycloak-admin-user=admin",
    "--from-literal=keycloak-admin-password=$keycloakAdminPassword",
    "--from-literal=workload-client-secret=$workloadClientSecret",
    "--dry-run=client", "-o", "yaml"
  )

  Write-Host "Generating the namespace runtime Secret (values are not displayed)"
  $secretYaml = & kubectl @secretArguments
  if ($LASTEXITCODE -ne 0) {
    throw "kubectl could not generate the runtime Secret"
  }
  $secretYaml | & kubectl apply -f -
  if ($LASTEXITCODE -ne 0) {
    throw "kubectl could not apply the runtime Secret"
  }
} else {
  Write-Host "Using the existing namespace runtime Secret"
}

Write-Host "Applying the immutable current-main qualification topology"
Invoke-Checked "kubectl" @("apply", "-k", "deploy/k8s/t15")

Write-Host "Waiting for Keycloak client bootstrap"
Invoke-Checked "kubectl" @(
  "wait", "--for=condition=complete", "job/keycloak-client-bootstrap",
  "-n", $Namespace, "--timeout=300s"
)

Write-Host "Waiting for the live workload-identity smoke"
Invoke-Checked "kubectl" @(
  "wait", "--for=condition=complete", "job/workload-identity-smoke",
  "-n", $Namespace, "--timeout=300s"
)

Write-Host "Waiting for the database and application rollouts"
Invoke-Checked "kubectl" @("rollout", "status", "statefulset/postgres", "-n", $Namespace, "--timeout=300s")
Invoke-Checked "kubectl" @("rollout", "status", "deployment/keycloak", "-n", $Namespace, "--timeout=300s")
Invoke-Checked "kubectl" @("rollout", "status", "deployment/learning-platform", "-n", $Namespace, "--timeout=300s")
Invoke-Checked "kubectl" @("rollout", "status", "deployment/ramals-ai", "-n", $Namespace, "--timeout=300s")
Invoke-Checked "kubectl" @("rollout", "status", "deployment/web-ui", "-n", $Namespace, "--timeout=300s")

Write-Host "T15.1 topology is deployed; run smoke.ps1 to capture qualification evidence"
Invoke-Checked "kubectl" @("get", "pods", "-n", $Namespace, "-o", "wide")
