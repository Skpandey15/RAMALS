# Harness-level tests for the M2-T15.2 diagnostic dispatch-ownership proof.
#
# These run offline. They never touch Kubernetes or PostgreSQL, and they do not execute the
# Phase-2 pod-death scenario. Their whole purpose is the negative direction: each perturbation
# below is a way the #154 recovery state machine could be violated, and every one of them must be
# rejected. The final case goes further and removes a proof from a temporary copy of the module,
# showing the harness would accept the bad scenario without it.

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "dispatch-ownership-proof.ps1")

$script:RequestId = "diag-req-0190000000007000"
$script:CommissionEventId = "01900000-0000-7000-8000-0000000000c1"
$script:ContextId = "ctx-0190000000007000"
$script:ContextAsOf = "2026-08-26T03:00:00.000000Z"
$script:OwnerToken = "01900000-0000-7000-8000-0000000000d1"

function New-DispatchRow {
  param(
    [string]$State = "AVAILABLE",
    [AllowNull()][string]$OwnerToken = $null,
    [long]$Fence = 0,
    [AllowNull()][string]$OwnershipAcquiredAt = $null,
    [AllowNull()][string]$InvocationStartedAt = $null,
    [string]$RequestId = $script:RequestId,
    [string]$CommissionEventId = $script:CommissionEventId,
    [string]$ContextId = $script:ContextId,
    [string]$ContextAsOf = $script:ContextAsOf
  )
  return [pscustomobject]@{
    requestId = $RequestId
    commissionEventId = $CommissionEventId
    state = $State
    ownerToken = $OwnerToken
    fence = $Fence
    contextId = $ContextId
    contextAsOf = $ContextAsOf
    commissionedAt = "2026-08-26T03:00:01.000000Z"
    ownershipAcquiredAt = $OwnershipAcquiredAt
    invocationStartedAt = $InvocationStartedAt
  }
}

function New-DispatchCheckpoint {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [AllowNull()]$Dispatch,
    [int]$ProviderInvocationCount = 0,
    [int]$CommissionCount = 1,
    [int]$TerminalCount = 0,
    [int]$GateCount = 0
  )
  return [pscustomobject]@{
    name = $Name
    capturedAtUtc = "2026-08-26T03:00:00.000000Z"
    dispatch = $Dispatch
    rowCount = if ($null -eq $Dispatch) { 0 } else { 1 }
    providerInvocationCount = $ProviderInvocationCount
    commissionCount = $CommissionCount
    terminalCount = $TerminalCount
    gateCount = $GateCount
  }
}

