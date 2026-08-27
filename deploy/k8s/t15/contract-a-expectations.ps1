# M2-T15.2 Contract A scenario expectations, and the minimum shape of a scenario evidence record.
#
# Pure functions, for the same reason the proof modules are pure: an expectation that can only be
# exercised by running a crash scenario against a cluster is an expectation nobody checks until it
# is already wrong -- which is exactly how the stale attempt expectation survived #164 and failed
# S3 on a candidate that was behaving correctly.

# The Contract A fail-closed scenarios: an authorized provider submission whose outcome becomes
# ambiguous. Both terminate INDETERMINATE, and both must preserve their evidence on failure.
#
# This set says nothing about DIAGNOSE attempt counts. Treating it as if it did is the #165 defect:
# fail-closed describes the terminal *semantics*, and the attempt count is decided by something
# else entirely -- which worker died. Get-ExpectedDiagnoseAttempt below keys on that instead.
function Get-FailClosedScenarioNames {
  return @("diagnostic-provider", "diagnostic-in-flight")
}

function Test-IsFailClosedScenario {
  param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$ScenarioName)
  return (Get-FailClosedScenarioNames) -contains $ScenarioName
}

# Expected DIAGNOSE attempt, per scenario, keyed on which worker dies.
#
# There is no shared rule here, and #165 failed because it invented one. Both Contract A
# fail-closed scenarios end INDETERMINATE, so it looked as though both must end at attempt 1. They
# do not, and the difference is not the terminal status -- it is whose worker the fault kills.
#
#   diagnostic-provider  -> 1. The AI worker dies. The original platform worker SURVIVES, catches
#                           the failure, records INDETERMINATE in-process and returns a terminal
#                           result. A terminal result is not retried, so its first and only attempt
#                           is the one that resolves the request. Observed in the S3 evidence:
#                           DIAGNOSE FAILED, attempt 1.
#
#   diagnostic-in-flight -> 2. The PLATFORM worker dies, and attempt 1 dies with it. A replacement
#                           worker claims the step as attempt 2, observes the durable IN_FLIGHT
#                           row, and closes it INDETERMINATE without redispatching. Attempt 2 is
#                           the recovery attempt -- it is the guarantee this scenario exists to
#                           prove, not a retry of the provider call. That no second submission
#                           occurred on it is proven separately and durably by fence = 1 and
#                           providerInvocations = 1. Observed in the S4 evidence: DIAGNOSE FAILED,
#                           attempt 2.
#
# Both numbers are transcribed from observed run evidence rather than derived from reasoning about
# what the application ought to do. Reasoning is what produced the wrong answer twice: #164 left
# diagnostic-provider on the pre-#160 value of 2, and #165 then pinned both scenarios to 1.
#
# An explicit per-scenario map, deliberately. A future scenario gets its own entry and its own
# justification; it does not inherit a number from a category it happens to share.
function Get-ScenarioDiagnoseAttemptOverrides {
  return @{
    "diagnostic-provider" = 1
    "diagnostic-in-flight" = 2
  }
}

function Get-ExpectedDiagnoseAttempt {
  param(
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ScenarioName,
    [Parameter(Mandatory = $true)][int]$DefaultAttempt
  )
  $overrides = Get-ScenarioDiagnoseAttemptOverrides
  if ($overrides.ContainsKey($ScenarioName)) {
    return $overrides[$ScenarioName]
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
