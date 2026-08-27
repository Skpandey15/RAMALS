# M2-T15.2 Contract A scenario expectations, and the minimum shape of a scenario evidence record.
#
# Pure functions, for the same reason the proof modules are pure: an expectation that can only be
# exercised by running a crash scenario against a cluster is an expectation nobody checks until it
# is already wrong -- which is exactly how the stale attempt expectation survived #164 and failed
# S3 on a candidate that was behaving correctly.

# The Contract A fail-closed scenarios: an authorized provider submission whose outcome becomes
# ambiguous. Both terminate at DIAGNOSE attempt 1.
function Get-FailClosedScenarioNames {
  return @("diagnostic-provider", "diagnostic-in-flight")
}

function Test-IsFailClosedScenario {
  param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$ScenarioName)
  return (Get-FailClosedScenarioNames) -contains $ScenarioName
}

# Why 1 and not 2.
#
# Before #160 an AI-side death surfaced to the workflow as an ordinary transport failure. The step
# failed *retryably*, the bounded attempt policy ran DIAGNOSE again, and the scenario legitimately
# expected attempt 2.
#
# Contract A deliberately removed that second attempt. Once provider dispatch is authorized and the
# outcome becomes ambiguous, DiagnosticAssessmentService records INDETERMINATE and rethrows it;
# DiagnosticAgentStep maps that to a terminal result, and a terminal result is not retried. Retrying
# is precisely what Contract A forbids after an authorized submission, because a retry could reach
# the provider a second time for one logical request.
#
# So attempt 1 is not a weaker outcome than attempt 2 here -- it is the guarantee. An expectation of
# 2 asserts that the platform retried an ambiguous provider call, which is the defect Contract A
# exists to prevent.
function Get-ExpectedDiagnoseAttempt {
  param(
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ScenarioName,
    [Parameter(Mandatory = $true)][int]$DefaultAttempt
  )
  if (Test-IsFailClosedScenario $ScenarioName) {
    return 1
  }
  return $DefaultAttempt
}

# -- scenario evidence record ------------------------------------------------------------------

# The fields a scenario evidence record must carry to be usable, whether the scenario passed or
# failed. A FAIL that preserves nothing is the case this exists to prevent: S3 failed on one stale
# assertion and left no pre/post state, no pod identity and no logs, so the actual production
# behaviour had to be reconstructed by querying PostgreSQL by hand afterwards.
function Get-RequiredScenarioEvidenceFields {
  return @(
    "schema",
    "scenarioId",
    "result",
    "candidate",
    "perturbation",
    "podLifecycle",
    "correlations",
    "durableState",
    "logsAndTraces",
    "workflowCursor"
  )
}

function Test-ScenarioEvidenceValueAbsent {
  param([Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()]$Value)
  if ($null -eq $Value) {
    return $true
  }
  if ($Value -is [string]) {
    return [string]::IsNullOrWhiteSpace($Value)
  }
  return $false
}

# Throws unless the record is usable. Accepts PASS and FAIL alike: a FAIL record is required to
# carry the same evidence a PASS does, plus the failure message that explains it.
function Assert-ScenarioEvidenceRecord {
  param([Parameter(Mandatory = $true)]$Record)

  foreach ($field in Get-RequiredScenarioEvidenceFields) {
    if (-not $Record.PSObject.Properties.Name.Contains($field)) {
      throw "scenario evidence record is missing required field '$field'"
    }
    if (Test-ScenarioEvidenceValueAbsent $Record.$field) {
      throw "scenario evidence record field '$field' is empty"
    }
  }

  $result = [string]$Record.result
  if ($result -ne "PASS" -and $result -ne "FAIL") {
    throw "scenario evidence result must be PASS or FAIL but was '$result'"
  }
  if ($result -eq "FAIL" -and (Test-ScenarioEvidenceValueAbsent $Record.error)) {
    throw "a failing scenario evidence record must carry the failure message that explains it"
  }
  return $true
}

# The fail-closed scenarios additionally have to preserve the Contract A terminal facts, because
# those are the whole point of running them. Checked separately so an ordinary scenario is not
# required to carry them.
function Assert-FailClosedScenarioEvidence {
  param([Parameter(Mandatory = $true)]$Record)

  [void](Assert-ScenarioEvidenceRecord $Record)
  if (-not (Test-IsFailClosedScenario ([string]$Record.scenarioId))) {
    throw "'$([string]$Record.scenarioId)' is not a Contract A fail-closed scenario"
  }
  foreach ($field in @("failClosedTerminal", "durableState")) {
    if (Test-ScenarioEvidenceValueAbsent $Record.$field) {
      throw "fail-closed scenario evidence must preserve '$field'"
    }
  }
  if ([string]$Record.scenarioId -eq "diagnostic-in-flight" -and
      (Test-ScenarioEvidenceValueAbsent $Record.inFlightProof) -and
      [string]$Record.result -eq "PASS") {
    throw "a passing diagnostic-in-flight record must preserve inFlightProof"
  }
  return $true
}
