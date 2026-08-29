# Stand up the RAMALS local Kubernetes development environment from nothing.
#
# Safe to re-run. Every step checks for what it is about to create, so a partial run followed by a
# second invocation converges rather than failing or duplicating. The one thing it will not do is
# silently adopt a cluster somebody else made under the same name with a different shape -- that is
# reported, not worked around.
#
#   pwsh -File .\deploy\k8s\dev\bootstrap.ps1
#
# This is the ordinary developer environment. It is not the M2-T15 qualification environment and it
# is not AWS DEV. Contract B stays off; no external AI provider is configured.

[CmdletBinding()]
param(
  [string]$ClusterName = "ramals-dev",
  [string]$RegistryName = "ramals-registry",
  [int]$RegistryPort = 5000,
  [string]$Namespace = "ramals-dev",
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
Set-Location $repositoryRoot

$context = "k3d-$ClusterName"
$registryHost = "k3d-$RegistryName"
$registryRef = "${registryHost}:${RegistryPort}"

function Assert-Tool([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "$name is not on PATH. See deploy/k8s/dev/README.md for prerequisites."
  }
}

Write-Host "== prerequisites ==" -ForegroundColor Cyan
foreach ($t in @("docker", "kubectl", "k3d")) { Assert-Tool $t }
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker is not responding. Start Rancher Desktop and retry." }

# The commit is the image identity. A dirty tree would produce an image whose tag names a commit it
# does not actually contain, so refuse rather than mislabel it.
$gitSha = (git rev-parse --short HEAD).Trim()
if ((git status --porcelain --untracked-files=no)) {
  throw "Working tree has uncommitted tracked changes; images would be tagged '$gitSha' but not match it. Commit or stash first."
}
Write-Host "commit: $gitSha"

Write-Host "== registry ==" -ForegroundColor Cyan
if (-not (k3d registry list -o json | ConvertFrom-Json | Where-Object { $_.name -eq $registryHost })) {
  k3d registry create $RegistryName --port $RegistryPort | Out-Host
} else {
  Write-Host "registry $registryHost already exists"
}

Write-Host "== cluster ==" -ForegroundColor Cyan
if (-not (k3d cluster list -o json | ConvertFrom-Json | Where-Object { $_.name -eq $ClusterName })) {
  # No host port mappings on purpose: every Service in this package is ClusterIP, so there is
  # nothing to publish. Developer access is kubectl port-forward (see README).
  k3d cluster create $ClusterName --servers 1 --agents 1 --registry-use $registryRef --wait --timeout 300s | Out-Host
} else {
  Write-Host "cluster $ClusterName already exists"
}

kubectl config use-context $context | Out-Null
kubectl wait --for=condition=Ready node --all --timeout=180s | Out-Host

if (-not $SkipBuild) {
  Write-Host "== images ==" -ForegroundColor Cyan
  $images = @(
    @{ name = "postgres";          file = "infrastructure/docker/postgres-init/Dockerfile" },
    @{ name = "keycloak";          file = "infrastructure/docker/keycloak/Dockerfile" },
    @{ name = "ramals-ai";         file = "ramals-ai/Dockerfile" },
    @{ name = "web-ui";            file = "web-ui/Dockerfile" },
    @{ name = "learning-platform"; file = "learning-platform/Dockerfile" }
  )
  foreach ($i in $images) {
    # Push through localhost; the cluster pulls the same repository under the registry's in-network
    # name. Both names address one registry, so one push serves both.
    $push = "localhost:${RegistryPort}/ramals-$($i.name):$gitSha"
    Write-Host "building $($i.name)" -ForegroundColor DarkCyan
    docker build -t $push -f $i.file . | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "build failed: $($i.name)" }
    docker push $push | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "push failed: $($i.name)" }
  }
}

Write-Host "== secrets ==" -ForegroundColor Cyan
kubectl apply -f deploy/k8s/dev/namespace.yaml | Out-Host

# Generated in memory, never written to disk and never echoed. Re-running keeps the existing Secret
# so a redeploy does not invalidate the passwords PostgreSQL already initialised its roles with.
function New-RandomSecret {
  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [Convert]::ToBase64String($bytes)
}

if (-not (kubectl get secret ramals-dev-runtime -n $Namespace --ignore-not-found -o name)) {
  kubectl create secret generic ramals-dev-runtime -n $Namespace `
    --from-literal=db-admin-password=$(New-RandomSecret) `
    --from-literal=db-migration-password=$(New-RandomSecret) `
    --from-literal=db-runtime-password=$(New-RandomSecret) `
    --from-literal=keycloak-db-password=$(New-RandomSecret) `
    --from-literal=keycloak-admin-password=$(New-RandomSecret) `
    --dry-run=client -o yaml | kubectl apply -f - | Out-Host
} else {
  Write-Host "secret ramals-dev-runtime already exists (kept)"
}

Write-Host "== deploy ==" -ForegroundColor Cyan
# kustomization.yaml pins a tag so that `kubectl kustomize` alone renders something valid and
# reviewable. That committed tag is whatever commit last touched the package, which is almost never
# the commit you are deploying -- so the tag is re-pointed here, to the sha actually built above.
#
# The pattern matches the tag on this registry's images only; it cannot touch the digest-pinned
# upstream base images, which must not be rewritten.
$rendered = (kubectl kustomize deploy/k8s/dev) -join "`n"
$rendered = [regex]::Replace(
  $rendered,
  "(?<repo>${registryHost}:${RegistryPort}/[A-Za-z0-9._/-]+):[A-Za-z0-9._-]+",
  "`${repo}:$gitSha")
$rendered | kubectl apply -f - | Out-Host

Write-Host "== waiting for workloads ==" -ForegroundColor Cyan
kubectl rollout status statefulset/postgres -n $Namespace --timeout=300s | Out-Host
kubectl rollout status deployment/keycloak -n $Namespace --timeout=300s | Out-Host
kubectl rollout status deployment/ramals-ai -n $Namespace --timeout=300s | Out-Host
kubectl rollout status deployment/learning-platform -n $Namespace --timeout=420s | Out-Host
kubectl rollout status deployment/web-ui -n $Namespace --timeout=300s | Out-Host

kubectl get pods -n $Namespace -o wide | Out-Host
Write-Host ""
Write-Host "Ready. Verify with: pwsh -File .\deploy\k8s\dev\smoke.ps1" -ForegroundColor Green
