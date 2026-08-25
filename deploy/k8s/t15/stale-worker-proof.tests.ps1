$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "stale-worker-proof.ps1")

$valid = [pscustomobject]@{
  aHeld = $true
  bReclaimed = $true
  podUidA = "pod-a"
  podUidB = "pod-b"
  tokenA = "01900000-0000-7000-8000-000000000901"
  tokenB = "01900000-0000-7000-8000-000000000902"
  attemptA = 1
  attemptB = 2
  staleACompletionCasAffectedRows = 0
  bCompletionCasAffectedRows = 1
}
[void](Assert-StaleWorkerObservation $valid)

$diagnosticRequestId = "wf-diag-run-1"
$preSeededAssessment = [pscustomobject]@{
  aiExecutions = @(
    [pscustomobject]@{ agent_type = "ASSESSMENT"; request_id = "t15-eval-run-1" }
  )
}
$assessmentCount = Get-StaleWorkerDiagnosticExecutionCount `
  $preSeededAssessment $diagnosticRequestId
if ($assessmentCount -ne 0) {
  throw "pre-seeded ASSESSMENT execution was incorrectly attributed to the stale diagnostic"
}
$mixedExecutions = [pscustomobject]@{
  aiExecutions = @(
    [pscustomobject]@{ agent_type = "ASSESSMENT"; request_id = "t15-eval-run-1" },
    [pscustomobject]@{ agent_type = "DIAGNOSTIC"; request_id = "wf-diag-other-run" },
    [pscustomobject]@{ agent_type = "DIAGNOSTIC"; request_id = $diagnosticRequestId }
  )
}
$exactDiagnosticCount = Get-StaleWorkerDiagnosticExecutionCount `
  $mixedExecutions $diagnosticRequestId
if ($exactDiagnosticCount -ne 1) {
  throw "exact stale-worker diagnostic lineage count expected 1 but was $exactDiagnosticCount"
}

[void](Assert-StaleWorkerExecutionTokenCleared $null)
[void](Assert-StaleWorkerExecutionTokenCleared "")
$nonEmptyTokenRejected = $false
$nonEmptyTokenRejection = ""
try {
  [void](Assert-StaleWorkerExecutionTokenCleared "01900000-0000-7000-8000-000000000903")
} catch {
  $nonEmptyTokenRejected = $true
  $nonEmptyTokenRejection = $_.Exception.Message
}
if (-not $nonEmptyTokenRejected) {
  throw "non-empty stale-worker final execution token unexpectedly passed"
}

function Assert-NegativeRejected {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)]$Mutation
  )
  $rejected = $false
  $message = ""
  try {
    [void](Assert-StaleWorkerObservation $Mutation)
  } catch {
    $rejected = $true
    $message = $_.Exception.Message
  }
  if (-not $rejected) {
    throw "negative stale-worker perturbation '$Name' unexpectedly passed"
  }
  return [pscustomobject]@{ name = $Name; expected = "FAIL"; result = "PASS"; rejection = $message }
}

$missingHold = $valid | ConvertTo-Json | ConvertFrom-Json
$missingHold.aHeld = $false
$holdResult = Assert-NegativeRejected "worker-a-not-held" $missingHold

$missingReclaim = $valid | ConvertTo-Json | ConvertFrom-Json
$missingReclaim.bReclaimed = $false
$reclaimResult = Assert-NegativeRejected "worker-b-never-reclaims" $missingReclaim

# Temporary mutation: model a bypassed production token guard by changing the stale completion
# outcome from zero to one affected row. The validator must reject it, and the mutation is restored
# before the self-test returns.
$originalCas = $valid.staleACompletionCasAffectedRows
$valid.staleACompletionCasAffectedRows = 1
try {
  $bypassResult = Assert-NegativeRejected "stale-token-check-bypassed" $valid
} finally {
  $valid.staleACompletionCasAffectedRows = $originalCas
}
[void](Assert-StaleWorkerObservation $valid)

[ordered]@{
  schema = "m2-t15.stale-worker-negative-proof.v1"
  result = "PASS"
  cases = @($holdResult, $reclaimResult, $bypassResult)
  diagnosticAttribution = [ordered]@{
    preSeededAssessmentCount = $assessmentCount
    exactDiagnosticCount = $exactDiagnosticCount
    result = "PASS"
  }
  clearedTokenAssertion = [ordered]@{
    null = "PASS"
    empty = "PASS"
    nonEmpty = "FAIL"
    nonEmptyRejection = $nonEmptyTokenRejection
    result = "PASS"
  }
  temporaryMutationRestored = ($valid.staleACompletionCasAffectedRows -eq 0)
} | ConvertTo-Json -Depth 20
