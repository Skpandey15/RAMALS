<#
.SYNOPSIS
Durable deployment state and rollback for the Jenkins CD boundary.

.DESCRIPTION
The push-based Jenkins path deploys and verifies, but until now it could not undo. A failed smoke
suite left the cluster running the bad release with only a red build to say so -- the Jenkinsfile's
own abort message admits it: "can leave images pushed and workloads half-rolled."

The pull-based controller in deploy/deploy-controller.sh already solves this for the shared
environment, and its state machine is the one modelled here rather than a second one invented
alongside it:

  APPROVED -> DEPLOYING -> HEALTHY
                       \-> FAILED -> ROLLBACK -> ROLLED_BACK -> RELEASE_HELD

Two properties are carried over deliberately.

**Known-good image references, not just a commit.** By the time a rollback is needed the workspace
already describes the bad version, so a commit alone is nothing to return to. The exact image
references that were running and healthy are captured before anything is applied.

**Anti-flapping.** A version that failed its health gates and was rolled back is recorded in
`held_versions` and is not deployed again automatically. Polling every two minutes against a branch
that still has the bad commit at its head would otherwise redeploy, fail, and roll back forever.

The state file lives outside the Jenkins workspace on purpose: `deleteDir()` empties that workspace
on every build, so state kept there would be destroyed exactly when it is needed -- on the run after
the one that failed.

kubectl is injectable so the state machine can be tested without a cluster, the same way
deploy-controller.sh makes its container runtime injectable.
#>

Set-StrictMode -Version Latest

# The workloads whose images are captured and restored. Deployments and the StatefulSet that the
# release actually re-points; mailpit is excluded because it is not part of a release and its image
# is pinned upstream, so it has no known-good version that a RAMALS rollback could restore.
$script:RamalsRollbackWorkloads = @(
  'deployment/learning-platform',
  'deployment/web-ui',
  'deployment/ramals-ai',
  'deployment/keycloak',
  'deployment/sms-sink',
  'statefulset/postgres'
)

function Invoke-RamalsKubectl {
  param(
    [Parameter(Mandatory)][string[]]$Arguments,
    [scriptblock]$Kubectl
  )
  if ($Kubectl) { return & $Kubectl $Arguments }
  return & kubectl @Arguments
}

function Get-RamalsCdStatePath {
  <#
    Outside the workspace, keyed by cluster and namespace so two environments on one agent cannot
    overwrite each other's known-good record.
  #>
  param(
    [Parameter(Mandatory)][string]$ClusterName,
    [Parameter(Mandatory)][string]$Namespace,
    [string]$Root
  )
  if (-not $Root) {
    $base = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [IO.Path]::GetTempPath() }
    $Root = Join-Path $base "RAMALS\cd-state"
  }
  New-Item -ItemType Directory -Force -Path $Root | Out-Null
  return (Join-Path $Root "$ClusterName-$Namespace.json")
}

function Get-RamalsCdState {
  param([Parameter(Mandatory)][string]$StatePath)
  if (-not (Test-Path $StatePath)) {
    # A first deployment has no history. Absent is not the same as healthy, so the state is
    # UNKNOWN rather than HEALTHY: nothing here may claim a known-good version that never existed.
    return [ordered]@{
      state                = 'UNKNOWN'
      current_commit       = ''
      known_good_commit    = ''
      known_good_images    = @{}
      held_versions        = @()
      failure_count        = 0
      updated_at           = ''
    }
  }
  $raw = Get-Content $StatePath -Raw | ConvertFrom-Json
  $images = @{}
  if ($raw.PSObject.Properties.Name -contains 'known_good_images' -and $raw.known_good_images) {
    foreach ($property in $raw.known_good_images.PSObject.Properties) {
      $images[$property.Name] = [string]$property.Value
    }
  }
  return [ordered]@{
    state             = [string]$raw.state
    current_commit    = [string]$raw.current_commit
    known_good_commit = [string]$raw.known_good_commit
    known_good_images = $images
    held_versions     = @($raw.held_versions)
    failure_count     = [int]$raw.failure_count
    updated_at        = [string]$raw.updated_at
  }
}

