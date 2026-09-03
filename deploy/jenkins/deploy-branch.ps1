<#
.SYNOPSIS
Deploys an arbitrary branch to the local k3d environment, or restores the last known-good main.

.DESCRIPTION
A scratch path, deliberately separate from deploy-main.ps1. The main boundary refuses anything that
is not origin/main and that refusal is the trust property the release path rests on; it is not
relaxed here, it is simply not the path being used.

**This replaces the running dev deployment.** The Ingress claims fixed hosts (localhost,
keycloak.localhost) and the web UI image has the Keycloak issuer baked in, so a second namespace
cannot be reached alongside the first. A branch deployment therefore takes over ramals-dev until
something else is deployed over it.

Two properties keep that from damaging the release path:

**A branch never becomes a rollback target.** known_good_commit and known_good_images are written
only by deploy-main.ps1, and only after a release passes its smoke suite. This script reads them and
never writes them, so the last healthy main release stays the thing the environment can be returned
to no matter how many branches are tried in between.

**Returning is one switch.** -RestoreKnownGood puts the recorded main release back without needing
a git ref, a rebuild, or the release pipeline -- which matters because the reason to want it is
usually that the branch under test is broken.
#>

[CmdletBinding()]
param(
  # The branch to deploy. Ignored when -RestoreKnownGood is given.
  [string]$Ref,
  [switch]$RestoreKnownGood,
  [string]$ApprovedBy,
  [string]$ExpectedRepository = "https://github.com/Skpandey15/RAMALS.git",
  [string]$Namespace = "ramals-dev",
  [string]$ClusterName = "ramals-dev",
  [int]$WaitMinutes = 20,
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

. (Join-Path $PSScriptRoot "cd-state.ps1")

function ConvertTo-PlainLogText {
  param([AllowNull()][object]$Value)
  if ($null -eq $Value) { return "" }
  return ([string]$Value -replace "`e\[[0-9;?]*[ -/]*[@-~]", "").Trim()
}

foreach ($tool in @("git", "pwsh", "docker", "k3d", "kubectl")) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    throw "$tool is required on the Jenkins agent PATH."
  }
}

# The origin check is kept. Relaxing which *ref* may be deployed is the point of this script;
# relaxing which *repository* it comes from is not, and never becomes the point.
$origin = (git remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0) { throw "The Jenkins checkout has no origin remote." }
$normalize = { param([string]$value) ($value.TrimEnd('/') -replace '\.git$', '').ToLowerInvariant() }
if ((& $normalize $origin) -ne (& $normalize $ExpectedRepository)) {
  throw "Refusing deployment from unexpected origin '$origin'."
}

$statePath = Get-RamalsCdStatePath -ClusterName $ClusterName -Namespace $Namespace -Root $StateRoot
$cdState = Get-RamalsCdState -StatePath $statePath
$evidenceDirectory = Join-Path $repositoryRoot "artifacts\jenkins"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

$approvedBy = if ([string]::IsNullOrWhiteSpace($ApprovedBy) -or $ApprovedBy -match '^%.*%$') {
  "UNRECORDED"
} else {
  $ApprovedBy.Trim()
}

# ------------------------------------------------------------------------------------------------
# Restore mode
# ------------------------------------------------------------------------------------------------
if ($RestoreKnownGood) {
  $knownGoodCommit = [string]$cdState['known_good_commit']
  $knownGoodImages = @{}
  foreach ($key in ([hashtable]$cdState['known_good_images']).Keys) {
    $knownGoodImages[$key] = [string]$cdState['known_good_images'][$key]
  }
  if ([string]::IsNullOrWhiteSpace($knownGoodCommit) -or $knownGoodImages.Count -eq 0) {
    throw ("No known-good release is recorded for $ClusterName/$Namespace. " +
      "Run the RAMALS-main job once so a healthy release exists to return to.")
  }

  Write-Host "[restore] Returning $Namespace to known-good main release $knownGoodCommit"
  $restored = Restore-RamalsWorkloadImages -Namespace $Namespace -Images $knownGoodImages
  if (-not $restored) {
    throw "Restore to $knownGoodCommit did not complete. The cluster is mixed and needs a human."
  }

  Write-Host "[restore] Running smoke suite..."
  & pwsh -NoLogo -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\k8s\dev\smoke.ps1") `
    -Namespace $Namespace -ClusterName $ClusterName 2>&1 |
    Tee-Object -FilePath (Join-Path $evidenceDirectory "smoke.log")
  if ($LASTEXITCODE -ne 0) { throw "Smoke suite failed after restoring $knownGoodCommit." }

  # current_commit moves back; known_good_* is untouched because it already describes this release.
  $cdState['state'] = 'HEALTHY'
  $cdState['current_commit'] = $knownGoodCommit
  Set-RamalsCdState -StatePath $statePath -State $cdState

  [ordered]@{
    outcome = "SUCCESS"
    mode = "RESTORE_KNOWN_GOOD"
    restoredCommit = $knownGoodCommit
    approvedBy = $approvedBy
    buildNumber = $env:BUILD_NUMBER
    buildUrl = $env:BUILD_URL
    cluster = $ClusterName
    namespace = $Namespace
    completedAt = [DateTimeOffset]::UtcNow.ToString("O")
  } | ConvertTo-Json | Out-File (Join-Path $evidenceDirectory "summary.json") -Encoding utf8

  Write-Host "[restore] $Namespace is back on $knownGoodCommit."
  return
}

