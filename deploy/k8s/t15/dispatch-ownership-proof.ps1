# M2-T15.2 diagnostic dispatch-ownership proof.
#
# These functions are deliberately pure: they take one captured observation and decide PASS/FAIL
# without touching Kubernetes or PostgreSQL. That is what makes the negative controls in
# dispatch-ownership-proof.tests.ps1 able to prove the proofs actually reject a bad scenario.
#
# The invariant being proven is the #154 production recovery state machine:
#
#   commission -> AVAILABLE -> A dies before dispatch -> natural lease expiry
#     -> B reclaims the same diagnostic request -> exactly one dispatch CAS winner
#     -> DISPATCH_OWNED -> fenced transition to IN_FLIGHT -> exactly one provider invocation
#     -> terminal execution/outcome -> workflow completion
#
# Aggregate row counts alone are explicitly NOT sufficient. A run whose dispatch evidence is
# missing fails before any count is read.

function Get-RequiredDispatchCheckpointNames {
  return @(
    "after-commission-before-death",
    "after-death-before-reclaim",
    "replacement-held",
    "after-dispatch-acquisition",
    "in-flight-before-provider",
    "final"
  )
}

function Get-PreOwnershipDispatchCheckpointNames {
  return @(
    "after-commission-before-death",
    "after-death-before-reclaim",
    "replacement-held"
  )
}

function Test-DispatchValueAbsent {
  param([Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()]$Value)
  if ($null -eq $Value) {
    return $true
  }
  return [string]::IsNullOrWhiteSpace([string]$Value)
}

# AVAILABLE -> DISPATCH_OWNED -> IN_FLIGHT is the only legal order. Anything else, including
# LEGACY_INDETERMINATE, ranks -1 and is rejected: a commission created by this candidate can
# never be legacy, and a legacy row is by definition not safe to dispatch.
function Get-DispatchStateRank {
  param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$State)
  switch ($State) {
    "AVAILABLE" { return 0 }
    "DISPATCH_OWNED" { return 1 }
    "IN_FLIGHT" { return 2 }
    default { return -1 }
  }
}

function Get-DispatchCheckpoint {
  param(
    [Parameter(Mandatory = $true)]$Observation,
    [Parameter(Mandatory = $true)][string]$Name
  )
  $matched = @($Observation.checkpoints | Where-Object { [string]$_.name -eq $Name })
  if ($matched.Count -ne 1) {
    throw "dispatch-ownership evidence requires exactly one '$Name' checkpoint but found $($matched.Count)"
  }
  return $matched[0]
}

# Fails closed before any count is read. A run that cannot show the dispatch row at the mandatory
# checkpoints has not proven the state machine, however clean its aggregate counts look.
function Assert-DispatchEvidencePresent {
  param([Parameter(Mandatory = $true)]$Observation)

  if (Test-DispatchValueAbsent $Observation.requestId) {
    throw "dispatch-ownership evidence has no diagnostic request identity"
  }
  if (@($Observation.checkpoints).Count -eq 0) {
    throw "dispatch-ownership evidence contains no checkpoints; final success cannot be inferred from row counts alone"
  }
  foreach ($name in Get-RequiredDispatchCheckpointNames) {
    $checkpoint = Get-DispatchCheckpoint $Observation $name
    if ($name -in @("after-dispatch-acquisition", "in-flight-before-provider")) {
      # These two are only sampled where the application makes them deterministically observable.
      # They are still mandatory as records, and the durable reconstruction below must carry them.
      continue
    }
    if ($null -eq $checkpoint.dispatch) {
      throw "dispatch-ownership checkpoint '$name' captured no core.ai_execution_dispatch row; final success cannot be inferred from row counts alone"
    }
    if ([int]$checkpoint.rowCount -ne 1) {
      throw "dispatch-ownership checkpoint '$name' observed $([int]$checkpoint.rowCount) dispatch rows for one commissioned request"
    }
  }
}

