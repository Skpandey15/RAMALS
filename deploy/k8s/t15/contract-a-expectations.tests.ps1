# Offline self-tests for the Contract A scenario expectations and evidence-record shape.
#
# Executes no crash scenario and touches no cluster or database. These exist because the defect
# they cover survived #164 and failed S3 on a candidate that was behaving correctly: the stale
# attempt expectation could only be exercised by running the scenario, so nothing caught it until
# the cluster did.
#
#   pwsh -File .\deploy\k8s\t15\contract-a-expectations.tests.ps1

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
. (Join-Path $scriptRoot "contract-a-expectations.ps1")

$script:Failures = 0
$script:Total = 0

function Test-Value {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][AllowEmptyString()]$Actual,
    [Parameter(Mandatory = $true)][AllowEmptyString()]$Expected
  )
  $script:Total++
  if ("$Actual" -eq "$Expected") {
    Write-Host "  PASS  $Name"
  } else {
    $script:Failures++
    Write-Host "  FAIL  $Name -- expected '$Expected' but was '$Actual'"
  }
}

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
      Write-Host "  FAIL  $Name -- expected rejection but it was accepted"
    }
  }
}

Write-Host "Contract A expectation self-tests"

# -- 1. fail-closed scenarios terminate at DIAGNOSE attempt 1 -----------------------------------

Write-Host " attempt expectations"

Test-Value "diagnostic-provider expects attempt 1" `
  (Get-ExpectedDiagnoseAttempt -ScenarioName "diagnostic-provider" -DefaultAttempt 2) 1

Test-Value "diagnostic-in-flight expects attempt 1" `
  (Get-ExpectedDiagnoseAttempt -ScenarioName "diagnostic-in-flight" -DefaultAttempt 2) 1

# The stale expectation itself: 2 is what #164 left behind and what failed S3 on a correct
# candidate. It must never be what these scenarios resolve to again.
Test-Value "diagnostic-provider does not resolve to the stale attempt 2" `
  ((Get-ExpectedDiagnoseAttempt -ScenarioName "diagnostic-provider" -DefaultAttempt 2) -eq 2) $false

Test-Value "diagnostic-in-flight does not resolve to the stale attempt 2" `
  ((Get-ExpectedDiagnoseAttempt -ScenarioName "diagnostic-in-flight" -DefaultAttempt 2) -eq 2) $false

# A fail-closed scenario is pinned to 1 regardless of what default is handed in, so a future edit
# to an unrelated default cannot quietly reintroduce a retry expectation.
Test-Value "fail-closed pinning ignores a default of 3" `
  (Get-ExpectedDiagnoseAttempt -ScenarioName "diagnostic-provider" -DefaultAttempt 3) 1

# -- 2. every other scenario keeps its existing expectation --------------------------------------

Write-Host " legacy and default expectations are unchanged"

foreach ($legacy in @(
    @{ Name = "diagnostic-commission"; Default = 2 },
    @{ Name = "diagnostic-outcome-commit"; Default = 2 },
    @{ Name = "after-claim"; Default = 2 },
    @{ Name = "after-evidence-effect"; Default = 2 },
    @{ Name = "after-mastery-effect"; Default = 2 },
    @{ Name = "adaptation-handoff"; Default = 2 },
    @{ Name = "adaptation-commission"; Default = 1 },
    @{ Name = "contention"; Default = 1 },
    @{ Name = "stale-worker"; Default = 2 }
  )) {
  Test-Value "$($legacy.Name) keeps default $($legacy.Default)" `
    (Get-ExpectedDiagnoseAttempt -ScenarioName $legacy.Name -DefaultAttempt $legacy.Default) `
    $legacy.Default
}

Test-Value "an unknown scenario keeps its default" `
  (Get-ExpectedDiagnoseAttempt -ScenarioName "some-future-scenario" -DefaultAttempt 2) 2

Test-Value "only two scenarios are fail-closed" (Get-FailClosedScenarioNames).Count 2
Test-Value "diagnostic-commission is not fail-closed" `
  (Test-IsFailClosedScenario "diagnostic-commission") $false
Test-Value "diagnostic-outcome-commit is not fail-closed" `
  (Test-IsFailClosedScenario "diagnostic-outcome-commit") $false