function Set-RamalsCdState {
  param(
    [Parameter(Mandatory)][string]$StatePath,
    [Parameter(Mandatory)][hashtable]$State
  )
  $State['updated_at'] = [DateTimeOffset]::UtcNow.ToString('O')
  $directory = Split-Path -Parent $StatePath
  if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
  ($State | ConvertTo-Json -Depth 6) | Out-File $StatePath -Encoding utf8
}

function Test-RamalsReleaseHeld {
  param(
    [Parameter(Mandatory)][hashtable]$State,
    [Parameter(Mandatory)][string]$Commit
  )
  return ([string[]]$State['held_versions']) -contains $Commit
}

function Get-RamalsWorkloadImages {
  <#
    The image reference each workload is running right now.

    Read from the live cluster rather than from the rendered manifest: the manifest describes what
    is being deployed, and a rollback needs what was deployed. A workload that does not exist yet
    is omitted, so a first deployment simply has nothing to restore rather than recording an empty
    reference that a later rollback would apply.
  #>
  param(
    [Parameter(Mandatory)][string]$Namespace,
    [scriptblock]$Kubectl
  )
  $images = @{}
  foreach ($workload in $script:RamalsRollbackWorkloads) {
    $reference = Invoke-RamalsKubectl -Kubectl $Kubectl -Arguments @(
      'get', $workload, '-n', $Namespace,
      '-o', 'jsonpath={.spec.template.spec.containers[0].image}')
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($reference)) {
      $images[$workload] = ([string]$reference).Trim()
    }
  }
  return $images
}

function Restore-RamalsWorkloadImages {
  <#
    Puts every captured workload back on the image it was running, then waits for each to become
    ready again.

    `kubectl set image` against the recorded reference rather than `rollout undo`: undo walks back
    one revision, which is only the known-good version if exactly one deployment happened since --
    and the failing build may have rolled several workloads. The recorded reference is what was
    actually healthy, whatever happened in between.

    Returns $true only if every workload was restored and became ready. A partial restore is
    reported as a failure, because a cluster half on the old version and half on the new one is the
    state this whole mechanism exists to avoid.
  #>
  param(
    [Parameter(Mandatory)][string]$Namespace,
    [Parameter(Mandatory)][hashtable]$Images,
    [scriptblock]$Kubectl,
    [int]$TimeoutSeconds = 420
  )
  if ($Images.Count -eq 0) {
    Write-Host "[rollback] No known-good images recorded; nothing to restore."
    return $false
  }

  $restored = $true
  foreach ($workload in $Images.Keys) {
    $reference = $Images[$workload]
    $container = ($workload -split '/')[1]
    Write-Host "[rollback] $workload -> $reference"
    Invoke-RamalsKubectl -Kubectl $Kubectl -Arguments @(
      'set', 'image', $workload, "$container=$reference", '-n', $Namespace) | Out-Host
    if ($LASTEXITCODE -ne 0) {
      Write-Host "[rollback] FAILED to set image for $workload"
      $restored = $false
    }
  }

  foreach ($workload in $Images.Keys) {
    Invoke-RamalsKubectl -Kubectl $Kubectl -Arguments @(
      'rollout', 'status', $workload, '-n', $Namespace,
      "--timeout=${TimeoutSeconds}s") | Out-Host
    if ($LASTEXITCODE -ne 0) {
      Write-Host "[rollback] $workload did not become ready after restore"
      $restored = $false
    }
  }
  return $restored
}

function Add-RamalsHeldRelease {
  <#
    Records a version that failed its health gates and was rolled back.

    Held versions are not redeployed automatically. Jenkins polls main every two minutes, so
    without this a bad commit at the head of main would be deployed, fail, roll back, and be
    deployed again on the next poll -- indefinitely, each cycle taking the environment down and
    back up. A human has to correct the commit or explicitly override.
  #>
  param(
    [Parameter(Mandatory)][hashtable]$State,
    [Parameter(Mandatory)][string]$Commit
  )
  $held = [System.Collections.Generic.List[string]]::new()
  foreach ($existing in ([string[]]$State['held_versions'])) {
    if ($existing) { $held.Add($existing) }
  }
  if (-not $held.Contains($Commit)) { $held.Add($Commit) }
  $State['held_versions'] = $held.ToArray()
  return $State
}
