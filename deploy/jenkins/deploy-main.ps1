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
$ErrorView = "ConciseView"
$env:NO_COLOR = "1"
$env:CLICOLOR = "0"
$env:TERM = "dumb"
if ($null -ne (Get-Variable -Name PSStyle -ErrorAction SilentlyContinue)) {
  $PSStyle.OutputRendering = "PlainText"
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repositoryRoot

function ConvertTo-PlainLogText {
  param([AllowNull()][object]$Value)
  if ($null -eq $Value) { return "" }
  return ([string]$Value -replace "`e\[[0-9;?]*[ -/]*[@-~]", "").Trim()
}

function Invoke-Checked {
  param([string]$Description, [scriptblock]$Command)
  & $Command
  if ($LASTEXITCODE -ne 0) {
    throw "$Description failed with exit code $LASTEXITCODE."
  }
}

function Assert-DockerRuntimeReady {
  Write-Host "[preflight] Checking Docker runtime..."
  $probeOutput = & docker info --format '{{.ServerVersion}}' 2>&1
  $probeExitCode = $LASTEXITCODE
  $plainOutput = @($probeOutput | ForEach-Object { ConvertTo-PlainLogText $_ } | Where-Object { $_ })
  $serverVersion = @($plainOutput | Where-Object { $_ -match '^v?\d+(\.\d+){1,3}([+-][0-9A-Za-z.-]+)?$' } | Select-Object -First 1)

  if ($probeExitCode -ne 0 -or $serverVersion.Count -ne 1) {
    $detail = ($plainOutput -join " | ")
    if (-not $detail) { $detail = "docker info returned no diagnostic output" }
    throw "Docker runtime is unavailable. $detail"
  }

  Write-Host "[preflight] Docker runtime ready: $($serverVersion[0])"
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

Write-Host "Validated trusted main commit $head"
if ($ValidateOnly) { return }

$evidenceDirectory = Join-Path $repositoryRoot "artifacts\jenkins"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$applicationReleaseCommit = $null

try {
  Assert-DockerRuntimeReady

  $ghcrEvidence = Join-Path $evidenceDirectory "ghcr-images.json"
  $desiredVersion = Get-Content (Join-Path $repositoryRoot "deploy\desired-version.json") -Raw | ConvertFrom-Json
  $applicationReleaseCommit = [string]$desiredVersion.release.commit
  $applicationImageTag = $applicationReleaseCommit.Substring(0, 7)
  $infrastructureImageTag = $head.Substring(0, 7)

  Write-Host "[deploy] Preparing immutable GHCR application images..."
  & pwsh -NoLogo -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\jenkins\prepare-ghcr-images.ps1") `
    -Commit $head -EvidencePath $ghcrEvidence
  if ($LASTEXITCODE -ne 0) { throw "Preparing immutable GHCR images failed." }

  Write-Host "[deploy] Bootstrapping local k3d DEV..."
  & pwsh -NoLogo -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\k8s\dev\bootstrap.ps1") `
    -Namespace $Namespace -ClusterName $ClusterName `
    -ApplicationImageTag $applicationImageTag `
    -InfrastructureImageTag $infrastructureImageTag -SkipBuild
  if ($LASTEXITCODE -ne 0) { throw "RAMALS bootstrap failed." }

  Write-Host "[deploy] Running smoke suite..."
  $smokeLog = Join-Path $evidenceDirectory "smoke.log"
  & pwsh -NoLogo -NoProfile -NonInteractive -File `
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

  Write-Host "[deploy] RAMALS local DEV deployment completed successfully."
} catch {
  $failureReason = ConvertTo-PlainLogText $_.Exception.Message
  [ordered]@{
    outcome = "FAILED"
    deploymentConfigCommit = $head
    applicationReleaseCommit = $applicationReleaseCommit
    buildNumber = $env:BUILD_NUMBER
    buildUrl = $env:BUILD_URL
    cluster = $ClusterName
    namespace = $Namespace
    failedAt = [DateTimeOffset]::UtcNow.ToString("O")
    reason = $failureReason
  } | ConvertTo-Json | Out-File (Join-Path $evidenceDirectory "summary.json") -Encoding utf8

  Write-Host ""
  Write-Host "ERROR: $failureReason"
  Write-Host "Deployment stopped. See artifacts/jenkins/summary.json for failure evidence."
  exit 1
}