# -- 3. a failing scenario still leaves a valid evidence record ----------------------------------

Write-Host " evidence records survive failure"

function New-EvidenceRecord {
  param([string]$ScenarioId = "diagnostic-provider", [string]$Result = "PASS")
  return [pscustomobject]@{
    schema = "m2-t15.scenario-evidence.v1"
    scenarioId = $ScenarioId
    result = $Result
    error = if ($Result -eq "FAIL") { "diagnose attempts expected '2' but was '1'" } else { $null }
    candidate = [pscustomobject]@{ sourceCommit = "b79df3b391ba04f972d08d740a06a42de23385d1" }
    perturbation = [pscustomobject]@{ type = "ai-pod-death" }
    podLifecycle = [pscustomobject]@{
      deletedPod = "ramals-ai-aaa"; deletedPodUid = "01900000-0000-7000-8000-00000000000a"
      replacementPod = "ramals-ai-bbb"; replacementPodUid = "01900000-0000-7000-8000-00000000000b"
    }
    correlations = [pscustomobject]@{ requestId = "wf-diag-01900000-0000-7000-8000-000000000001" }
    durableState = [pscustomobject]@{ before = "pre-state.json"; after = "post-state.json" }
    logsAndTraces = [pscustomobject]@{ deletedPod = "deleted-pod-correlation.log" }
    workflowCursor = [pscustomobject]@{ history = "cursor-history.log"; final = "FAILED" }
    failClosedTerminal = [pscustomobject]@{
      diagnosticErrorCode = "AI_EXECUTION_OUTCOME_INDETERMINATE"
      diagnoseReasonCode = "DIAGNOSIS_EXECUTION_INDETERMINATE"
    }
    inFlightProof = $null
  }
}

Test-Case "a PASS record is valid" {
  Assert-ScenarioEvidenceRecord (New-EvidenceRecord)
} $true

# The case this whole section exists for.
Test-Case "a FAIL record carrying the full evidence set is valid" {
  Assert-ScenarioEvidenceRecord (New-EvidenceRecord -Result "FAIL")
} $true

Test-Case "a FAIL record with the fail-closed terminal proof is valid" {
  Assert-FailClosedScenarioEvidence (New-EvidenceRecord -Result "FAIL")
} $true

Test-Case "rejects a FAIL record with no failure message" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.error = ""
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects a record with no durable state" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.durableState = $null
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects a record with no pod identities" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.podLifecycle = $null
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects a record with no correlation ids" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.correlations = $null
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects a record with no logs" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.logsAndTraces = $null
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects a record with no workflow cursor" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.workflowCursor = $null
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects a record with no candidate identity" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.candidate = $null
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects an unknown result value" {
  $r = New-EvidenceRecord; $r.result = "PENDING"
  Assert-ScenarioEvidenceRecord $r
} $false

Test-Case "rejects a fail-closed record missing the terminal proof" {
  $r = New-EvidenceRecord -Result "FAIL"; $r.failClosedTerminal = $null
  Assert-FailClosedScenarioEvidence $r
} $false

Test-Case "rejects a non-fail-closed scenario through the fail-closed check" {
  Assert-FailClosedScenarioEvidence (New-EvidenceRecord -ScenarioId "diagnostic-commission")
} $false

Test-Case "rejects a passing diagnostic-in-flight record with no inFlightProof" {
  $r = New-EvidenceRecord -ScenarioId "diagnostic-in-flight" -Result "PASS"
  Assert-FailClosedScenarioEvidence $r
} $false

# A failing in-flight run has no proof to carry -- the proof is what threw. It must still be a
# valid record, or the FAIL path preserves nothing again.
Test-Case "accepts a failing diagnostic-in-flight record with no inFlightProof" {
  $r = New-EvidenceRecord -ScenarioId "diagnostic-in-flight" -Result "FAIL"
  Assert-FailClosedScenarioEvidence $r
} $true

Write-Host ""
if ($script:Failures -ne 0) {
  Write-Host "Contract A expectation self-tests FAILED: $($script:Failures) of $($script:Total)"
  exit 1
}
Write-Host "Contract A expectation self-tests passed: $($script:Total)/$($script:Total)"