# ------------------------------------------------------------------------------------------------
# Branch deployment
# ------------------------------------------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($Ref)) {
  throw "A branch is required. Pass -Ref <branch>, or -RestoreKnownGood to return to main."
}
$branch = $Ref.Trim() -replace '^origin/', '' -replace '^refs/heads/', ''
if ($branch -notmatch '^[A-Za-z0-9._\-/]+$') {
  # The value reaches git and a container tag, so it is constrained rather than quoted-and-hoped.
  throw "Refusing an implausible branch name: '$Ref'."
}

Invoke-Expression "git fetch --no-tags origin $branch" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Branch '$branch' does not exist on $ExpectedRepository." }
$commit = (git rev-parse FETCH_HEAD).Trim()
if ($commit -notmatch '^[0-9a-f]{40}$') { throw "Could not resolve branch '$branch' to a commit." }
Write-Host "[branch] $branch resolves to $commit"

$shortCommit = $commit.Substring(0, 7)
$ghcrEvidence = Join-Path $evidenceDirectory "ghcr-images.json"

try {
  # Checked before anything is touched, and cheaply. prepare-ghcr-images.ps1 would otherwise poll
  # for twenty minutes holding the only Windows agent, and the usual reason the image is missing is
  # simply that nobody published one for this branch -- which is a sentence, not a wait.
  $probe = "ghcr.io/skpandey15/ramals-learning-platform:sha-$commit"
  & docker manifest inspect $probe *> $null
  if ($LASTEXITCODE -ne 0) {
    throw ("No published image for $branch ($shortCommit). Release only publishes from main and " +
      "tags, so a branch needs one dispatched first:`n" +
      "    gh workflow run release.yml --ref $branch`n" +
      "Wait for it to finish, then re-run this job.")
  }

  Write-Host "[branch] Mirroring images for $shortCommit into the local registry..."
  & pwsh -NoLogo -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\jenkins\prepare-ghcr-images.ps1") `
    -Commit $commit -EvidencePath $ghcrEvidence -WaitMinutes $WaitMinutes
  if ($LASTEXITCODE -ne 0) { throw "Preparing images for $branch failed." }

  Write-Host "[branch] Deploying $branch to $Namespace (this replaces what is running)..."
  & pwsh -NoLogo -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\k8s\dev\bootstrap.ps1") `
    -Namespace $Namespace -ClusterName $ClusterName `
    -ApplicationImageTag $shortCommit -InfrastructureImageTag $shortCommit -SkipBuild
  if ($LASTEXITCODE -ne 0) { throw "Bootstrap failed for $branch." }

  Write-Host "[branch] Running smoke suite..."
  & pwsh -NoLogo -NoProfile -NonInteractive -File `
    (Join-Path $repositoryRoot "deploy\k8s\dev\smoke.ps1") `
    -Namespace $Namespace -ClusterName $ClusterName 2>&1 |
    Tee-Object -FilePath (Join-Path $evidenceDirectory "smoke.log")
  $smokePassed = ($LASTEXITCODE -eq 0)

  kubectl get pods -n $Namespace -o wide |
    Out-File (Join-Path $evidenceDirectory "pods.txt") -Encoding utf8

  # BRANCH_DEPLOYED, never HEALTHY. HEALTHY is the release path's word for "this is what the
  # environment should be running", and a branch is never that -- so known_good_commit and
  # known_good_images are read here and never written. Whatever main release was last healthy stays
  # the thing -RestoreKnownGood returns to.
  $cdState['state'] = 'BRANCH_DEPLOYED'
  $cdState['current_commit'] = $commit
  Set-RamalsCdState -StatePath $statePath -State $cdState

  [ordered]@{
    outcome = if ($smokePassed) { "SUCCESS" } else { "SMOKE_FAILED" }
    mode = "BRANCH"
    state = "BRANCH_DEPLOYED"
    branch = $branch
    branchCommit = $commit
    knownGoodMainCommit = [string]$cdState['known_good_commit']
    approvedBy = $approvedBy
    buildNumber = $env:BUILD_NUMBER
    buildUrl = $env:BUILD_URL
    cluster = $ClusterName
    namespace = $Namespace
    completedAt = [DateTimeOffset]::UtcNow.ToString("O")
  } | ConvertTo-Json | Out-File (Join-Path $evidenceDirectory "summary.json") -Encoding utf8

  if (-not $smokePassed) {
    # Not rolled back automatically. A branch is deployed in order to be examined, and a scratch
    # environment that reverts the moment the thing under test misbehaves cannot be examined. The
    # way back is one switch, and it is named here so nobody has to go looking for it.
    Write-Host ""
    Write-Host "[branch] Smoke FAILED for $branch. The branch is left running on purpose so it can"
    Write-Host "[branch] be inspected. Re-run this job with RESTORE_KNOWN_GOOD to return"
    Write-Host "[branch] $Namespace to main $($cdState['known_good_commit'])."
    exit 1
  }

  Write-Host "[branch] $branch deployed to $Namespace and smoke-tested."
  Write-Host "[branch] Return with RESTORE_KNOWN_GOOD -> main $($cdState['known_good_commit'])."
} catch {
  $failureReason = ConvertTo-PlainLogText $_.Exception.Message
  $cdState['state'] = 'BRANCH_DEPLOYED'
  $cdState['current_commit'] = $commit
  Set-RamalsCdState -StatePath $statePath -State $cdState

  [ordered]@{
    outcome = "FAILED"
    mode = "BRANCH"
    branch = $branch
    branchCommit = $commit
    knownGoodMainCommit = [string]$cdState['known_good_commit']
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
  Write-Host "Return with RESTORE_KNOWN_GOOD -> main $($cdState['known_good_commit'])."
  exit 1
}
