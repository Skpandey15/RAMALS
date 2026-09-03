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
  # Who authorised the deployment, from the Jenkins input's submitterParameter. Recorded in the
  # evidence bundle: the build number and URL survive in summary.json, but the approver was the one
  # fact a human gate establishes and nothing captured it.
  [string]$ApprovedBy,
  [string]$ExpectedRepository = "https://github.com/Skpandey15/RAMALS.git",
  [string]$Namespace = "ramals-dev",
  [string]$ClusterName = "ramals-dev",
  # Redeploys a version that previously failed its health gates and was rolled back. Held versions
  # are refused by default so a bad commit at the head of main is not redeployed every two minutes
  # by SCM polling; overriding is a deliberate act by whoever believes the environment, not the
  # commit, was the problem.
  [switch]$ForceHeldRelease,
  # Overridable so the state machine can be exercised against a scratch path in tests.
  [string]$StateRoot
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

. (Join-Path $PSScriptRoot "cd-state.ps1")
$statePath = Get-RamalsCdStatePath -ClusterName $ClusterName -Namespace $Namespace -Root $StateRoot
$cdState = Get-RamalsCdState -StatePath $statePath

# Checked in the validate stage as well as the deploy stage, so a held release fails before anybody
# is asked to approve it. Asking a human to authorise a deployment that is going to be refused
# teaches them that the prompt does not mean anything.
if ((Test-RamalsReleaseHeld -State $cdState -Commit $head) -and -not $ForceHeldRelease) {
  throw ("Release $head is HELD: it previously failed its health gates and was rolled back. " +
    "Fix the commit, or re-run with -ForceHeldRelease if the environment was the problem.")
}

if ($ValidateOnly) { return }

$evidenceDirectory = Join-Path $repositoryRoot "artifacts\jenkins"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$applicationReleaseCommit = $null
# An unset submitterParameter arrives as the literal "%RAMALS_APPROVER%" from cmd when the variable
# does not exist, so an unrecorded approver is written as UNRECORDED rather than as shell noise --
# evidence that says nothing is better than evidence that looks like a name and is not one.
$approvedBy = if ([string]::IsNullOrWhiteSpace($ApprovedBy) -or $ApprovedBy -match '^%.*%$') {
  "UNRECORDED"
} else {
  $ApprovedBy.Trim()
}
Write-Host "[deploy] Approved by: $approvedBy"

$knownGoodImages = @{}
$rolledBack = $false

try {
  Assert-DockerRuntimeReady

  # Captured before anything is applied, because after the apply the cluster no longer knows what
  # it was running. This is the whole difference between a failed deployment and an outage.
  $knownGoodImages = Get-RamalsWorkloadImages -Namespace $Namespace
  if ($knownGoodImages.Count -gt 0) {
    Write-Host "[deploy] Known-good workloads captured: $($knownGoodImages.Count)"
  } else {
    Write-Host "[deploy] No running workloads to capture; this deployment has nothing to roll back to."
  }
  $cdState['state'] = 'DEPLOYING'
  $cdState['current_commit'] = $head
  Set-RamalsCdState -StatePath $statePath -State $cdState

  $ghcrEvidence = Join-Path $evidenceDirectory "ghcr-images.json"
  # Local/dev follows current main. Image preparation waits for GitHub Actions to publish this
  # exact commit and verifies every OCI revision before anything reaches the cluster.
  $applicationReleaseCommit = $head
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

  # HEALTHY, and this version becomes the thing a future failure returns to. Recorded only after
  # the smoke suite passed: a version that has not proved itself is not a rollback target.
  $cdState['state'] = 'HEALTHY'
  $cdState['current_commit'] = $head
  $cdState['known_good_commit'] = $head
  $cdState['known_good_images'] = Get-RamalsWorkloadImages -Namespace $Namespace
  $cdState['failure_count'] = 0
  Set-RamalsCdState -StatePath $statePath -State $cdState

  [ordered]@{
    outcome = "SUCCESS"
    state = "HEALTHY"
    deploymentConfigCommit = $head
    applicationReleaseCommit = $applicationReleaseCommit
    approvedBy = $approvedBy
    buildNumber = $env:BUILD_NUMBER
    buildUrl = $env:BUILD_URL
    cluster = $ClusterName
    namespace = $Namespace
    completedAt = [DateTimeOffset]::UtcNow.ToString("O")
  } | ConvertTo-Json | Out-File (Join-Path $evidenceDirectory "summary.json") -Encoding utf8

  Write-Host "[deploy] RAMALS local DEV deployment completed successfully."
} catch {
  $failureReason = ConvertTo-PlainLogText $_.Exception.Message

  # FAILED -> ROLLBACK -> ROLLED_BACK -> RELEASE_HELD, the same sequence the pull-based controller
  # runs. Attempted for any failure past the point where workloads may have changed: the failure
  # that matters is not the smoke suite reporting a fault, it is the cluster being left on a
  # version nobody verified.
  $cdState['state'] = 'FAILED'
  $cdState['failure_count'] = [int]$cdState['failure_count'] + 1
  Set-RamalsCdState -StatePath $statePath -State $cdState

  if ($knownGoodImages.Count -gt 0) {
    Write-Host ""
    Write-Host "[rollback] Deployment failed. Restoring last known-good workloads..."
    $cdState['state'] = 'ROLLBACK'
    Set-RamalsCdState -StatePath $statePath -State $cdState
    try {
      $rolledBack = Restore-RamalsWorkloadImages -Namespace $Namespace -Images $knownGoodImages
    } catch {
      # A rollback that throws must not replace the original failure reason: the first fault is
      # what an operator needs, and the rollback outcome is reported separately below.
      Write-Host "[rollback] Restore raised: $(ConvertTo-PlainLogText $_.Exception.Message)"
      $rolledBack = $false
    }
    $cdState['state'] = if ($rolledBack) { 'ROLLED_BACK' } else { 'FAILED' }
    Set-RamalsCdState -StatePath $statePath -State $cdState
    if ($rolledBack) {
      Write-Host "[rollback] Restored to known-good commit $($cdState['known_good_commit'])."
    } else {
      Write-Host "[rollback] INCOMPLETE. The cluster is in a mixed state and needs a human."
    }
  }

  # Held whether or not the restore succeeded. The version failed its gates either way, and the
  # thing that must not happen is the next poll deploying it again.
  $cdState = Add-RamalsHeldRelease -State $cdState -Commit $head
  $cdState['state'] = 'RELEASE_HELD'
  Set-RamalsCdState -StatePath $statePath -State $cdState
  Write-Host "[rollback] RELEASE_HELD: $head will not be redeployed automatically."

  [ordered]@{
    outcome = "FAILED"
    state = "RELEASE_HELD"
    rolledBack = $rolledBack
    rolledBackTo = $cdState['known_good_commit']
    deploymentConfigCommit = $head
    applicationReleaseCommit = $applicationReleaseCommit
    approvedBy = $approvedBy
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
