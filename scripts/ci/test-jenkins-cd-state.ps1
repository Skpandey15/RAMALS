<#
.SYNOPSIS
Exercises the Jenkins CD rollback state machine without a cluster.

.DESCRIPTION
The rollback path only runs when a deployment has already gone wrong, which is the worst possible
time to discover it does not work. kubectl is injectable for exactly this reason -- the same choice
deploy-controller.sh makes about its container runtime -- so every transition can be driven here
against a recorded fake.

What is checked is the behaviour that matters when the environment is broken: that a known-good
version is captured before it can be lost, that a failed release is held so polling cannot redeploy
it every two minutes, that a partial restore is reported as a failure rather than as success, and
that a first deployment with nothing to roll back to says so instead of pretending.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $repositoryRoot "deploy\jenkins\cd-state.ps1")

$script:failures = 0
function Assert-True([bool]$Condition, [string]$Message) {
  if ($Condition) {
    Write-Host ("ok   {0}" -f $Message)
  } else {
    Write-Host ("FAIL {0}" -f $Message)
    $script:failures++
  }
}

$scratch = Join-Path ([IO.Path]::GetTempPath()) ("ramals-cd-state-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

try {
  # --- state file ------------------------------------------------------------------------------
  $statePath = Get-RamalsCdStatePath -ClusterName 'test-cluster' -Namespace 'test-ns' -Root $scratch
  Assert-True ($statePath -like "*test-cluster-test-ns.json") `
    "the state file is keyed by cluster and namespace"

  $fresh = Get-RamalsCdState -StatePath $statePath
  # Absent history must not read as a healthy history: a first run has no known-good version, and
  # claiming one would hand a rollback an image reference that never ran.
  Assert-True ($fresh['state'] -eq 'UNKNOWN') "a missing state file reads as UNKNOWN, not HEALTHY"
  Assert-True ($fresh['known_good_commit'] -eq '') "a missing state file claims no known-good commit"
  Assert-True ($fresh['known_good_images'].Count -eq 0) "a missing state file claims no known-good images"

  $fresh['state'] = 'HEALTHY'
  $fresh['known_good_commit'] = 'a' * 40
  $fresh['known_good_images'] = @{ 'deployment/web-ui' = 'registry/web-ui:aaaaaaa' }
  Set-RamalsCdState -StatePath $statePath -State $fresh
  $reloaded = Get-RamalsCdState -StatePath $statePath
  Assert-True ($reloaded['known_good_commit'] -eq ('a' * 40)) "known-good commit survives a round trip"
  Assert-True ($reloaded['known_good_images']['deployment/web-ui'] -eq 'registry/web-ui:aaaaaaa') `
    "known-good image references survive a round trip"
  Assert-True (-not [string]::IsNullOrWhiteSpace($reloaded['updated_at'])) `
    "every write stamps when it happened"

  # --- anti-flapping ---------------------------------------------------------------------------
  $bad = 'b' * 40
  Assert-True (-not (Test-RamalsReleaseHeld -State $reloaded -Commit $bad)) `
    "a version nobody has failed is not held"
  $withHold = Add-RamalsHeldRelease -State $reloaded -Commit $bad
  Assert-True (Test-RamalsReleaseHeld -State $withHold -Commit $bad) `
    "a failed version is held"
  # Idempotent, because the same commit can fail more than once before anyone corrects it and a
  # held list that grows a duplicate per attempt is a held list nobody reads.
  $withHold = Add-RamalsHeldRelease -State $withHold -Commit $bad
  Assert-True (@(@($withHold['held_versions']) | Where-Object { $_ -eq $bad }).Count -eq 1) `
    "holding the same version twice records it once"
  Assert-True (-not (Test-RamalsReleaseHeld -State $withHold -Commit ('c' * 40))) `
    "holding one version does not hold another"
  Set-RamalsCdState -StatePath $statePath -State $withHold
  Assert-True (Test-RamalsReleaseHeld -State (Get-RamalsCdState -StatePath $statePath) -Commit $bad) `
    "a hold survives a restart of the agent"

  # --- capture -----------------------------------------------------------------------------------
  $present = {
    param($Arguments)
    $global:LASTEXITCODE = 0
    return "registry/$(($Arguments[1] -split '/')[1]):good"
  }
  $captured = Get-RamalsWorkloadImages -Namespace 'test-ns' -Kubectl $present
  Assert-True ($captured.Count -eq 6) "every release-managed workload is captured"
  Assert-True ($captured['deployment/learning-platform'] -eq 'registry/learning-platform:good') `
    "the captured reference is what the workload was running"
  # mailpit is pinned upstream and is not part of a release, so a RAMALS rollback has no known-good
  # version of it to restore and must not pretend otherwise.
  Assert-True (-not $captured.ContainsKey('deployment/mailpit')) `
    "workloads outside the release are not captured"

  $absent = { param($Arguments) $global:LASTEXITCODE = 1; return "" }
  Assert-True ((Get-RamalsWorkloadImages -Namespace 'test-ns' -Kubectl $absent).Count -eq 0) `
    "a cluster with no workloads yields nothing to roll back to"

  # --- restore -----------------------------------------------------------------------------------
  $script:invoked = [System.Collections.Generic.List[string]]::new()
  $succeeds = {
    param($Arguments)
    $script:invoked.Add(($Arguments -join ' '))
    $global:LASTEXITCODE = 0
    return ""
  }
  $images = @{
    'deployment/web-ui' = 'registry/web-ui:aaaaaaa'
    'statefulset/postgres' = 'registry/postgres:aaaaaaa'
  }
  Assert-True (Restore-RamalsWorkloadImages -Namespace 'test-ns' -Images $images -Kubectl $succeeds) `
    "restoring every workload reports success"
  Assert-True (@($script:invoked | Where-Object { $_ -like 'set image deployment/web-ui web-ui=registry/web-ui:aaaaaaa*' }).Count -eq 1) `
    "the container is set back to the exact recorded reference"
  # `rollout undo` walks back one revision, which is the known-good version only if exactly one
  # deployment happened since. The recorded reference is what was actually healthy.
  Assert-True (@($script:invoked | Where-Object { $_ -like 'rollout undo*' }).Count -eq 0) `
    "restore targets the recorded reference rather than the previous revision"
  Assert-True (@($script:invoked | Where-Object { $_ -like 'rollout status*' }).Count -eq 2) `
    "restore waits for every workload to become ready"

  $failsOnSet = {
    param($Arguments)
    $global:LASTEXITCODE = if ($Arguments[0] -eq 'set') { 1 } else { 0 }
    return ""
  }
  # A cluster half on the old version and half on the new one is the state this mechanism exists to
  # avoid, so it must never be reported as a successful rollback.
  Assert-True (-not (Restore-RamalsWorkloadImages -Namespace 'test-ns' -Images $images -Kubectl $failsOnSet)) `
    "a workload that cannot be set back fails the rollback"

  $failsOnStatus = {
    param($Arguments)
    $global:LASTEXITCODE = if ($Arguments[0] -eq 'rollout') { 1 } else { 0 }
    return ""
  }
  Assert-True (-not (Restore-RamalsWorkloadImages -Namespace 'test-ns' -Images $images -Kubectl $failsOnStatus)) `
    "a workload that never becomes ready fails the rollback"

  Assert-True (-not (Restore-RamalsWorkloadImages -Namespace 'test-ns' -Images @{} -Kubectl $succeeds)) `
    "a rollback with nothing recorded reports failure rather than success"
} finally {
  Remove-Item -Recurse -Force $scratch -ErrorAction SilentlyContinue
}

Write-Host ""
if ($script:failures -gt 0) {
  Write-Host "$($script:failures) Jenkins CD state check(s) failed."
  exit 1
}
Write-Host "All Jenkins CD rollback state checks passed."
# Explicit, because the fakes above deliberately set $LASTEXITCODE to 1 to simulate a kubectl
# failure, and a script that ends without exiting inherits it. Without this the suite printed that
# every check passed and then failed the build -- which is the same defect in the other direction:
# an exit status that does not mean what the output says.
exit 0
