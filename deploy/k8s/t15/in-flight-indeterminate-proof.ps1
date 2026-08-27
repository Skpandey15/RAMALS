# M2-T15.2 Contract A in-flight indeterminate proof (S4).
#
# Pure functions over one captured observation, for the same reason dispatch-ownership-proof.ps1 is:
# a proof that touches Kubernetes or PostgreSQL cannot be shown to reject a bad scenario, and the
# negative controls in in-flight-indeterminate-proof.tests.ps1 depend on being able to.
#
# The invariant being proven is the Contract A (#160) guarantee that neither diagnostic-commission
# nor diagnostic-provider can prove:
#
#   commission -> AVAILABLE -> DISPATCH_OWNED -> IN_FLIGHT -> the PLATFORM dies mid-provider-call
#     -> replacement observes IN_FLIGHT -> closes it INDETERMINATE without redispatching
#     -> no SUCCEEDED execution, no gate decision, ADAPT skipped, effects still exactly-once
#
# diagnostic-commission kills at AVAILABLE, before any dispatch ownership exists.
# diagnostic-provider kills the AI process, leaving the original platform worker alive to record
# the outcome itself. Only this scenario forces a *different* worker to resolve an IN_FLIGHT row.
#
# The load-bearing fact is the fence. Only the acquisition CAS increments it, so an unchanged
# fence of 1 across the death is itself the proof that no replacement redispatched. Provider
# invocation counts corroborate; they do not replace it.

function Test-InFlightValueAbsent {
  param([Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()]$Value)
  if ($null -eq $Value) {
    return $true
  }
  return [string]::IsNullOrWhiteSpace([string]$Value)
}

function Get-InFlightRequiredCaptureNames {
  return @("preDeath", "afterDeath", "final")
}

# Fails closed before any assertion is evaluated. A run that cannot show the dispatch row at all
# three captures has not proven anything, however clean its final counts look.
function Assert-InFlightEvidencePresent {
  param([Parameter(Mandatory = $true)]$Observation)

  if (Test-InFlightValueAbsent $Observation.requestId) {
    throw "in-flight evidence has no diagnostic request identity"
  }
  foreach ($name in Get-InFlightRequiredCaptureNames) {
    $capture = $Observation.$name
    if ($null -eq $capture) {
      throw "in-flight evidence is missing the '$name' dispatch capture"
    }
    if (Test-InFlightValueAbsent $capture.state) {
      throw "in-flight evidence capture '$name' has no dispatch state"
    }
    if (Test-InFlightValueAbsent $capture.fence) {
      throw "in-flight evidence capture '$name' has no dispatch fence"
    }
  }
  if (Test-InFlightValueAbsent $Observation.deletedPodUid) {
    throw "in-flight evidence does not identify the deleted platform pod"
  }
  if (Test-InFlightValueAbsent $Observation.replacementPodUid) {
    throw "in-flight evidence does not identify the replacement platform pod"
  }
}

function New-InFlightCheck {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][bool]$Passed,
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Observed
  )
  return [ordered]@{
    name = $Name
    result = if ($Passed) { "PASS" } else { "FAIL" }
    observed = $Observed
  }
}