# One clean, fully compliant observation of the #154 recovery state machine.
function New-ValidDispatchObservation {
  $ownedRow = New-DispatchRow -State "DISPATCH_OWNED" -OwnerToken $script:OwnerToken -Fence 1 `
    -OwnershipAcquiredAt "2026-08-26T03:02:10.000000Z"
  $inFlightRow = New-DispatchRow -State "IN_FLIGHT" -OwnerToken $script:OwnerToken -Fence 1 `
    -OwnershipAcquiredAt "2026-08-26T03:02:10.000000Z" `
    -InvocationStartedAt "2026-08-26T03:02:10.250000Z"

  return [pscustomobject]@{
    schema = "m2-t15.dispatch-ownership-proof.v1"
    requestId = $script:RequestId
    checkpoints = @(
      (New-DispatchCheckpoint -Name "after-commission-before-death" -Dispatch (New-DispatchRow)),
      (New-DispatchCheckpoint -Name "after-death-before-reclaim" -Dispatch (New-DispatchRow)),
      (New-DispatchCheckpoint -Name "replacement-held" -Dispatch (New-DispatchRow)),
      (New-DispatchCheckpoint -Name "after-dispatch-acquisition" -Dispatch $ownedRow),
      (New-DispatchCheckpoint -Name "in-flight-before-provider" -Dispatch $inFlightRow),
      (New-DispatchCheckpoint -Name "final" -Dispatch $inFlightRow `
        -ProviderInvocationCount 1 -TerminalCount 1 -GateCount 1)
    )
    transitionSamples = @(
      [pscustomobject]@{ observedAtUtc = "2026-08-26T03:02:09.000000Z"; state = "AVAILABLE"; ownerToken = $null; fence = 0 },
      [pscustomobject]@{ observedAtUtc = "2026-08-26T03:02:10.100000Z"; state = "DISPATCH_OWNED"; ownerToken = $script:OwnerToken; fence = 1 },
      [pscustomobject]@{ observedAtUtc = "2026-08-26T03:02:10.300000Z"; state = "IN_FLIGHT"; ownerToken = $script:OwnerToken; fence = 1 }
    )
    claimA = [pscustomobject]@{
      executionToken = "01900000-0000-7000-8000-0000000000a1"
      attemptCount = 1
      claimedAt = "2026-08-26T03:00:00.500000Z"
      status = "RUNNING"
    }
    claimB = [pscustomobject]@{
      executionToken = "01900000-0000-7000-8000-0000000000b2"
      attemptCount = 2
      claimedAt = "2026-08-26T03:02:00.000000Z"
      status = "RUNNING"
    }
    preReclaimClaimedAt = @(
      "2026-08-26T03:00:00.500000Z",
      "2026-08-26T03:00:00.500000Z",
      "2026-08-26T03:00:00.500000Z"
    )
    naturalLease = [pscustomobject]@{
      expired = $true
      leaseExpiresAt = "2026-08-26T03:01:00.500000Z"
      leaseSeconds = 60
    }
    podUidA = "0190aaaa-0000-7000-8000-00000000000a"
    podUidB = "0190bbbb-0000-7000-8000-00000000000b"
    finalCounts = [pscustomobject]@{
      evidence = 1
      mastery = 1
      diagnosticExecution = 1
      diagnosticExecutionStatus = "SUCCEEDED"
      diagnosticExecutionError = ""
      diagnosticCommission = 1
      diagnosticTerminal = 1
      diagnosticGate = 1
      outbox = 1
    }
    workflow = [pscustomobject]@{ status = "COMPLETED"; terminalReason = "WORKFLOW_COMPLETED" }
    cursorHistoryResult = "PASS"
  }
}

function Get-DispatchCheckpointByName {
  param($Observation, [string]$Name)
  return @($Observation.checkpoints | Where-Object { [string]$_.name -eq $Name })[0]
}

$script:Controls = [System.Collections.Generic.List[object]]::new()

function Test-DispatchNegativeControl {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$ExpectedCheck,
    [Parameter(Mandatory = $true)][scriptblock]$Mutate
  )
  $observation = New-ValidDispatchObservation
  & $Mutate $observation
  $rejected = $false
  $rejection = ""
  try {
    [void](Assert-DiagnosticDispatchOwnershipProof $observation)
  } catch {
    $rejected = $true
    $rejection = $_.Exception.Message
  }
  if (-not $rejected) {
    throw "negative control '$Name' was accepted by the dispatch-ownership proof"
  }
  if (-not $rejection.Contains($ExpectedCheck)) {
    throw "negative control '$Name' failed for the wrong reason: $rejection"
  }
  [void]$script:Controls.Add([ordered]@{
      name = $Name
      expected = "FAIL"
      result = "PASS"
      rejectedBy = $ExpectedCheck
    })
}

# ---------------------------------------------------------------------------------------------
# Positive control: the compliant observation must pass.
# ---------------------------------------------------------------------------------------------
$proof = Assert-DiagnosticDispatchOwnershipProof (New-ValidDispatchObservation)
if ([string]$proof.result -ne "PASS") {
  throw "the compliant dispatch-ownership observation did not pass"
}
if ([string]$proof.acquisitionEvidence -ne "sampled" -or
    [string]$proof.inFlightEvidence -ne "sampled") {
  throw "the compliant observation should record directly sampled transition evidence"
}
if (@($proof.checks | Where-Object { $_.result -ne "PASS" }).Count -ne 0) {
  throw "the compliant observation produced a failing check"
}

