# RAMALS local Kubernetes bootstrap entrypoint.
#
# The long-lived core bootstrap remains in bootstrap-core.ps1. This wrapper adds the privileged
# identity-administration reconciliation that must happen after Keycloak is running, without
# weakening the core bootstrap's image, database, user, and smoke-test behavior.

[CmdletBinding()]
param(
  [string]$ClusterName = "ramals-dev",
  [string]$RegistryName = "ramals-registry",
  [int]$RegistryPort = 5000,
  [string]$Namespace = "ramals-dev",
  [string]$ApplicationImageTag,
  [string]$InfrastructureImageTag,
  [switch]$SkipBuild,
  [int]$IngressPort = 8080,
  [switch]$EnableOpenAI,
  [switch]$RepairDockerCredentials,
  [switch]$ShowTestCredentials
)

$ErrorActionPreference = "Stop"

$coreArgs = @{
  ClusterName = $ClusterName
  RegistryName = $RegistryName
  RegistryPort = $RegistryPort
  Namespace = $Namespace
  IngressPort = $IngressPort
}
if ($SkipBuild) { $coreArgs.SkipBuild = $true }
if ($ApplicationImageTag) { $coreArgs.ApplicationImageTag = $ApplicationImageTag }
if ($InfrastructureImageTag) { $coreArgs.InfrastructureImageTag = $InfrastructureImageTag }
if ($EnableOpenAI) { $coreArgs.EnableOpenAI = $true }
if ($RepairDockerCredentials) { $coreArgs.RepairDockerCredentials = $true }
if ($ShowTestCredentials) { $coreArgs.ShowTestCredentials = $true }

& (Join-Path $PSScriptRoot "bootstrap-core.ps1") @coreArgs
if (-not $?) { throw "Core RAMALS bootstrap failed." }

Write-Host "== admin identity workload ==" -ForegroundColor Cyan
kubectl config use-context "k3d-$ClusterName" | Out-Null

function New-RandomSecret {
  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [Convert]::ToBase64String($bytes)
}

$secretName = "ramals-dev-identity-admin"
$secretKey = "RAMALS_ADMIN_IDENTITY_CLIENT_SECRET"
$secretRef = kubectl get secret $secretName -n $Namespace --ignore-not-found -o name
if (-not $secretRef) {
  kubectl create secret generic $secretName -n $Namespace `
    --from-literal="$secretKey=$(New-RandomSecret)" `
    --dry-run=client -o yaml | kubectl apply -f - | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Failed to create $secretName." }
  Write-Host "  generated dedicated identity-admin client secret"
} else {
  $encoded = kubectl get secret $secretName -n $Namespace -o jsonpath="{.data.$secretKey}"
  if (-not $encoded) {
    $patch = @{ stringData = @{ $secretKey = (New-RandomSecret) } } | ConvertTo-Json -Compress
    kubectl patch secret $secretName -n $Namespace --type merge -p $patch | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Failed to repair $secretName." }
    Write-Host "  repaired missing identity-admin client secret"
  } else {
    Write-Host "  reusing dedicated identity-admin client secret"
  }
}

$keycloakPod = kubectl get pod -n $Namespace -l app.kubernetes.io/name=keycloak `
  -o jsonpath='{.items[0].metadata.name}'
if (-not $keycloakPod) { throw "Keycloak pod not found for identity-admin reconciliation." }

$encoded = kubectl get secret $secretName -n $Namespace -o jsonpath="{.data.$secretKey}"
$identityAdminSecret = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))

# Secret goes to kcadm on stdin, never argv or terminal output. The reconciliation helper creates or
# updates the client in persistent realms, pins the same secret the backend receives, removes
# realm-wide administration, grants manage-users/view-users only, and marks the workload SERVICE.
$reconcileOutput = ($identityAdminSecret | kubectl exec -i -n $Namespace $keycloakPod -- `
  /bin/sh /opt/keycloak/bin/reconcile-identity-admin.sh 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $reconcileOutput -notmatch 'reconciled ramals-identity-admin') {
  throw "Failed to reconcile ramals-identity-admin. Keycloak said:`n$reconcileOutput"
}
Write-Host "  Keycloak client reconciled with least-privilege workload roles"

# The manifest references this Secret as optional so the core bootstrap can bring the platform up on
# a brand-new cluster before the post-Keycloak secret exists. Restart now so the completed bootstrap
# always hands back a backend with the credential injected; subsequent applies keep the reference.
kubectl rollout restart deployment/learning-platform -n $Namespace | Out-Host
kubectl rollout status deployment/learning-platform -n $Namespace --timeout=420s | Out-Host
if ($LASTEXITCODE -ne 0) { throw "learning-platform did not become ready after identity-admin wiring." }

Write-Host "  identity administration ready" -ForegroundColor Green
Write-Host ""
Write-Host "Ready. Verify with: pwsh -File .\deploy\k8s\dev\smoke.ps1" -ForegroundColor Green
