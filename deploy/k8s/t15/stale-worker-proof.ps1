function Assert-StaleWorkerObservation {
  param([Parameter(Mandatory = $true)]$Observation)

  if (-not [bool]$Observation.aHeld) {
    throw "stale-worker proof requires worker A to be held before authoritative completion"
  }
  if (-not [bool]$Observation.bReclaimed) {
    throw "stale-worker proof requires worker B to reclaim the same run and step"
  }
  if ([string]::IsNullOrWhiteSpace([string]$Observation.tokenA) -or
      [string]::IsNullOrWhiteSpace([string]$Observation.tokenB) -or
      [string]$Observation.tokenA -eq [string]$Observation.tokenB) {
    throw "stale-worker proof requires distinct non-empty execution tokens"
  }
  if ([int]$Observation.attemptB -ne ([int]$Observation.attemptA + 1)) {
    throw "stale-worker proof requires attempt B to equal attempt A plus one"
  }
  if ([string]$Observation.podUidA -eq [string]$Observation.podUidB) {
    throw "stale-worker proof requires distinct worker A and B pod UIDs"
  }
  if ([int]$Observation.staleACompletionCasAffectedRows -ne 0) {
    throw "stale-worker proof rejected: stale-token protection was bypassed"
  }
  if ([int]$Observation.bCompletionCasAffectedRows -ne 1) {
    throw "stale-worker proof requires exactly one successful B completion CAS"
  }

  return [pscustomobject]@{
    result = "PASS"
    tokenA = [string]$Observation.tokenA
    tokenB = [string]$Observation.tokenB
    attemptA = [int]$Observation.attemptA
    attemptB = [int]$Observation.attemptB
  }
}

function Get-StaleWorkerDiagnosticExecutionCount {
  param(
    [Parameter(Mandatory = $true)]$Snapshot,
    [Parameter(Mandatory = $true)][string]$DiagnosticRequestId
  )

  if ([string]::IsNullOrWhiteSpace($DiagnosticRequestId)) {
    throw "stale-worker diagnostic attribution requires the fixture request identity"
  }
  return @($Snapshot.aiExecutions | Where-Object {
      [string]$_.agent_type -eq "DIAGNOSTIC" -and
      [string]$_.request_id -eq $DiagnosticRequestId
    }).Count
}