# A run that never catches the intermediate states must still pass on durable reconstruction --
# fence, ownership_acquired_at and invocation_started_at are sufficient.
$durableOnly = New-ValidDispatchObservation
(Get-DispatchCheckpointByName $durableOnly "after-dispatch-acquisition").dispatch = $null
(Get-DispatchCheckpointByName $durableOnly "in-flight-before-provider").dispatch = $null
$durableOnly.transitionSamples = @()
$durableProof = Assert-DiagnosticDispatchOwnershipProof $durableOnly
if ([string]$durableProof.acquisitionEvidence -ne "durable-reconstruction" -or
    [string]$durableProof.inFlightEvidence -ne "durable-reconstruction") {
  throw "the unsampled observation should record durable-reconstruction evidence"
}

# ---------------------------------------------------------------------------------------------
# Negative controls: one per fail-closed condition.
# ---------------------------------------------------------------------------------------------
Test-DispatchNegativeControl "provider invocation never happened" "exactly one provider invocation" {
  param($o)
  (Get-DispatchCheckpointByName $o "final").providerInvocationCount = 0
}

Test-DispatchNegativeControl "provider invoked twice" "exactly one provider invocation" {
  param($o)
  (Get-DispatchCheckpointByName $o "final").providerInvocationCount = 2
}

Test-DispatchNegativeControl "provider invoked before A died" "provider invocation count before A death is 0" {
  param($o)
  (Get-DispatchCheckpointByName $o "after-commission-before-death").providerInvocationCount = 1
}

Test-DispatchNegativeControl "request identity changed after reclaim" "the same diagnostic request identity" {
  param($o)
  (Get-DispatchCheckpointByName $o "final").dispatch.requestId = "diag-req-0190000000007099"
}

Test-DispatchNegativeControl "grounded context identity changed" "context_id and context_as_of are preserved" {
  param($o)
  (Get-DispatchCheckpointByName $o "final").dispatch.contextId = "ctx-0190000000007099"
}

Test-DispatchNegativeControl "grounded context asOf changed" "context_id and context_as_of are preserved" {
  param($o)
  (Get-DispatchCheckpointByName $o "final").dispatch.contextAsOf = "2026-08-26T03:05:00.000000Z"
}

Test-DispatchNegativeControl "two dispatch winners" "exactly one claimant won dispatch ownership" {
  param($o)
  $o.transitionSamples[1].ownerToken = "01900000-0000-7000-8000-0000000000d9"
}

Test-DispatchNegativeControl "owner token cannot be proven" "dispatch winner has a provable owner token and fence" {
  param($o)
  foreach ($name in @("after-dispatch-acquisition", "in-flight-before-provider", "final")) {
    (Get-DispatchCheckpointByName $o $name).dispatch.ownerToken = $null
  }
  $o.transitionSamples = @($o.transitionSamples[0])
}

Test-DispatchNegativeControl "a second commission appeared" "exactly one durable commission exists throughout" {
  param($o)
  (Get-DispatchCheckpointByName $o "final").dispatch.commissionEventId =
    "01900000-0000-7000-8000-0000000000c9"
}

Test-DispatchNegativeControl "redispatch from DISPATCH_OWNED" "no redispatch from DISPATCH_OWNED or IN_FLIGHT" {
  param($o)
  $o.transitionSamples = @(
    $o.transitionSamples[0],
    $o.transitionSamples[1],
    [pscustomobject]@{ observedAtUtc = "2026-08-26T03:02:10.200000Z"; state = "AVAILABLE"; ownerToken = $null; fence = 1 },
    $o.transitionSamples[2]
  )
}

Test-DispatchNegativeControl "redispatch from IN_FLIGHT" "no redispatch from DISPATCH_OWNED or IN_FLIGHT" {
  param($o)
  $o.transitionSamples = @(
    $o.transitionSamples[0],
    $o.transitionSamples[1],
    $o.transitionSamples[2],
    [pscustomobject]@{ observedAtUtc = "2026-08-26T03:02:11.000000Z"; state = "DISPATCH_OWNED"; ownerToken = $script:OwnerToken; fence = 1 }
  )
}

# A redispatch that the sampler never caught still leaves a durable trace: the acquisition CAS
# increments the fence, so fence > 1 is by itself proof that ownership was granted twice.
Test-DispatchNegativeControl "durable redispatch shown by a bumped fence" "exactly one claimant won dispatch ownership" {
  param($o)
  foreach ($name in @("after-dispatch-acquisition", "in-flight-before-provider", "final")) {
    (Get-DispatchCheckpointByName $o $name).dispatch.fence = 2
  }
  $o.transitionSamples[1].fence = 2
  $o.transitionSamples[2].fence = 2
}

