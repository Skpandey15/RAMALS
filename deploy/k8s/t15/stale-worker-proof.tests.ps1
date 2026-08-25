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
  temporaryMutationRestored = ($valid.staleACompletionCasAffectedRows -eq 0)
} | ConvertTo-Json -Depth 20
