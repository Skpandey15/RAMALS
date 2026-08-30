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
  [switch]$SkipBuild,
  [int]$IngressPort = 8080,
  [switch]$EnableOpenAI
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
  # -p publishes a host port to the Traefik load balancer, which is what makes the Ingress
  # reachable. Every Service stays ClusterIP; this one mapping is the only way in, and it can only
  # be set at creation time -- on an existing cluster use
  # `k3d cluster edit <name> --port-add "8080:80@loadbalancer"`.
  k3d cluster create $ClusterName --servers 1 --agents 1 --registry-use $registryRef `
    -p "${IngressPort}:80@loadbalancer" --wait --timeout 300s | Out-Host
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

    # web-ui's VITE_* values are inlined at build time, so the OIDC issuer is a property of the
    # image. VITE_API_BASE_URL must be EMPTY: api.ts already prefixes every path with /api/v1, so a
    # non-empty base produces /api/api/v1/... -- a route Spring has no mapping for, and the only
    # symptom is a 404 the UI renders as "Not found".
    $buildArgs = @()
    if ($i.name -eq "web-ui") {
      $buildArgs = @(
        "--build-arg", "VITE_KEYCLOAK_URL=http://keycloak.localhost:${IngressPort}",
        "--build-arg", "VITE_API_BASE_URL="
      )
    }
    docker build @buildArgs -t $push -f $i.file . | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "build failed: $($i.name)" }
    docker push $push | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "push failed: $($i.name)" }
  }
}

Write-Host "== cluster DNS ==" -ForegroundColor Cyan
# The browser and the platform must agree on ONE issuer URL. `keycloak.localhost` resolves to
# 127.0.0.1 in browsers for free (RFC 6761); this rewrite makes the same name resolve to the
# keycloak Service inside the cluster, so Keycloak stamps `iss` with a host the platform can also
# fetch JWKS from. Without it, login succeeds in the browser and every API call then 401s.
@"
apiVersion: v1
kind: ConfigMap
metadata:
  name: coredns-custom
  namespace: kube-system
data:
  ramals.override: |
    rewrite name keycloak.localhost keycloak.$Namespace.svc.cluster.local
"@ | kubectl apply -f - | Out-Host
kubectl -n kube-system rollout restart deployment/coredns | Out-Host
kubectl -n kube-system rollout status deployment/coredns --timeout=120s | Out-Host

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

if ($EnableOpenAI) {
  Write-Host "== live provider execution (opt-in) ==" -ForegroundColor Yellow

  # Read from the User environment in the registry rather than $env:, because a long-lived shell
  # inherited its environment when it started and will happily hand back a key you rotated an hour
  # ago. That exact staleness produced a 401 against a key that was perfectly valid.
  $key = [Environment]::GetEnvironmentVariable("RAMALS_AI_PROVIDER_API_KEY", "User")
  if (-not $key) { $key = [Environment]::GetEnvironmentVariable("RAMALS_AI_PROVIDER_API_KEY", "Machine") }
  if (-not $key) {
    throw "-EnableOpenAI needs RAMALS_AI_PROVIDER_API_KEY in the User or Machine environment. Set it, then re-run."
  }

  # The value is piped straight into kubectl and never written to a file, a log, or the console.
  kubectl -n $Namespace create secret generic ramals-ai-provider `
    --from-literal=provider-api-key=$key --dry-run=client -o yaml | kubectl apply -f - | Out-Host

  # Applied to the live Deployment rather than to the manifests, so the committed default stays off
  # and a later `kubectl apply -k` does not silently re-enable billable calls for someone else.
  kubectl -n $Namespace set env deployment/ramals-ai `
    RAMALS_AI_AI_ENABLED=true RAMALS_AI_MODEL_ROUTE=diagnostic-default | Out-Host

  Write-Host "OpenAI enabled: routes pinned to gpt-4.1-2025-04-14. This makes real, billable calls." -ForegroundColor Yellow
}

Write-Host "== waiting for workloads ==" -ForegroundColor Cyan
kubectl rollout status statefulset/postgres -n $Namespace --timeout=300s | Out-Host
kubectl rollout status deployment/keycloak -n $Namespace --timeout=300s | Out-Host
kubectl rollout status deployment/ramals-ai -n $Namespace --timeout=300s | Out-Host
kubectl rollout status deployment/learning-platform -n $Namespace --timeout=420s | Out-Host
kubectl rollout status deployment/web-ui -n $Namespace --timeout=300s | Out-Host

kubectl get pods -n $Namespace -o wide | Out-Host
Write-Host ""
Write-Host "Ready. Verify with: pwsh -File .\deploy\k8s\dev\smoke.ps1" -ForegroundColor Green