Test-DispatchNegativeControl "commission was already owned before A died" "is an ownerless AVAILABLE commission" {
  param($o)
  $row = (Get-DispatchCheckpointByName $o "after-commission-before-death").dispatch
  $row.state = "DISPATCH_OWNED"
  $row.ownerToken = $script:OwnerToken
  $row.fence = 1
  $row.ownershipAcquiredAt = "2026-08-26T03:00:02.000000Z"
}

Test-DispatchNegativeControl "legacy indeterminate commission was dispatched" "is an ownerless AVAILABLE commission" {
  param($o)
  $row = (Get-DispatchCheckpointByName $o "after-death-before-reclaim").dispatch
  $row.state = "LEGACY_INDETERMINATE"
  $row.contextId = $null
  $row.contextAsOf = $null
}

Test-DispatchNegativeControl "claimed_at was rewritten to force expiry" "natural lease expiry did not mutate claimed_at" {
  param($o)
  $o.preReclaimClaimedAt = @(
    "2026-08-26T03:00:00.500000Z",
    "2026-08-26T02:58:00.000000Z",
    "2026-08-26T03:00:00.500000Z"
  )
}

Test-DispatchNegativeControl "B reclaimed before the lease expired" "B reclaimed only after natural production lease expiry" {
  param($o)
  $o.claimB.claimedAt = "2026-08-26T03:00:30.000000Z"
}

Test-DispatchNegativeControl "B reused worker A's execution token" "workflow execution token A differs from token B" {
  param($o)
  $o.claimB.executionToken = $o.claimA.executionToken
}

Test-DispatchNegativeControl "attempt was not incremented" "B reclaimed as attempt 2" {
  param($o)
  $o.claimB.attemptCount = 1
}

Test-DispatchNegativeControl "the same pod claimed twice" "A and B are distinct backend pod UIDs" {
  param($o)
  $o.podUidB = $o.podUidA
}

Test-DispatchNegativeControl "provider invocation was never fenced" "authorized DISPATCH_OWNED -> IN_FLIGHT" {
  param($o)
  $row = (Get-DispatchCheckpointByName $o "final").dispatch
  $row.state = "DISPATCH_OWNED"
  $row.invocationStartedAt = $null
  $o.transitionSamples = @($o.transitionSamples[0], $o.transitionSamples[1])
}

Test-DispatchNegativeControl "duplicate downstream adaptation work" "no duplicate downstream evidence, mastery or adaptation work" {
  param($o)
  $o.finalCounts.outbox = 2
}

Test-DispatchNegativeControl "two terminal executions" "exactly one terminal AI execution" {
  param($o)
  $o.finalCounts.diagnosticTerminal = 2
}

Test-DispatchNegativeControl "execution ended abandoned" "exactly one terminal AI execution" {
  param($o)
  $o.finalCounts.diagnosticExecutionStatus = "FAILED"
  $o.finalCounts.diagnosticExecutionError = "AI_EXECUTION_ABANDONED"
}

Test-DispatchNegativeControl "two gate decisions" "exactly one authoritative diagnostic gate decision" {
  param($o)
  $o.finalCounts.diagnosticGate = 2
}

Test-DispatchNegativeControl "workflow did not complete" "workflow completed" {
  param($o)
  $o.workflow.status = "FAILED"
  $o.workflow.terminalReason = "DIAGNOSIS_EXECUTION_ABANDONED"
}

Test-DispatchNegativeControl "cursor history did not pass" "cursor history passed" {
  param($o)
  $o.cursorHistoryResult = "FAIL"
}

