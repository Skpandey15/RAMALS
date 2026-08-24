$ErrorActionPreference = "Stop"

$proofPath = Join-Path $PSScriptRoot "contention-proof.ps1"
. $proofPath

$runId = "01900000-0000-7000-8000-000000000801"
$step = "RECORD_EVALUATION_EVIDENCE"
$winnerToken = "01900000-0000-7000-8000-000000000901"
$valid = @(
  [ordered]@{
    podUid = "pod-a"
    backendPid = "101"
    runId = $runId
    step = $step
    claimAttempt = 1
    result = "WON"
    executionToken = $winnerToken
  }
  [ordered]@{
    podUid = "pod-b"
    backendPid = "202"
    runId = $runId
    step = $step
    claimAttempt = 1
    result = "LOST"
    executionToken = $null
  }
)

[void](Assert-ContentionClaimAttemptEvidence $valid $runId $step "pod-a" $winnerToken)

# Negative perturbation: a healthy/idle second pod is not a claim attempt. Removing its record must
# fail the proof, so the qualification runner cannot turn the historical false positive into PASS.
$missingSecondAttempt = @($valid[0])
$negativeFailed = $false
try {
  [void](Assert-ContentionClaimAttemptEvidence $missingSecondAttempt $runId $step "pod-a" $winnerToken)
} catch {
  $negativeFailed = $true
}
if (-not $negativeFailed) {
  throw "negative contention perturbation unexpectedly passed without a second claim attempt"
}

Write-Host "PASS contention-proof self-test: two attempts accepted; missing second attempt rejected"