# $Durable carries the terminal facts read from PostgreSQL after recovery: execution status and
# error code, event counts, gate count, ADAPT status and the effect lineage counts. Passed in
# rather than queried here so this stays pure and testable.
function Assert-InFlightIndeterminateProof {
  param(
    [Parameter(Mandatory = $true)]$Observation,
    [Parameter(Mandatory = $true)]$Durable
  )
  Assert-InFlightEvidencePresent $Observation

  $checks = @()

  # -- the window was real ---------------------------------------------------------------------
  $pre = $Observation.preDeath
  $checks += New-InFlightCheck "platform died while dispatch was durably IN_FLIGHT" `
    ([string]$pre.state -eq "IN_FLIGHT") `
    "preDeath.state=$([string]$pre.state); fence=$([string]$pre.fence)"

  $checks += New-InFlightCheck "invocation was durably started before the death" `
    (-not (Test-InFlightValueAbsent $pre.invocationStartedAt)) `
    "invocationStartedAt=$([string]$pre.invocationStartedAt)"

  $checks += New-InFlightCheck "dispatch was owned exactly once before the death" `
    ([string]$pre.fence -eq "1") `
    "preDeath.fence=$([string]$pre.fence); ownerToken=$([string]$pre.ownerToken)"

  # -- the replacement did not redispatch -------------------------------------------------------
  $final = $Observation.final
  $checks += New-InFlightCheck "fence remains 1 after replacement recovery" `
    ([string]$final.fence -eq "1") `
    "afterDeath.fence=$([string]$Observation.afterDeath.fence); final.fence=$([string]$final.fence)"

  $checks += New-InFlightCheck "dispatch ownership never changed hands" `
    ([string]$final.ownerToken -eq [string]$pre.ownerToken) `
    "preDeath.ownerToken=$([string]$pre.ownerToken); final.ownerToken=$([string]$final.ownerToken)"

  $checks += New-InFlightCheck "invocation timestamp was never rewritten" `
    ([string]$final.invocationStartedAt -eq [string]$pre.invocationStartedAt) `
    "preDeath=$([string]$pre.invocationStartedAt); final=$([string]$final.invocationStartedAt)"

  $checks += New-InFlightCheck "no second provider submission" `
    ([int]$Observation.providerInvocationsFinal -eq 1 -and
     [int]$Observation.providerInvocationsBeforeDeath -eq 1) `
    "beforeDeath=$([string]$Observation.providerInvocationsBeforeDeath); final=$([string]$Observation.providerInvocationsFinal)"

  $checks += New-InFlightCheck "the recovering worker is a different pod" `
    ([string]$Observation.deletedPodUid -ne [string]$Observation.replacementPodUid) `
    "deletedPodUid=$([string]$Observation.deletedPodUid); replacementPodUid=$([string]$Observation.replacementPodUid)"

  # -- the outcome is honestly indeterminate ----------------------------------------------------
  $checks += New-InFlightCheck "exactly one STARTED diagnostic event" `
    ([string]$Durable.startedEvents -eq "1") `
    "startedEvents=$([string]$Durable.startedEvents)"

  $checks += New-InFlightCheck "exactly one INDETERMINATE terminal event" `
    ([string]$Durable.indeterminateEvents -eq "1") `
    "indeterminateEvents=$([string]$Durable.indeterminateEvents)"

  $checks += New-InFlightCheck "terminal execution is INDETERMINATE with the fail-closed code" `
    ([string]$Durable.executionStatus -eq "INDETERMINATE" -and
     [string]$Durable.errorCode -eq "AI_EXECUTION_OUTCOME_INDETERMINATE") `
    "status=$([string]$Durable.executionStatus); errorCode=$([string]$Durable.errorCode)"

  # Asserted as its own fact rather than inferred from the status above. "No success was
  # fabricated" is the claim Contract A makes, and it is worth failing on directly.
  $checks += New-InFlightCheck "no SUCCEEDED diagnostic execution exists" `
    ([string]$Durable.succeededExecutions -eq "0") `
    "succeededExecutions=$([string]$Durable.succeededExecutions)"

  $checks += New-InFlightCheck "no diagnostic gate decision was written" `
    ([string]$Durable.gateDecisions -eq "0") `
    "gateDecisions=$([string]$Durable.gateDecisions)"

  $checks += New-InFlightCheck "ADAPT was skipped rather than run on an unknown outcome" `
    ([string]$Durable.adaptStatus -eq "SKIPPED") `
    "adaptStatus=$([string]$Durable.adaptStatus)"

  # -- nothing downstream was duplicated --------------------------------------------------------
  $checks += New-InFlightCheck "evidence and mastery lineage remain exactly-once" `
    ([string]$Durable.evidenceRows -eq "1" -and [string]$Durable.masterySnapshots -eq "1") `
    "evidence=$([string]$Durable.evidenceRows); mastery=$([string]$Durable.masterySnapshots)"

  $failed = @($checks | Where-Object { $_.result -eq "FAIL" })

  $proof = [ordered]@{
    schema = "m2-t15.in-flight-indeterminate-proof.v1"
    result = if ($failed.Count -eq 0) { "PASS" } else { "FAIL" }
    requestId = [string]$Observation.requestId
    runId = [string]$Observation.runId
    fence = [string]$Observation.final.fence
    ownerToken = [string]$Observation.final.ownerToken
    preDeathState = [string]$Observation.preDeath.state
    finalState = [string]$Observation.final.state
    providerInvocations = [int]$Observation.providerInvocationsFinal
    deletedPodUid = [string]$Observation.deletedPodUid
    replacementPodUid = [string]$Observation.replacementPodUid
    workflowTokenA = [string]$Observation.workflowTokenA
    durable = $Durable
    checks = $checks
  }

  if ($failed.Count -ne 0) {
    $names = ($failed | ForEach-Object { $_.name }) -join "; "
    throw "in-flight indeterminate proof failed: $names"
  }
  return $proof
}
