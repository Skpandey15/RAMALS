# Offline self-tests for the S4 in-flight indeterminate proof.
#
# Executes no crash scenario, touches no cluster and no database. Every case is a mutation of one
# known-good observation, and each must be REJECTED. A proof that only ever sees passing input is
# not a proof, it is a formatter.
#
#   pwsh -File .\deploy\k8s\t15\in-flight-indeterminate-proof.tests.ps1

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
. (Join-Path $scriptRoot "in-flight-indeterminate-proof.ps1")

$script:Failures = 0
$script:Total = 0

function Test-Case {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][scriptblock]$Body,
    [Parameter(Mandatory = $true)][bool]$ShouldPass
  )
  $script:Total++
  $threw = $false
  $message = ""
  try {
    [void](& $Body)
  } catch {
    $threw = $true
    $message = $_.Exception.Message
  }
  $passed = if ($ShouldPass) { -not $threw } else { $threw }
  if ($passed) {
    Write-Host "  PASS  $Name"
  } else {
    $script:Failures++
    if ($ShouldPass) {
      Write-Host "  FAIL  $Name -- expected acceptance but was rejected: $message"
    } else {
      Write-Host "  FAIL  $Name -- expected rejection but the proof accepted it"
    }
  }
}

function New-GoodObservation {
  return [ordered]@{
    schema = "m2-t15.in-flight-indeterminate-observation.v1"
    requestId = "wf-diag-01900000-0000-7000-8000-000000000001"
    runId = "01900000-0000-7000-8000-000000000001"
    preDeath = [ordered]@{
      capturedAtUtc = "2026-08-27T10:00:00.000000Z"
      state = "IN_FLIGHT"; fence = "1"; ownerToken = "01900000-0000-7000-8000-0000000000d1"
      ownershipAcquiredAt = "2026-08-27T09:59:59.100000Z"
      invocationStartedAt = "2026-08-27T09:59:59.200000Z"
    }
    afterDeath = [ordered]@{
      capturedAtUtc = "2026-08-27T10:00:05.000000Z"
      state = "IN_FLIGHT"; fence = "1"; ownerToken = "01900000-0000-7000-8000-0000000000d1"
      ownershipAcquiredAt = "2026-08-27T09:59:59.100000Z"
      invocationStartedAt = "2026-08-27T09:59:59.200000Z"
    }
    final = [ordered]@{
      capturedAtUtc = "2026-08-27T10:01:30.000000Z"
      state = "IN_FLIGHT"; fence = "1"; ownerToken = "01900000-0000-7000-8000-0000000000d1"
      ownershipAcquiredAt = "2026-08-27T09:59:59.100000Z"
      invocationStartedAt = "2026-08-27T09:59:59.200000Z"
    }
    providerInvocationsBeforeDeath = 1
    providerInvocationsFinal = 1
    deletedPod = "learning-platform-aaa"
    deletedPodUid = "01900000-0000-7000-8000-00000000000a"
    replacementPod = "learning-platform-bbb"
    replacementPodUid = "01900000-0000-7000-8000-00000000000b"
    aiProviderPod = "ramals-ai-ccc"
    workflowTokenA = "01900000-0000-7000-8000-0000000000a1"
    attemptA = 1
  }
}

function New-GoodDurable {
  return [ordered]@{
    executionStatus = "INDETERMINATE"
    errorCode = "AI_EXECUTION_OUTCOME_INDETERMINATE"
    succeededExecutions = "0"
    startedEvents = "1"
    indeterminateEvents = "1"
    gateDecisions = "0"
    adaptStatus = "SKIPPED"
    evidenceRows = "1"
    masterySnapshots = "1"
  }
}

Write-Host "in-flight indeterminate proof self-tests"

Test-Case "a clean in-flight recovery is accepted" {
  Assert-InFlightIndeterminateProof (New-GoodObservation) (New-GoodDurable)
} $true

# -- the perturbations that must be rejected ----------------------------------------------------

Test-Case "rejects a redispatch that incremented the fence" {
  $o = New-GoodObservation; $o.final.fence = "2"
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects a second provider submission" {
  $o = New-GoodObservation; $o.providerInvocationsFinal = 2
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects ownership changing hands" {
  $o = New-GoodObservation; $o.final.ownerToken = "01900000-0000-7000-8000-0000000000d9"
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects a rewritten invocation timestamp" {
  $o = New-GoodObservation; $o.final.invocationStartedAt = "2026-08-27T10:01:00.000000Z"
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects a death that did not happen while IN_FLIGHT" {
  $o = New-GoodObservation; $o.preDeath.state = "DISPATCH_OWNED"
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects a pre-death row with no invocation timestamp" {
  $o = New-GoodObservation; $o.preDeath.invocationStartedAt = ""
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects recovery by the same pod" {
  $o = New-GoodObservation; $o.replacementPodUid = $o.deletedPodUid
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects a fabricated SUCCEEDED execution" {
  $d = New-GoodDurable; $d.succeededExecutions = "1"; $d.executionStatus = "SUCCEEDED"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

Test-Case "rejects a fabricated gate decision" {
  $d = New-GoodDurable; $d.gateDecisions = "1"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

Test-Case "rejects a legacy FAILED terminal status" {
  $d = New-GoodDurable; $d.executionStatus = "FAILED"; $d.errorCode = "AI_EXECUTION_ABANDONED"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

Test-Case "rejects a missing INDETERMINATE terminal event" {
  $d = New-GoodDurable; $d.indeterminateEvents = "0"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

Test-Case "rejects a duplicated STARTED event" {
  $d = New-GoodDurable; $d.startedEvents = "2"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

Test-Case "rejects ADAPT running on an unknown outcome" {
  $d = New-GoodDurable; $d.adaptStatus = "COMPLETED"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

Test-Case "rejects duplicated downstream evidence" {
  $d = New-GoodDurable; $d.evidenceRows = "2"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

Test-Case "rejects duplicated mastery lineage" {
  $d = New-GoodDurable; $d.masterySnapshots = "2"
  Assert-InFlightIndeterminateProof (New-GoodObservation) $d
} $false

# -- fail-closed on missing evidence ------------------------------------------------------------

Test-Case "rejects evidence with no request identity" {
  $o = New-GoodObservation; $o.requestId = ""
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects evidence missing the final dispatch capture" {
  $o = New-GoodObservation; $o.final = $null
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Test-Case "rejects evidence that does not identify the replacement pod" {
  $o = New-GoodObservation; $o.replacementPodUid = ""
  Assert-InFlightIndeterminateProof $o (New-GoodDurable)
} $false

Write-Host ""
if ($script:Failures -ne 0) {
  Write-Host "in-flight indeterminate proof self-tests FAILED: $($script:Failures) of $($script:Total)"
  exit 1
}
Write-Host "in-flight indeterminate proof self-tests passed: $($script:Total)/$($script:Total)"