function Assert-DiagnosticDispatchOwnershipProof {
  param([Parameter(Mandatory = $true)]$Observation)

  Assert-DispatchEvidencePresent $Observation

  $failures = [System.Collections.Generic.List[string]]::new()
  $checks = [System.Collections.Generic.List[object]]::new()
  $record = {
    param([string]$Name, [bool]$Condition, [string]$Observed)
    [void]$checks.Add([ordered]@{
        name = $Name
        result = if ($Condition) { "PASS" } else { "FAIL" }
        observed = $Observed
      })
    if (-not $Condition) {
      [void]$failures.Add("$Name (observed: $Observed)")
    }
  }

  $requestId = [string]$Observation.requestId
  $final = Get-DispatchCheckpoint $Observation "final"
  $commissioned = Get-DispatchCheckpoint $Observation "after-commission-before-death"
  $finalRow = $final.dispatch
  $commissionedRow = $commissioned.dispatch

  # --- A's durable commission is AVAILABLE, and stays that way until B owns it ---------------
  foreach ($name in Get-PreOwnershipDispatchCheckpointNames) {
    $checkpoint = Get-DispatchCheckpoint $Observation $name
    $row = $checkpoint.dispatch
    $available = [string]$row.state -eq "AVAILABLE" -and
      (Test-DispatchValueAbsent $row.ownerToken) -and
      [long]$row.fence -eq 0 -and
      (Test-DispatchValueAbsent $row.ownershipAcquiredAt) -and
      (Test-DispatchValueAbsent $row.invocationStartedAt)
    & $record "dispatch $name is an ownerless AVAILABLE commission" $available `
      ("state=$([string]$row.state); ownerToken=$([string]$row.ownerToken); fence=$([string]$row.fence); acquiredAt=$([string]$row.ownershipAcquiredAt); invokedAt=$([string]$row.invocationStartedAt)")
  }

  # --- provider invocation count before A death is zero --------------------------------------
  $preDeathClean = [int]$commissioned.providerInvocationCount -eq 0 -and
    [int]$commissioned.terminalCount -eq 0 -and
    [int]$commissioned.gateCount -eq 0
  & $record "provider invocation count before A death is 0" $preDeathClean `
    ("providerInvocations=$([int]$commissioned.providerInvocationCount); terminal=$([int]$commissioned.terminalCount); gate=$([int]$commissioned.gateCount)")

  $afterDeath = Get-DispatchCheckpoint $Observation "after-death-before-reclaim"
  $replacementHeld = Get-DispatchCheckpoint $Observation "replacement-held"
  $preReclaimClean = [int]$afterDeath.providerInvocationCount -eq 0 -and
    [int]$replacementHeld.providerInvocationCount -eq 0
  & $record "no provider invocation between A death and B reclaim" $preReclaimClean `
    ("afterDeath=$([int]$afterDeath.providerInvocationCount); replacementHeld=$([int]$replacementHeld.providerInvocationCount)")

  # --- natural production lease expiry, with claimed_at never rewritten -----------------------
  $claimA = $Observation.claimA
  $claimB = $Observation.claimB
  $lease = $Observation.naturalLease
  $claimedAtStable = @($Observation.preReclaimClaimedAt | Where-Object {
      [string]$_ -ne [string]$claimA.claimedAt
    }).Count -eq 0
  & $record "natural lease expiry did not mutate claimed_at" `
    ($claimedAtStable -and -not (Test-DispatchValueAbsent $claimA.claimedAt)) `
    ("claimA.claimedAt=$([string]$claimA.claimedAt); observed=$((@($Observation.preReclaimClaimedAt) -join ','))")
  $leaseNatural = [bool]$lease.expired -and -not (Test-DispatchValueAbsent $lease.leaseExpiresAt)
  if ($leaseNatural -and -not (Test-DispatchValueAbsent $claimB.claimedAt)) {
    $leaseNatural = [datetimeoffset]::Parse([string]$claimB.claimedAt) -ge
      [datetimeoffset]::Parse([string]$lease.leaseExpiresAt)
  } else {
    $leaseNatural = $false
  }
  & $record "B reclaimed only after natural production lease expiry" $leaseNatural `
    ("leaseExpiresAt=$([string]$lease.leaseExpiresAt); claimB.claimedAt=$([string]$claimB.claimedAt); expired=$([string]$lease.expired)")

  # --- B obtains attempt 2 with a distinct workflow execution token ---------------------------
  $tokensDiffer = -not (Test-DispatchValueAbsent $claimA.executionToken) -and
    -not (Test-DispatchValueAbsent $claimB.executionToken) -and
    [string]$claimA.executionToken -ne [string]$claimB.executionToken
  & $record "workflow execution token A differs from token B" $tokensDiffer `
    ("tokenA=$([string]$claimA.executionToken); tokenB=$([string]$claimB.executionToken)")
  $attemptIncrement = [int]$claimA.attemptCount -eq 1 -and [int]$claimB.attemptCount -eq 2
  & $record "B reclaimed as attempt 2" $attemptIncrement `
    ("attemptA=$([int]$claimA.attemptCount); attemptB=$([int]$claimB.attemptCount)")
  $distinctPods = -not (Test-DispatchValueAbsent $Observation.podUidA) -and
    -not (Test-DispatchValueAbsent $Observation.podUidB) -and
    [string]$Observation.podUidA -ne [string]$Observation.podUidB
  & $record "A and B are distinct backend pod UIDs" $distinctPods `
    ("podUidA=$([string]$Observation.podUidA); podUidB=$([string]$Observation.podUidB)")

  # --- request identity and grounding identity survive the reclaim unchanged ------------------
  $capturedRows = @($Observation.checkpoints |
      Where-Object { $null -ne $_.dispatch } |
      ForEach-Object { $_.dispatch })
  $requestIds = @($capturedRows | ForEach-Object { [string]$_.requestId } | Sort-Object -Unique)
  $requestStable = $requestIds.Count -eq 1 -and $requestIds[0] -eq $requestId
  & $record "the same diagnostic request identity is used before and after reclaim" $requestStable `
    ("expected=$requestId; observed=$($requestIds -join ',')")

  $contextIds = @($capturedRows | ForEach-Object { [string]$_.contextId } | Sort-Object -Unique)
  $contextAsOfs = @($capturedRows | ForEach-Object { [string]$_.contextAsOf } | Sort-Object -Unique)
  $contextStable = $contextIds.Count -eq 1 -and $contextAsOfs.Count -eq 1 -and
    -not (Test-DispatchValueAbsent $contextIds[0]) -and
    -not (Test-DispatchValueAbsent $contextAsOfs[0])
  & $record "durable context_id and context_as_of are preserved across reclaim" $contextStable `
    ("contextId=$($contextIds -join ','); contextAsOf=$($contextAsOfs -join ',')")

  # --- exactly one commission, and no second one appears anywhere -----------------------------
  $commissionEventIds = @($capturedRows |
      ForEach-Object { [string]$_.commissionEventId } | Sort-Object -Unique)
  $commissionCounts = @($Observation.checkpoints |
      Where-Object { $null -ne $_.dispatch } |
      ForEach-Object { [int]$_.commissionCount } | Sort-Object -Unique)
  $singleCommission = $commissionEventIds.Count -eq 1 -and
    -not (Test-DispatchValueAbsent $commissionEventIds[0]) -and
    $commissionCounts.Count -eq 1 -and $commissionCounts[0] -eq 1
  & $record "exactly one durable commission exists throughout" $singleCommission `
    ("commissionEventIds=$($commissionEventIds -join ','); commissionCounts=$($commissionCounts -join ',')")

  # --- exactly one dispatch CAS winner --------------------------------------------------------
  # fence is monotonic and only the acquisition CAS increments it, so fence=1 in the final row is
  # the durable proof that ownership was granted exactly once. A redispatch would leave fence>1.
  $ownerTokens = @($capturedRows |
      Where-Object { -not (Test-DispatchValueAbsent $_.ownerToken) } |
      ForEach-Object { [string]$_.ownerToken } | Sort-Object -Unique)
  $sampleTokens = @($Observation.transitionSamples |
      Where-Object { -not (Test-DispatchValueAbsent $_.ownerToken) } |
      ForEach-Object { [string]$_.ownerToken } | Sort-Object -Unique)
  $allTokens = @(@($ownerTokens) + @($sampleTokens) | Sort-Object -Unique)
  $singleWinner = $allTokens.Count -eq 1 -and [long]$finalRow.fence -eq 1
  & $record "exactly one claimant won dispatch ownership" $singleWinner `
    ("ownerTokens=$($allTokens -join ','); finalFence=$([string]$finalRow.fence)")

  $winnerProvable = -not (Test-DispatchValueAbsent $finalRow.ownerToken) -and
    [long]$finalRow.fence -ge 1
  & $record "dispatch winner has a provable owner token and fence" $winnerProvable `
    ("ownerToken=$([string]$finalRow.ownerToken); fence=$([string]$finalRow.fence)")

  # --- the AVAILABLE -> DISPATCH_OWNED transition ----------------------------------------------
  $acquisition = Get-DispatchCheckpoint $Observation "after-dispatch-acquisition"
  $ownedSample = $null
  if ($null -ne $acquisition.dispatch -and [string]$acquisition.dispatch.state -eq "DISPATCH_OWNED") {
    $ownedSample = $acquisition.dispatch
  } else {
    $ownedSample = @($Observation.transitionSamples |
        Where-Object { [string]$_.state -eq "DISPATCH_OWNED" } | Select-Object -First 1)
    if (@($ownedSample).Count -eq 0) { $ownedSample = $null } else { $ownedSample = @($ownedSample)[0] }
  }
  $acquisitionEvidence = if ($null -ne $ownedSample) { "sampled" } else { "durable-reconstruction" }
  $ownershipTransition = [string]$commissionedRow.state -eq "AVAILABLE" -and
    -not (Test-DispatchValueAbsent $finalRow.ownershipAcquiredAt) -and
    [long]$finalRow.fence -eq 1
  if ($null -ne $ownedSample) {
    $ownershipTransition = $ownershipTransition -and
      [string]$ownedSample.ownerToken -eq [string]$finalRow.ownerToken -and
      [long]$ownedSample.fence -eq [long]$finalRow.fence
  }
  & $record "observed transition AVAILABLE -> DISPATCH_OWNED" $ownershipTransition `
    ("evidence=$acquisitionEvidence; commissionedState=$([string]$commissionedRow.state); acquiredAt=$([string]$finalRow.ownershipAcquiredAt); fence=$([string]$finalRow.fence)")

  # --- the fenced DISPATCH_OWNED -> IN_FLIGHT transition ----------------------------------------
  $inFlight = Get-DispatchCheckpoint $Observation "in-flight-before-provider"
  $inFlightSample = $null
  if ($null -ne $inFlight.dispatch -and [string]$inFlight.dispatch.state -eq "IN_FLIGHT") {
    $inFlightSample = $inFlight.dispatch
  } else {
    $inFlightSample = @($Observation.transitionSamples |
        Where-Object { [string]$_.state -eq "IN_FLIGHT" } | Select-Object -First 1)
    if (@($inFlightSample).Count -eq 0) { $inFlightSample = $null } else { $inFlightSample = @($inFlightSample)[0] }
  }
  $inFlightEvidence = if ($null -ne $inFlightSample) { "sampled" } else { "durable-reconstruction" }
  $fencedTransition = [string]$finalRow.state -eq "IN_FLIGHT" -and
    -not (Test-DispatchValueAbsent $finalRow.ownershipAcquiredAt) -and
    -not (Test-DispatchValueAbsent $finalRow.invocationStartedAt)
  if ($fencedTransition) {
    $fencedTransition = [datetimeoffset]::Parse([string]$finalRow.ownershipAcquiredAt) -le
      [datetimeoffset]::Parse([string]$finalRow.invocationStartedAt)
  }
  if ($fencedTransition -and $null -ne $inFlightSample) {
    $fencedTransition = [string]$inFlightSample.ownerToken -eq [string]$finalRow.ownerToken -and
      [long]$inFlightSample.fence -eq [long]$finalRow.fence
  }
  & $record "the same owner token and fence authorized DISPATCH_OWNED -> IN_FLIGHT" $fencedTransition `
    ("evidence=$inFlightEvidence; finalState=$([string]$finalRow.state); acquiredAt=$([string]$finalRow.ownershipAcquiredAt); invokedAt=$([string]$finalRow.invocationStartedAt); ownerToken=$([string]$finalRow.ownerToken); fence=$([string]$finalRow.fence)")

  # --- no redispatch: the sampled state sequence never regresses --------------------------------
  $sequence = @($Observation.transitionSamples | ForEach-Object { [string]$_.state })
  $regression = ""
  $rank = -1
  foreach ($state in $sequence) {
    $next = Get-DispatchStateRank $state
    if ($next -lt 0) {
      $regression = "unsupported dispatch state '$state'"
      break
    }
    if ($next -lt $rank) {
      $regression = "regressed to '$state'"
      break
    }
    $rank = $next
  }
  & $record "no redispatch from DISPATCH_OWNED or IN_FLIGHT" ([string]::IsNullOrEmpty($regression)) `
    ("sequence=$($sequence -join '->'); finalFence=$([string]$finalRow.fence); $regression")

  # --- exactly one provider invocation, terminal execution, gate decision -----------------------
  $counts = $Observation.finalCounts
  & $record "exactly one provider invocation" ([int]$final.providerInvocationCount -eq 1) `
    ("providerInvocations=$([int]$final.providerInvocationCount)")
  $terminalOnce = [int]$counts.diagnosticExecution -eq 1 -and
    [string]$counts.diagnosticExecutionStatus -eq "SUCCEEDED" -and
    [int]$counts.diagnosticTerminal -eq 1
  & $record "exactly one terminal AI execution" $terminalOnce `
    ("execution=$([int]$counts.diagnosticExecution)/$([string]$counts.diagnosticExecutionStatus)/$([string]$counts.diagnosticExecutionError); terminal=$([int]$counts.diagnosticTerminal)")
  & $record "exactly one authoritative diagnostic gate decision" ([int]$counts.diagnosticGate -eq 1) `
    ("gate=$([int]$counts.diagnosticGate)")

  $downstream = [int]$counts.evidence -eq 1 -and [int]$counts.mastery -eq 1 -and
    [int]$counts.outbox -eq 1
  & $record "no duplicate downstream evidence, mastery or adaptation work" $downstream `
    ("evidence/mastery/outbox=$([int]$counts.evidence)/$([int]$counts.mastery)/$([int]$counts.outbox)")

  $workflowComplete = [string]$Observation.workflow.status -eq "COMPLETED" -and
    [string]$Observation.workflow.terminalReason -eq "WORKFLOW_COMPLETED"
  & $record "workflow completed" $workflowComplete `
    ("workflow=$([string]$Observation.workflow.status)/$([string]$Observation.workflow.terminalReason)")
  & $record "cursor history passed" ([string]$Observation.cursorHistoryResult -eq "PASS") `
    ("cursorHistory=$([string]$Observation.cursorHistoryResult)")

  if ($failures.Count -gt 0) {
    throw "DIAGNOSTIC DISPATCH OWNERSHIP PROOF FAILED: $($failures -join '; ')"
  }

  return [ordered]@{
    schema = "m2-t15.dispatch-ownership-proof.v1"
    result = "PASS"
    requestId = $requestId
    commissionEventId = $commissionEventIds[0]
    contextId = $contextIds[0]
    contextAsOf = $contextAsOfs[0]
    ownerToken = [string]$finalRow.ownerToken
    fence = [long]$finalRow.fence
    finalState = [string]$finalRow.state
    ownershipAcquiredAt = [string]$finalRow.ownershipAcquiredAt
    invocationStartedAt = [string]$finalRow.invocationStartedAt
    acquisitionEvidence = $acquisitionEvidence
    inFlightEvidence = $inFlightEvidence
    observedStateSequence = @($sequence)
    workflowTokenA = [string]$claimA.executionToken
    workflowTokenB = [string]$claimB.executionToken
    attemptA = [int]$claimA.attemptCount
    attemptB = [int]$claimB.attemptCount
    podUidA = [string]$Observation.podUidA
    podUidB = [string]$Observation.podUidB
    providerInvocations = [int]$final.providerInvocationCount
    checkpoints = @($Observation.checkpoints | ForEach-Object {
        [ordered]@{
          name = [string]$_.name
          capturedAtUtc = [string]$_.capturedAtUtc
          state = if ($null -eq $_.dispatch) { "" } else { [string]$_.dispatch.state }
          ownerToken = if ($null -eq $_.dispatch) { "" } else { [string]$_.dispatch.ownerToken }
          fence = if ($null -eq $_.dispatch) { "" } else { [string]$_.dispatch.fence }
          providerInvocationCount = [int]$_.providerInvocationCount
        }
      })
    checks = @($checks)
  }
}