# ---------------------------------------------------------------------------------------------
# The headline fail-closed rule: perfect aggregate counts, no dispatch-state evidence.
# ---------------------------------------------------------------------------------------------
$countsOnly = New-ValidDispatchObservation
(Get-DispatchCheckpointByName $countsOnly "final").dispatch = $null
(Get-DispatchCheckpointByName $countsOnly "final").rowCount = 0
$countsOnlyRejected = $false
$countsOnlyRejection = ""
try {
  [void](Assert-DiagnosticDispatchOwnershipProof $countsOnly)
} catch {
  $countsOnlyRejected = $true
  $countsOnlyRejection = $_.Exception.Message
}
if (-not $countsOnlyRejected -or
    -not $countsOnlyRejection.Contains("cannot be inferred from row counts alone")) {
  throw "a counts-only observation with no dispatch evidence was not rejected: $countsOnlyRejection"
}
[void]$script:Controls.Add([ordered]@{
    name = "final success inferred from row counts with no dispatch evidence"
    expected = "FAIL"
    result = "PASS"
    rejectedBy = "dispatch evidence presence guard"
  })

# ---------------------------------------------------------------------------------------------
# Mutation control: remove the single-winner proof from a temporary copy of the module and show
# the harness then accepts a two-winner scenario. Without this, a deleted proof would look like a
# passing suite.
# ---------------------------------------------------------------------------------------------
$modulePath = Join-Path $PSScriptRoot "dispatch-ownership-proof.ps1"
$mutatedRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ramals-t15-dispatch-mutation-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $mutatedRoot -Force | Out-Null
$bypassAccepted = $false
try {
  $mutatedPath = Join-Path $mutatedRoot "dispatch-ownership-proof.ps1"
  $source = Get-Content -LiteralPath $modulePath -Raw
  $needle = '$singleWinner = $allTokens.Count -eq 1 -and [long]$finalRow.fence -eq 1'
  if (-not $source.Contains($needle)) {
    throw "the single-winner proof could not be located for the mutation control"
  }
  $mutated = $source.Replace($needle, '$singleWinner = $true')
  [System.IO.File]::WriteAllText($mutatedPath, $mutated, [System.Text.UTF8Encoding]::new($false))

  $probe = Join-Path $mutatedRoot "probe.ps1"
  $probeSource = @'
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "dispatch-ownership-proof.ps1")
. (Join-Path $args[0] "dispatch-ownership-proof.fixtures.ps1")
$observation = New-ValidDispatchObservation
$observation.transitionSamples[1].ownerToken = "01900000-0000-7000-8000-0000000000d9"
[void](Assert-DiagnosticDispatchOwnershipProof $observation)
Write-Output "BYPASS-ACCEPTED"
'@
  [System.IO.File]::WriteAllText($probe, $probeSource, [System.Text.UTF8Encoding]::new($false))

  # Share this file's fixture builders with the probe without re-declaring them.
  $fixtureSource = (Get-Content -LiteralPath $PSCommandPath -Raw)
  $fixtureEnd = $fixtureSource.IndexOf('$script:Controls = ')
  if ($fixtureEnd -lt 0) {
    throw "the fixture section of the test file could not be delimited"
  }
  [System.IO.File]::WriteAllText(
    (Join-Path $mutatedRoot "dispatch-ownership-proof.fixtures.ps1"),
    $fixtureSource.Substring(0, $fixtureEnd),
    [System.Text.UTF8Encoding]::new($false))

  $probeOutput = & pwsh -NoProfile -File $probe $mutatedRoot 2>&1
  $bypassAccepted = ($probeOutput -join "`n").Contains("BYPASS-ACCEPTED")
  if (-not $bypassAccepted) {
    throw "the mutation control did not accept the two-winner scenario, so the negative test is not sensitive to removing the single-winner proof: $($probeOutput -join "`n")"
  }
} finally {
  Remove-Item -LiteralPath $mutatedRoot -Recurse -Force -ErrorAction SilentlyContinue
}
[void]$script:Controls.Add([ordered]@{
    name = "removing the single-winner proof makes the harness accept two dispatch winners"
    expected = "ACCEPTED WITHOUT THE PROOF"
    result = "PASS"
    rejectedBy = "mutation control"
  })

[ordered]@{
  schema = "m2-t15.dispatch-ownership-proof-tests.v1"
  result = "PASS"
  positiveControls = 2
  negativeControls = @($script:Controls).Count
  controls = @($script:Controls)
} | ConvertTo-Json -Depth 20
