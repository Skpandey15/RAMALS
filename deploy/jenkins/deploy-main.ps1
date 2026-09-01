<#
.SYNOPSIS
Validates and deploys the exact trusted main commit to the local RAMALS k3d environment.

.DESCRIPTION
This is the Jenkins CD boundary. GitHub Actions remains responsible for qualification; this script
refuses arbitrary branches/remotes, invokes the canonical bootstrap, runs the canonical smoke suite,
and writes non-secret deployment evidence for Jenkins to archive.
#>

[CmdletBinding()]
param(
  [switch]$ValidateOnly,
  [string]$ExpectedRepository = "https://github.com/Skpandey15/RAMALS.git",
  [string]$Namespace = "ramals-dev",
  [string]$ClusterName = "ramals-dev"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repositoryRoot

function Invoke-Checked {
  param([string]$Description, [scriptblock]$Command)
  & $Command
  if ($LASTEXITCODE -ne 0) {
    throw "$Description failed with exit code $LASTEXITCODE."
  }
}

$requiredTools = @("git")
if (-not $ValidateOnly) { $requiredTools += @("pwsh", "docker", "k3d", "kubectl") }
foreach ($tool in $requiredTools) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    throw "$tool is required on the Jenkins agent PATH."
  }
}

$origin = (git remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0) { throw "The Jenkins checkout has no origin remote." }
$normalize = { param([string]$value) ($value.TrimEnd('/') -replace '\.git$', '').ToLowerInvariant() }
if ((& $normalize $origin) -ne (& $normalize $ExpectedRepository)) {
  throw "Refusing deployment from unexpected origin '$origin'."
}

Invoke-Checked "Fetching origin/main" { git fetch --no-tags origin main }
$head = (git rev-parse HEAD).Trim()
$main = (git rev-parse origin/main).Trim()
if ($head -ne $main) {
  throw "Refusing deployment: checked-out HEAD $head is not current origin/main $main."
}
if (git status --porcelain --untracked-files=no) {
  throw "Refusing deployment from a checkout with tracked modifications."
}

Write-Host "Validated trusted main commit $head" -ForegroundColor Green
if ($ValidateOnly) { return }

$evidenceDirectory = Join-Path $repositoryRoot "artifacts\jenkins"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$applicationReleaseCommit = $null

try {
  $ghcrEvidence = Join-Path $evidenceDirectory "ghcr-images.json"
  $desiredVersion = Get-Content (Join-Path $repositoryRoot "deploy\desired-version.json") -Raw | ConvertFrom-Json
  $applicationReleaseCommit = [string]$desiredVersion.release.commit
  $applicationImageTag = $applicationReleaseCommit.Substring(0, 7)
  $infrastructureImageTag = $head.Substring(0, 7)
  & pwsh -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\jenkins\prepare-ghcr-images.ps1") `
    -Commit $head -EvidencePath $ghcrEvidence
  if ($LASTEXITCODE -ne 0) { throw "Preparing immutable GHCR images failed." }

  & pwsh -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\k8s\dev\bootstrap.ps1") `
    -Namespace $Namespace -ClusterName $ClusterName `
    -ApplicationImageTag $applicationImageTag `
    -InfrastructureImageTag $infrastructureImageTag -SkipBuild
  if ($LASTEXITCODE -ne 0) { throw "RAMALS bootstrap failed." }

  $smokeLog = Join-Path $evidenceDirectory "smoke.log"
  & pwsh -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\k8s\dev\smoke.ps1") `
    -Namespace $Namespace -ClusterName $ClusterName 2>&1 | Tee-Object -FilePath $smokeLog
  if ($LASTEXITCODE -ne 0) { throw "RAMALS smoke suite failed." }

  kubectl get pods -n $Namespace -o wide | Out-File `
    (Join-Path $evidenceDirectory "pods.txt") -Encoding utf8
  kubectl get deployments,statefulsets -n $Namespace -o json | Out-File `
    (Join-Path $evidenceDirectory "workloads.json") -Encoding utf8
  $deploymentJson = kubectl get deployment -n $Namespace -o json
  if ($LASTEXITCODE -ne 0) { throw "Collecting deployed image evidence failed." }
  $deploymentJson | ConvertFrom-Json | Select-Object -ExpandProperty items |
    ForEach-Object { "{0}={1}" -f $_.metadata.name, $_.spec.template.spec.containers[0].image } |
    Out-File (Join-Path $evidenceDirectory "images.txt") -Encoding utf8

  [ordered]@{
    outcome = "SUCCESS"
    deploymentConfigCommit = $head
    applicationReleaseCommit = $applicationReleaseCommit
    buildNumber = $env:BUILD_NUMBER
    buildUrl = $env:BUILD_URL
    cluster = $ClusterName
    namespace = $Namespace
    completedAt = [DateTimeOffset]::UtcNow.ToString("O")
  } | ConvertTo-Json | Out-File (Join-Path $evidenceDirectory "summary.json") -Encoding utf8
} catch {
  [ordered]@{
    outcome = "FAILED"
    deploymentConfigCommit = $head
    applicationReleaseCommit = $applicationReleaseCommit
    buildNumber = $env:BUILD_NUMBER
    buildUrl = $env:BUILD_URL
    cluster = $ClusterName
    namespace = $Namespace
    failedAt = [DateTimeOffset]::UtcNow.ToString("O")
    reason = $_.Exception.Message
  } | ConvertTo-Json | Out-File (Join-Path $evidenceDirectory "summary.json") -Encoding utf8
  throw
}
