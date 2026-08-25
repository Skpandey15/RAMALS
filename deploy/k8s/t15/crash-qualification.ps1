[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ApprovedCommit,
  [string]$ApprovedRef = "origin/main",
  [ValidateSet(
    "all",
    "after-claim",
    "after-evidence-effect",
    "after-mastery-effect",
    "diagnostic-commission",
    "diagnostic-provider",
    "diagnostic-outcome-commit",
    "adaptation-handoff",
    "adaptation-commission",
    "contention",
    "stale-worker"
  )]
  [string]$Scenario = "all",
  [string]$ClusterName = "t15",
  [string]$Namespace = "ramals-t15",
  [string]$EvidenceRoot = "",
  [string]$LockPath = "",
  [string]$ManifestRoot = ""
)

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
Set-Location $repositoryRoot
. (Join-Path $scriptRoot "contention-proof.ps1")
. (Join-Path $scriptRoot "stale-worker-proof.ps1")
$qualificationManifestRoot = if ([string]::IsNullOrWhiteSpace($ManifestRoot)) {
  $scriptRoot
} else {
  (Resolve-Path $ManifestRoot).Path
}
$qualificationLockPath = if ([string]::IsNullOrWhiteSpace($LockPath)) {
  Join-Path $qualificationManifestRoot "images.lock.json"
} else {
  (Resolve-Path $LockPath).Path
}

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
  $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
  $EvidenceRoot = Join-Path $scriptRoot "evidence\m2-t15.2-$stamp"
}
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null

$script:Summary = [System.Collections.Generic.List[string]]::new()
$script:CandidateIdentity = $null
$script:ActiveStaleWorkerContext = $null
$script:StaleWorkerClaimBarrierDirectory = "/tmp/ramals-qualification"
$script:StepIndexes = @{
  "" = -1
  "RECORD_EVALUATION_EVIDENCE" = 0
  "RECOMPUTE_MASTERY" = 1
  "DIAGNOSE" = 2
  "ADAPT" = 3
}

function Invoke-Kubectl {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  $output = & kubectl @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "kubectl $($Arguments -join ' ') failed with exit code $LASTEXITCODE`n$($output -join "`n")"
  }
  return ($output -join "`n")
}

function Invoke-KubectlJson {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  $text = Invoke-Kubectl ($Arguments + @("-o", "json"))
  return $text | ConvertFrom-Json
}

function Invoke-Psql {
  param([Parameter(Mandatory = $true)][string]$Sql)
  $output = $Sql | & kubectl exec -i postgres-0 -n $Namespace -- sh -ec `
    'psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -X -P pager=off' 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "in-cluster PostgreSQL command failed with exit code $LASTEXITCODE`n$($output -join "`n")"
  }
  return ($output -join "`n")
}

function Invoke-PsqlAt {
  param([Parameter(Mandatory = $true)][string]$Sql)
  # kubectl exec multiplexes stderr with the command stream in some k3d/websocket paths. Keep
  # that diagnostic channel out of the pipe-delimited PostgreSQL result: a transport warning is
  # not a claim row and must never be accepted as qualification evidence. The process exit code
  # remains authoritative, so an actual psql/kubectl failure still fails the qualification run.
  $output = $Sql | & kubectl exec -i postgres-0 -n $Namespace -- sh -ec `
    'psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -X -A -t -F "|"' 2>$null
  if ($LASTEXITCODE -ne 0) {
    throw "in-cluster PostgreSQL scalar command failed with exit code $LASTEXITCODE`n$($output -join "`n")"
  }
  return ($output -join "`n")
}

function Get-Scalar {
  param([Parameter(Mandatory = $true)][string]$Sql)
  $text = Invoke-PsqlAt $Sql
  $line = @($text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
  if ($line.Count -eq 0) {
    return ""
  }
  return ([string]$line[0]).Trim()
}

function Assert-Equal {
  param(
    [Parameter(Mandatory = $true)][string]$Label,
    [AllowEmptyString()][string]$Actual,
    [Parameter(Mandatory = $true)][string]$Expected
  )
  if ($Actual -ne $Expected) {
    throw "$Label expected '$Expected' but was '$Actual'"
  }
}

function Invoke-CandidateIntegrityGate {
  $gatePath = Join-Path $scriptRoot "candidate-integrity.ps1"
  $output = & pwsh -NoProfile -File $gatePath `
    -ApprovedCommit $ApprovedCommit `
    -ApprovedRef $ApprovedRef `
    -ClusterName $ClusterName `
    -Namespace $Namespace `
    -ManifestRoot $qualificationManifestRoot `
    -LockPath $qualificationLockPath `
    -EvidenceDirectory $EvidenceRoot 2>&1
  $output | Set-Content -LiteralPath (Join-Path $EvidenceRoot "candidate-integrity-gate.log") -Encoding utf8
  if ($LASTEXITCODE -ne 0) {
    throw "candidate-integrity gate failed with exit code $LASTEXITCODE"
  }
  $resultPath = Join-Path $EvidenceRoot "candidate-integrity.json"
  if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
    throw "candidate-integrity gate did not produce $resultPath"
  }
  $script:CandidateIdentity = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
  if ($script:CandidateIdentity.result -ne "PASS") {
    throw "candidate-integrity gate result was '$($script:CandidateIdentity.result)'"
  }
  return $script:CandidateIdentity
}

function Invoke-StaleWorkerNegativeProofTests {
  $testPath = Join-Path $scriptRoot "stale-worker-proof.tests.ps1"
  $output = & pwsh -NoProfile -File $testPath 2>&1
  if ($LASTEXITCODE -ne 0) {
    $output | Set-Content -LiteralPath (Join-Path $EvidenceRoot "stale-worker-negative-proof.log") -Encoding utf8
    throw "stale-worker negative proof self-test failed with exit code $LASTEXITCODE"
  }
  $raw = $output -join "`n"
  try {
    $result = $raw | ConvertFrom-Json
  } catch {
    throw "stale-worker negative proof self-test did not return valid JSON: $raw"
  }
  if ($result.result -ne "PASS" -or -not [bool]$result.temporaryMutationRestored) {
    throw "stale-worker negative proof self-test did not reject and restore every perturbation"
  }
  $result | ConvertTo-Json -Depth 30 |
    Set-Content -LiteralPath (Join-Path $EvidenceRoot "stale-worker-negative-proof.json") -Encoding utf8
  return $result
}

function New-QualificationUuid7 {
  $hex = ([guid]::NewGuid()).ToString("N")
  return "01900000-$($hex.Substring(0, 4))-7$($hex.Substring(4, 3))-8$($hex.Substring(7, 3))-$($hex.Substring(10, 12))"
}

function New-ScenarioFixture {
  param([Parameter(Mandatory = $true)][string]$Name)
  $runId = ([guid]::NewGuid()).ToString()
  $compact = $runId.Replace("-", "").Substring(0, 12)
  [pscustomobject]@{
    Name = $Name
    RunId = $runId
    LearnerId = ([guid]::NewGuid()).ToString()
    AttemptId = ([guid]::NewGuid()).ToString()
    ContentionStepId = ([guid]::NewGuid()).ToString()
    EvaluationExecutionId = ([guid]::NewGuid()).ToString()
    EvaluationDecisionId = ([guid]::NewGuid()).ToString()
    EvaluationStartedEventId = ([guid]::NewGuid()).ToString()
    EvaluationTerminalEventId = ([guid]::NewGuid()).ToString()
    InteractionId = New-QualificationUuid7
    TraceId = ([guid]::NewGuid()).ToString("N")
    EvaluationRequestId = "t15-eval-$compact"
    EvaluationProposalId = "t15-eval-proposal-$compact"
    EvaluationAgentRunId = "t15-eval-run-$compact"
    DiagnosticRequestId = "wf-diag-$runId"
    Subject = "m2-t15-$Name-$compact-$runId"
    EvaluationContextId = "t15-eval-context-$compact"
    SkillId = "01900000-0000-7000-8000-000000000101"
    CurriculumVersionId = "01900000-0000-7000-8000-000000000002"
    AssessmentVersionId = "01900000-0000-7000-8000-000000000402"
    AssessmentVersionCode = "v1"
  }
}

function Seed-ScenarioFixture {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [bool]$PrecreateContentionStep = $false
  )
  $contentionStep = if ($PrecreateContentionStep) {
    @"

INSERT INTO core.learning_workflow_step
  (id, run_id, step_name, step_index, status, attempt_count, execution_token, claimed_at)
VALUES
  ('$($Fixture.ContentionStepId)', '$($Fixture.RunId)', 'RECORD_EVALUATION_EVIDENCE', 0,
   'PENDING', 0, NULL, NULL);
"@
  } else {
    ""
  }
  $sql = @"
BEGIN;
INSERT INTO core.learner (id, subject, status)
VALUES ('$($Fixture.LearnerId)', '$($Fixture.Subject)', 'ACTIVE');

INSERT INTO core.assessment_attempt
  (id, learner_id, assessment_version_id, status, idempotency_key, interaction_id)
VALUES
  ('$($Fixture.AttemptId)', '$($Fixture.LearnerId)', '$($Fixture.AssessmentVersionId)',
   'COMPLETED', 'm2-t15-$($Fixture.Name)-$($Fixture.RunId)', '$($Fixture.InteractionId)');

INSERT INTO ledger.grounding_retrieval_record
  (context_id, learner_id, retrieval_policy_version, as_of, expires_at, source_refs, source_count)
VALUES
  ('$($Fixture.EvaluationContextId)', '$($Fixture.LearnerId)', 'GROUNDING_RETRIEVAL_V1',
   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes',
   '["seed-assessment-context"]'::jsonb, 1);

INSERT INTO core.ai_execution
  (id, request_id, interaction_id, agent_type, contract_version, agent_version,
   agent_run_id, prompt_template_id, prompt_version, model_route, model_id, status,
   resolved_provider, route_version, trace_id, request_digest, proposal_digest,
   input_tokens, output_tokens, estimated_cost_usd, latency_ms, started_at, completed_at)
VALUES
  ('$($Fixture.EvaluationExecutionId)', '$($Fixture.EvaluationRequestId)', '$($Fixture.InteractionId)',
   'ASSESSMENT', '1.0', 'T15_SEED_AGENT_V1', '$($Fixture.EvaluationAgentRunId)',
   'ASSESSMENT_EVALUATION', 'ASSESSMENT_EVALUATION_PROMPT_V1', 'ci-fake', 'ci-fake-deterministic-v1',
   'SUCCEEDED', 'ci-fake', 'T15.2-SEED', '$($Fixture.TraceId)', repeat('a', 64), repeat('b', 64),
   10, 12, 0, 1, CURRENT_TIMESTAMP - INTERVAL '1 second', CURRENT_TIMESTAMP);

INSERT INTO core.ai_execution_event
  (id, request_id, interaction_id, agent_type, contract_version, event_type,
   request_digest, occurred_at, started_at)
VALUES
  ('$($Fixture.EvaluationStartedEventId)', '$($Fixture.EvaluationRequestId)', '$($Fixture.InteractionId)',
   'ASSESSMENT', '1.0', 'STARTED', repeat('a', 64), CURRENT_TIMESTAMP - INTERVAL '1 second',
   CURRENT_TIMESTAMP - INTERVAL '1 second');

INSERT INTO core.ai_execution_event
  (id, request_id, interaction_id, agent_type, contract_version, event_type,
   request_digest, proposal_digest, occurred_at, started_at, completed_at)
VALUES
  ('$($Fixture.EvaluationTerminalEventId)', '$($Fixture.EvaluationRequestId)', '$($Fixture.InteractionId)',
   'ASSESSMENT', '1.0', 'SUCCEEDED', repeat('a', 64), repeat('b', 64), CURRENT_TIMESTAMP,
   CURRENT_TIMESTAMP - INTERVAL '1 second', CURRENT_TIMESTAMP);

INSERT INTO ledger.assessment_evaluation_decision
  (id, proposal_id, request_id, agent_run_id, ai_execution_id, context_id,
   answer_evidence_id, answer_version, rubric_version, outcome, reason_codes,
   referenced_evidence_ids, dimension_results, feedback, confidence, deterministic_check,
   policy_version, decision_digest, interaction_id, trace_id)
VALUES
  ('$($Fixture.EvaluationDecisionId)', '$($Fixture.EvaluationProposalId)', '$($Fixture.EvaluationRequestId)',
   '$($Fixture.EvaluationAgentRunId)', '$($Fixture.EvaluationExecutionId)', '$($Fixture.EvaluationContextId)',
   'seed-answer-$($Fixture.RunId)', 'answer-v1', 'RUBRIC_V1', 'ACCEPTED', '["SEED_ACCEPTED"]'::jsonb,
   '["seed-answer"]'::jsonb, '[{"dimensionId":"SEED","score":0.4000}]'::jsonb,
   'Seeded accepted evaluation for T15.2.', 0.80000000, 'NOT_APPLICABLE',
   'EVALUATION_POLICY_V1', repeat('c', 64), '$($Fixture.InteractionId)', '$($Fixture.TraceId)');

INSERT INTO core.learning_workflow_run
  (id, workflow_type, policy_version, trigger_key, learner_id, skill_id,
   curriculum_version_id, attempt_id, assessment_version_id, normalized_score,
   evaluation_request_id, status, current_step, interaction_id, trace_id, deadline_at)
VALUES
  ('$($Fixture.RunId)', 'EVALUATION_TO_ADAPTATION', 'WORKFLOW_POLICY_V1',
   'T15.2:$($Fixture.RunId)', '$($Fixture.LearnerId)', '$($Fixture.SkillId)',
   '$($Fixture.CurriculumVersionId)', '$($Fixture.AttemptId)', '$($Fixture.AssessmentVersionId)',
   0.4000, '$($Fixture.EvaluationRequestId)', 'RUNNING', 'RECORD_EVALUATION_EVIDENCE',
   '$($Fixture.InteractionId)', '$($Fixture.TraceId)', CURRENT_TIMESTAMP + INTERVAL '10 minutes');
$contentionStep
COMMIT;
"@
  [void](Invoke-Psql $sql)
}

function Get-PodNames {
  param([Parameter(Mandatory = $true)][string]$Label)
  $selector = "app.kubernetes.io/name=$Label"
  $output = Invoke-Kubectl @(
    "get", "pods", "-n", $Namespace, "-l", $selector,
    "--field-selector=status.phase=Running", "--no-headers",
    "-o", "custom-columns=NAME:.metadata.name"
  )
  return @($output -split "`r?`n" | ForEach-Object { $_.Trim() } |
    Where-Object { $_ -match "^[A-Za-z0-9][A-Za-z0-9.-]*$" })
}

function Get-PodName {
  param([Parameter(Mandatory = $true)][string]$Label)
  $names = @(Get-PodNames $Label)
  if ($names.Count -eq 0) {
    throw "no running pod found for $Label"
  }
  return [string]$names[0]
}

function Wait-NoPods {
  param([Parameter(Mandatory = $true)][string]$Label)
  for ($i = 0; $i -lt 90; $i++) {
    $selector = "app.kubernetes.io/name=$Label"
    $allPods = Invoke-Kubectl @(
      "get", "pods", "-n", $Namespace, "-l", $selector, "--no-headers",
      "-o", "custom-columns=NAME:.metadata.name"
    )
    $podNames = @($allPods -split "`r?`n" | ForEach-Object { $_.Trim() } |
      Where-Object { $_ -match "^[A-Za-z0-9][A-Za-z0-9.-]*$" })
    if ($podNames.Count -eq 0) {
      return
    }
    Start-Sleep -Milliseconds 500
  }
  throw "pods for $Label did not terminate"
}

function Wait-DeploymentReady {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][int]$Replicas,
    [Parameter(Mandatory = $true)][string]$Label
  )
  [void](Invoke-Kubectl @("scale", "deployment/$Name", "-n", $Namespace, "--replicas=$Replicas"))
  if ($Replicas -eq 0) {
    Wait-NoPods $Label
    return
  }
  for ($i = 0; $i -lt 180; $i++) {
    $generation = (Invoke-Kubectl @(
        "get", "deployment/$Name", "-n", $Namespace, "-o", "jsonpath={.metadata.generation}"
      )).Trim()
    $observedGeneration = (Invoke-Kubectl @(
        "get", "deployment/$Name", "-n", $Namespace, "-o", "jsonpath={.status.observedGeneration}"
      )).Trim()
    $updated = (Invoke-Kubectl @(
        "get", "deployment/$Name", "-n", $Namespace, "-o", "jsonpath={.status.updatedReplicas}"
      )).Trim()
    $ready = (Invoke-Kubectl @(
        "get", "deployment/$Name", "-n", $Namespace, "-o", "jsonpath={.status.readyReplicas}"
      )).Trim()
    $available = (Invoke-Kubectl @(
        "get", "deployment/$Name", "-n", $Namespace, "-o", "jsonpath={.status.availableReplicas}"
      )).Trim()
    if ($observedGeneration -eq $generation -and
        $updated -eq "$Replicas" -and
        $ready -eq "$Replicas" -and
        $available -eq "$Replicas") {
      return
    }
    Start-Sleep -Milliseconds 1000
  }
  throw "deployment $Name did not reach $Replicas ready replicas"
}

function Set-BackendFault {
  param(
    [bool]$Enabled,
    [string]$Window = "",
    [string]$RunId = "",
    [string]$RequestId = "",
    [int]$PauseMs = 120000,
    [string]$Step = "",
    [string]$ClaimBarrierDirectory = ""
  )
  $enabledValue = if ($Enabled) { "true" } else { "false" }
  [void](Invoke-Kubectl @(
      "set", "env", "deployment/learning-platform", "-n", $Namespace,
      "RAMALS_QUALIFICATION_FAULT_ENABLED=$enabledValue",
      "RAMALS_QUALIFICATION_FAULT_WINDOW=$Window",
      "RAMALS_QUALIFICATION_FAULT_RUN_ID=$RunId",
      "RAMALS_QUALIFICATION_FAULT_REQUEST_ID=$RequestId",
      "RAMALS_QUALIFICATION_FAULT_PAUSE_MS=$PauseMs",
      "RAMALS_QUALIFICATION_FAULT_STEP=$Step",
      "RAMALS_QUALIFICATION_FAULT_CLAIM_BARRIER_DIRECTORY=$ClaimBarrierDirectory"
    ))
}

function Set-AiQualification {
  param(
    [bool]$Fixtures,
    [bool]$ProviderPause = $false,
    [string]$RequestId = "",
    [int]$PauseMs = 120000
  )
  $fixturesValue = if ($Fixtures) { "true" } else { "false" }
  $pauseValue = if ($ProviderPause) { "true" } else { "false" }
  [void](Invoke-Kubectl @(
      "set", "env", "deployment/ramals-ai", "-n", $Namespace,
      "RAMALS_AI_QUALIFICATION_FIXTURES=$fixturesValue",
      "RAMALS_AI_QUALIFICATION_PROVIDER_PAUSE_ENABLED=$pauseValue",
      "RAMALS_AI_QUALIFICATION_PROVIDER_PAUSE_REQUEST_ID=$RequestId",
      "RAMALS_AI_QUALIFICATION_PROVIDER_PAUSE_MS=$PauseMs"
    ))
}

function Get-WorkflowState {
  param([Parameter(Mandatory = $true)][string]$RunId)
  $line = Get-Scalar @"
SELECT r.status || '|' || COALESCE(r.current_step, '') || '|' ||
       COALESCE((SELECT s.status FROM core.learning_workflow_step s
                 WHERE s.run_id = r.id AND s.step_name = r.current_step), '') || '|' ||
       COALESCE((SELECT s.attempt_count::text FROM core.learning_workflow_step s
                 WHERE s.run_id = r.id AND s.step_name = r.current_step), '0') || '|' ||
       COALESCE((SELECT s.execution_token::text FROM core.learning_workflow_step s
                 WHERE s.run_id = r.id AND s.step_name = r.current_step), '')
  FROM core.learning_workflow_run r
 WHERE r.id = '$RunId';
"@
  if ([string]::IsNullOrWhiteSpace($line)) {
    throw "workflow $RunId was not found"
  }
  $parts = $line -split '\|', 5
  if ($parts.Count -lt 5) {
    throw "could not parse workflow state for ${RunId}: $line"
  }
  [pscustomobject]@{
    Status = $parts[0]
    CurrentStep = $parts[1]
    StepStatus = $parts[2]
    AttemptCount = $parts[3]
    ExecutionToken = $parts[4]
  }
}

function Format-CursorObservation {
  param([Parameter(Mandatory = $true)]$State)
  return "$((Get-Date).ToUniversalTime().ToString('o'))|$($State.Status)|$($State.CurrentStep)|$($State.StepStatus)|$($State.AttemptCount)|$($State.ExecutionToken)"
}

function Get-StepClaim {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$StepName
  )
  $line = Get-Scalar "SELECT COALESCE(execution_token::text, '') || '|' || attempt_count::text || '|' || status FROM core.learning_workflow_step WHERE run_id = '$RunId' AND step_name = '$StepName';"
  if ([string]::IsNullOrWhiteSpace($line)) {
    throw "step $StepName for workflow $RunId was not found"
  }
  $parts = $line -split '\|', 3
  [pscustomobject]@{ Token = $parts[0]; AttemptCount = $parts[1]; Status = $parts[2] }
}

function Get-StepClaimSnapshot {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$StepName
  )
  $line = Get-Scalar @"
SELECT COALESCE(execution_token::text, '') || '|' || attempt_count::text || '|' || status || '|' ||
       COALESCE(to_char(claimed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '')
  FROM core.learning_workflow_step
 WHERE run_id = '$RunId' AND step_name = '$StepName';
"@
  if ([string]::IsNullOrWhiteSpace($line)) {
    throw "step $StepName for workflow $RunId was not found"
  }
  $parts = $line -split '\|', 4
  if ($parts.Count -lt 4) {
    throw "could not parse claim snapshot for $RunId/$StepName`: $line"
  }
  [pscustomobject]@{
    runId = $RunId
    step = $StepName
    executionToken = $parts[0]
    attemptCount = [int]$parts[1]
    status = $parts[2]
    claimedAt = $parts[3]
    capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  }
}

function Wait-StepClaimAttempt {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$StepName,
    [Parameter(Mandatory = $true)][int]$AttemptCount,
    [string]$TokenMustDifferFrom = "",
    [int]$TimeoutSeconds = 60
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    try {
      $claim = Get-StepClaimSnapshot $RunId $StepName
      if ($claim.status -eq "RUNNING" -and
          $claim.attemptCount -eq $AttemptCount -and
          -not [string]::IsNullOrWhiteSpace($claim.executionToken) -and
          ([string]::IsNullOrWhiteSpace($TokenMustDifferFrom) -or
           $claim.executionToken -ne $TokenMustDifferFrom)) {
        return $claim
      }
    } catch {
      # The first attempt may not have inserted its row yet.
    }
    Start-Sleep -Milliseconds 200
  }
  throw "workflow $RunId/$StepName did not expose RUNNING attempt $AttemptCount"
}

function Wait-WorkflowTerminal {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [int]$TimeoutSeconds = 240
  )
  $history = [System.Collections.Generic.List[string]]::new()
  $lastIndex = -1
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    $state = Get-WorkflowState $RunId
    $index = $script:StepIndexes[$state.CurrentStep]
    if ($null -eq $index) {
      throw "workflow $RunId entered unknown step '$($state.CurrentStep)'"
    }
    if ($index -lt $lastIndex) {
      throw "workflow $RunId cursor moved backwards from $lastIndex to $index"
    }
    $lastIndex = [Math]::Max($lastIndex, $index)
    [void]$history.Add(
      "$((Get-Date).ToUniversalTime().ToString('o'))|$($state.Status)|$($state.CurrentStep)|$($state.StepStatus)|$($state.AttemptCount)|$($state.ExecutionToken)"
    )
    if ($state.Status -ne "RUNNING") {
      return [pscustomobject]@{ State = $state; History = @($history) }
    }
    Start-Sleep -Milliseconds 500
  }
  throw "workflow $RunId did not reach a terminal state within $TimeoutSeconds seconds"
}

function Wait-WorkflowBoundary {
  param(
    [Parameter(Mandatory = $true)][string]$Window,
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RunId,
    [string]$RequestId = "",
    [int]$TimeoutSeconds = 180
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    foreach ($pod in @(Get-PodNames "learning-platform")) {
      $logs = & kubectl logs $pod -n $Namespace --since=15m 2>$null
      $text = $logs -join "`n"
      if (($text -match "qualification crash boundary reached") -and
          ($text -match [regex]::Escape($Window)) -and
          (($text -match [regex]::Escape($RunId)) -or
           ([string]::IsNullOrWhiteSpace($RunId) -and
            ([string]::IsNullOrWhiteSpace($RequestId) -or $text -match [regex]::Escape($RequestId))))) {
        return $pod
      }
    }
    Start-Sleep -Milliseconds 250
  }
  throw "workflow boundary $Window was not observed for run $RunId"
}

function Get-WorkflowBoundaryPods {
  param(
    [Parameter(Mandatory = $true)][string]$Window,
    [Parameter(Mandatory = $true)][string]$RunId
  )
  $identities = @(Get-BackendPodIdentities)
  return @(
    foreach ($identity in $identities) {
      $logs = & kubectl logs $identity.podName -n $Namespace --since=15m 2>$null
      $text = $logs -join "`n"
      if ($text -match "qualification crash boundary reached" -and
          $text -match [regex]::Escape($Window) -and
          $text -match [regex]::Escape($RunId)) {
        $identity
      }
    }
  )
}

function Wait-WorkflowBoundaryPodCount {
  param(
    [Parameter(Mandatory = $true)][string]$Window,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][int]$Count,
    [int]$TimeoutSeconds = 60
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    $pods = @(Get-WorkflowBoundaryPods $Window $RunId)
    if ($pods.Count -eq $Count) {
      return $pods
    }
    if ($pods.Count -gt $Count) {
      throw "workflow boundary $Window observed $($pods.Count) pods before expected count $Count"
    }
    Start-Sleep -Milliseconds 200
  }
  throw "workflow boundary $Window observed fewer than $Count pods for run $RunId"
}

function Wait-RealStaleCompletionRejection {
  param(
    [Parameter(Mandatory = $true)][string]$Pod,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$StepName,
    [int]$TimeoutSeconds = 40
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    $logs = & kubectl logs $Pod -n $Namespace --since=15m 2>$null
    $matching = @($logs | Where-Object {
        $line = [string]$_
        $line.Contains("workflow.step.superseded") -and
        $line.Contains($RunId) -and
        $line.Contains($StepName)
      })
    if ($matching.Count -gt 0) {
      return ($matching -join "`n")
    }
    Start-Sleep -Milliseconds 200
  }
  throw "real stale completion rejection was not observed from pod $Pod for $RunId/$StepName"
}

function Wait-StaleWorkerPodTransactionsSettled {
  param(
    [Parameter(Mandatory = $true)][string]$PodIp,
    [int]$TimeoutSeconds = 40
  )
  if ($PodIp -notmatch '^[0-9a-fA-F:.]+$') {
    throw "invalid stale-worker pod IP '$PodIp'"
  }
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    $active = Get-Scalar @"
SELECT COUNT(*)::text
  FROM pg_stat_activity
 WHERE client_addr = '$PodIp'::inet
   AND xact_start IS NOT NULL;
"@
    if ($active -eq "0") {
      return [pscustomobject]@{
        podIp = $PodIp
        activeTransactions = 0
        observedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
      }
    }
    Start-Sleep -Milliseconds 50
  }
  throw "stale worker pod $PodIp still had an open PostgreSQL transaction after its completion result"
}

function Wait-AiProviderBoundary {
  param(
    [Parameter(Mandatory = $true)][string]$RequestId,
    [int]$TimeoutSeconds = 180
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    foreach ($pod in @(Get-PodNames "ramals-ai")) {
      $logs = & kubectl logs $pod -n $Namespace --since=15m 2>$null
      $text = $logs -join "`n"
      if (($text -match "qualification provider boundary reached") -and
          ($text -match "DIAGNOSTIC_PROVIDER_EXECUTION") -and
          ($text -match [regex]::Escape($RequestId))) {
        return $pod
      }
    }
    Start-Sleep -Milliseconds 250
  }
  throw "diagnostic provider boundary was not observed for request $RequestId"
}

function Expire-WorkflowClaim {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$StepName
  )
  $changed = Get-Scalar "WITH expired AS (UPDATE core.learning_workflow_step SET claimed_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes' WHERE run_id = '$RunId' AND step_name = '$StepName' AND status = 'RUNNING' RETURNING 1) SELECT COUNT(*) FROM expired;"
  if ($changed -ne "1") {
    throw "could not expire the captured workflow claim for $RunId/$StepName; observed '$changed'"
  }
}

function Expire-OutboxLease {
  param([Parameter(Mandatory = $true)][string]$WorkId)
  $changed = Get-Scalar "WITH expired AS (UPDATE core.agent_work_outbox SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes' WHERE id = '$WorkId' AND status = 'CLAIMED' RETURNING 1) SELECT COUNT(*) FROM expired;"
  if ($changed -ne "1") {
    throw "could not expire the captured outbox lease $WorkId; observed '$changed'"
  }
}

function Get-PodUid {
  param([Parameter(Mandatory = $true)][string]$Pod)
  return (Invoke-Kubectl @("get", "pod", $Pod, "-n", $Namespace, "-o", "jsonpath={.metadata.uid}")).Trim()
}

function Get-BackendPodIdentities {
  $podList = Invoke-KubectlJson @(
    "get", "pods", "-n", $Namespace,
    "-l", "app.kubernetes.io/name=learning-platform",
    "--field-selector", "status.phase=Running"
  )
  return @(
    foreach ($item in @($podList.items)) {
      if ($item.status.phase -ne "Running") {
        continue
      }
      [pscustomobject]@{
        podName = [string]$item.metadata.name
        podUid = [string]$item.metadata.uid
        podIp = [string]$item.status.podIP
      }
    }
  )
}

function Get-StaleWorkerClaimBoundaries {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$Step,
    [Parameter(Mandatory = $true)][int]$AttemptCount,
    [Parameter(Mandatory = $true)][string]$ExecutionToken
  )
  $identities = @(Get-BackendPodIdentities)
  return @(
    foreach ($identity in $identities) {
      $paths = & kubectl exec $identity.podName -n $Namespace -c learning-platform -- `
        find $script:StaleWorkerClaimBarrierDirectory -maxdepth 1 -type f -name "held-*.json" -print 2>$null
      if ($LASTEXITCODE -ne 0) {
        continue
      }
      foreach ($path in @($paths | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })) {
        $markerPath = ([string]$path).Trim()
        if ($markerPath -notmatch '^/tmp/ramals-qualification/held-[A-Za-z0-9._-]+\.json$') {
          throw "unsafe stale-worker claim marker path '$markerPath'"
        }
        $raw = (& kubectl exec $identity.podName -n $Namespace -c learning-platform -- `
          cat $markerPath 2>$null) -join "`n"
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) {
          continue
        }
        try {
          $marker = $raw | ConvertFrom-Json
        } catch {
          throw "invalid stale-worker claim marker from $($identity.podName)/$markerPath"
        }
        if ([string]$marker.runId -ne $RunId -or
            [string]$marker.step -ne $Step -or
            [int]$marker.attemptCount -ne $AttemptCount -or
            [string]$marker.executionToken -ne $ExecutionToken) {
          continue
        }
        if ([string]$marker.schema -ne "m2-t15.workflow-after-claim-barrier.v1" -or
            [string]$marker.state -ne "HELD" -or
            [string]$marker.podName -ne [string]$identity.podName -or
            [string]$marker.podUid -ne [string]$identity.podUid -or
            [string]$marker.podIp -ne [string]$identity.podIp) {
          throw "stale-worker claim marker identity does not match its live pod"
        }
        $releasePath = [string]$marker.releasePath
        if ($releasePath -notmatch '^/tmp/ramals-qualification/release-[A-Za-z0-9._-]+$') {
          throw "unsafe stale-worker release path '$releasePath'"
        }
        [pscustomobject]@{
          schema = [string]$marker.schema
          state = [string]$marker.state
          runId = [string]$marker.runId
          step = [string]$marker.step
          attemptCount = [int]$marker.attemptCount
          executionToken = [string]$marker.executionToken
          podName = [string]$identity.podName
          podUid = [string]$identity.podUid
          podIp = [string]$identity.podIp
          processId = [long]$marker.processId
          threadId = [long]$marker.threadId
          interactionId = [string]$marker.interactionId
          traceId = [string]$marker.traceId
          heldAtUtc = [string]$marker.heldAtUtc
          markerPath = $markerPath
          releasePath = $releasePath
          observedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        }
      }
    }
  )
}

function Wait-StaleWorkerClaimBoundary {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$Step,
    [Parameter(Mandatory = $true)][int]$AttemptCount,
    [Parameter(Mandatory = $true)][string]$ExecutionToken,
    [int]$TimeoutSeconds = 60
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    $boundaries = @(Get-StaleWorkerClaimBoundaries $RunId $Step $AttemptCount $ExecutionToken)
    if ($boundaries.Count -eq 1) {
      return $boundaries[0]
    }
    if ($boundaries.Count -gt 1) {
      throw "stale-worker claim $RunId/$Step/$AttemptCount/$ExecutionToken reached multiple barriers"
    }
    Start-Sleep -Milliseconds 100
  }
  throw "stale-worker claim $RunId/$Step/$AttemptCount/$ExecutionToken did not reach its explicit barrier"
}

function Assert-StaleWorkerClaimHeld {
  param([Parameter(Mandatory = $true)]$Boundary)
  & kubectl exec $Boundary.podName -n $Namespace -c learning-platform -- `
    test ! -e $Boundary.releasePath 1>$null 2>$null
  if ($LASTEXITCODE -ne 0) {
    throw "stale-worker claim barrier was already released for $($Boundary.executionToken)"
  }
}

function Release-StaleWorkerClaimBoundary {
  param([Parameter(Mandatory = $true)]$Boundary)
  if ([string]$Boundary.releasePath -notmatch '^/tmp/ramals-qualification/release-[A-Za-z0-9._-]+$') {
    throw "unsafe stale-worker release path '$($Boundary.releasePath)'"
  }
  [void](Invoke-Kubectl @(
      "exec", [string]$Boundary.podName, "-n", $Namespace, "-c", "learning-platform", "--",
      "touch", [string]$Boundary.releasePath
    ))
  return [pscustomobject]@{
    runId = [string]$Boundary.runId
    step = [string]$Boundary.step
    attemptCount = [int]$Boundary.attemptCount
    executionToken = [string]$Boundary.executionToken
    podName = [string]$Boundary.podName
    releasePath = [string]$Boundary.releasePath
    releasedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  }
}

function Save-StaleWorkerClaimant {
  param(
    [Parameter(Mandatory = $true)][ValidateSet("a", "b")][string]$Label,
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)]$Claim,
    [Parameter(Mandatory = $true)]$Boundary,
    [Parameter(Mandatory = $true)][string]$Directory
  )
  if ([string]$Boundary.runId -ne [string]$Claim.runId -or
      [string]$Boundary.step -ne [string]$Claim.step -or
      [int]$Boundary.attemptCount -ne [int]$Claim.attemptCount -or
      [string]$Boundary.executionToken -ne [string]$Claim.executionToken) {
    throw "stale-worker claim boundary does not match the captured PostgreSQL claim"
  }
  $claimant = [ordered]@{
    runId = $Fixture.RunId
    step = "RECORD_EVALUATION_EVIDENCE"
    podName = $Boundary.podName
    podUid = $Boundary.podUid
    podIp = $Boundary.podIp
    processId = $Boundary.processId
    threadId = $Boundary.threadId
    executionToken = $Claim.executionToken
    attemptCount = $Claim.attemptCount
    claimedAt = $Claim.claimedAt
    barrierHeldAtUtc = $Boundary.heldAtUtc
    barrierObservedAtUtc = $Boundary.observedAtUtc
    markerPath = $Boundary.markerPath
    releasePath = $Boundary.releasePath
    requestId = $Fixture.EvaluationRequestId
    diagnosticRequestId = $Fixture.DiagnosticRequestId
    interactionId = $Fixture.InteractionId
    traceId = $Fixture.TraceId
  }
  # The identity file is the first durable operation after the claimant and claim row are paired.
  $claimant | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $Directory "claimant-$Label.json") -Encoding utf8
  (Get-SafePodLogText $Boundary.podName $Fixture) |
    Set-Content -LiteralPath (Join-Path $Directory "claimant-$Label-boundary.log") -Encoding utf8
  return [pscustomobject]$claimant
}

function Expire-ExactStaleWorkerClaim {
  param(
    [Parameter(Mandatory = $true)]$Claim
  )
  $result = Get-Scalar @"
WITH expired AS (
  UPDATE core.learning_workflow_step
     SET claimed_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes'
   WHERE run_id = '$($Claim.runId)'
     AND step_name = '$($Claim.step)'
     AND status = 'RUNNING'
     AND execution_token = '$($Claim.executionToken)'
     AND attempt_count = $($Claim.attemptCount)
  RETURNING run_id, step_name, execution_token, attempt_count, claimed_at
)
SELECT jsonb_build_object(
  'changedRows', COUNT(*),
  'runId', '$($Claim.runId)',
  'step', '$($Claim.step)',
  'token', '$($Claim.executionToken)',
  'attempt', $($Claim.attemptCount),
  'newClaimedAt', (SELECT claimed_at FROM expired LIMIT 1)
)::text
FROM expired;
"@
  $proof = $result | ConvertFrom-Json
  if ([int]$proof.changedRows -ne 1) {
    throw "could not expire the exact captured stale-worker claim; changed rows '$($proof.changedRows)'"
  }
  return $proof
}

function Start-ContentionRowBarrier {
  param([Parameter(Mandatory = $true)][string]$StepId)
  $coordinatorPath = Join-Path $scriptRoot "qualification-coordinator.ps1"
  $output = & pwsh -NoProfile -File $coordinatorPath `
    -Action start `
    -ClusterName $ClusterName `
    -Namespace $Namespace `
    -Table "core.learning_workflow_step" `
    -RowId $StepId 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "could not start contention row barrier: $($output -join "`n")"
  }

  $end = (Get-Date).ToUniversalTime().AddSeconds(60)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    $barrierBackendPid = Get-Scalar @"
SELECT pid::text
  FROM pg_stat_activity
 WHERE datname = current_database()
   AND pid <> pg_backend_pid()
   AND query ILIKE '%core.learning_workflow_step%'
   AND query ILIKE '%$StepId%'
   AND query ILIKE '%pg_sleep(86400)%'
 ORDER BY pid
 LIMIT 1;
"@
    if (-not [string]::IsNullOrWhiteSpace($barrierBackendPid)) {
      return [pscustomobject]@{
        podName = "t15-pg-lock-coordinator"
        pid = $barrierBackendPid
        rowId = $StepId
        startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        observedAtUtc = ""
        waiterSessions = @()
        preState = ""
        releasedAtUtc = ""
      }
    }
    Start-Sleep -Milliseconds 250
  }
  throw "contention row barrier did not expose its PostgreSQL session for step $StepId"
}

function Stop-ContentionRowBarrier {
  $coordinatorPath = Join-Path $scriptRoot "qualification-coordinator.ps1"
  $output = & pwsh -NoProfile -File $coordinatorPath `
    -Action stop `
    -ClusterName $ClusterName `
    -Namespace $Namespace 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "could not stop contention row barrier: $($output -join "`n")"
  }
}

function Get-ContentionClaimWaiters {
  param([Parameter(Mandatory = $true)][string]$BarrierPid)
  if ($BarrierPid -notmatch '^[0-9]+$') {
    throw "invalid contention barrier PostgreSQL PID '$BarrierPid'"
  }
  $raw = Invoke-PsqlAt @"
WITH RECURSIVE claim_sessions AS (
  SELECT a.pid, a.client_addr, a.wait_event_type, a.wait_event, a.query_start, a.query
    FROM pg_stat_activity a
   WHERE a.datname = current_database()
     AND a.usename = 'ramals_core_runtime'
     AND a.state = 'active'
     AND a.query ILIKE '%INSERT INTO core.learning_workflow_step%'
     AND a.query ILIKE '%ON CONFLICT%'
     AND a.wait_event_type = 'Lock'
), lock_chain (claimant_pid, blocker_pid) AS (
  SELECT c.pid, blocker.pid
    FROM claim_sessions c
    CROSS JOIN LATERAL unnest(pg_blocking_pids(c.pid)) AS blocker(pid)
  UNION
  SELECT chain.claimant_pid, blocker.pid
    FROM lock_chain chain
    CROSS JOIN LATERAL unnest(pg_blocking_pids(chain.blocker_pid)) AS blocker(pid)
)
SELECT a.pid::text || '|' ||
       COALESCE(a.client_addr::text, '') || '|' ||
       COALESCE(a.wait_event_type, '') || '|' ||
       COALESCE(a.wait_event, '') || '|' ||
       COALESCE(to_char(a.query_start AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'), '') || '|' ||
       COALESCE(pg_blocking_pids(a.pid)::text, '') || '|' ||
       regexp_replace(COALESCE(a.query, ''), '[[:space:]]+', ' ', 'g')
  FROM claim_sessions a
 WHERE EXISTS (
         SELECT 1 FROM lock_chain chain
          WHERE chain.claimant_pid = a.pid AND chain.blocker_pid = $BarrierPid)
 ORDER BY a.pid;
"@
  $identities = @(Get-BackendPodIdentities)
  $waiters = [System.Collections.Generic.List[object]]::new()
  foreach ($line in @($raw -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
    $parts = ([string]$line).Trim() -split '\|', 7
    if ($parts.Count -lt 7) {
      throw "could not parse PostgreSQL contention waiter row: $line"
    }
    $clientIp = ([string]$parts[1]).Split('/')[0]
    $identity = @($identities | Where-Object { $_.podIp -eq $clientIp }) | Select-Object -First 1
    if ($null -eq $identity) {
      throw "could not map claim session client IP '$($parts[1])' to a running backend pod"
    }
    [void]$waiters.Add([pscustomobject]@{
        podUid = $identity.podUid
        podName = $identity.podName
        backendPid = $parts[0]
        clientIp = $clientIp
        waitEventType = $parts[2]
        waitEvent = $parts[3]
        observedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        blockingPids = $parts[5]
        claimSql = $parts[6]
      })
  }
  return @($waiters)
}

function Wait-ContentionClaimSessions {
  param(
    [Parameter(Mandatory = $true)][string]$BarrierPid,
    [int]$TimeoutSeconds = 120
  )
  $seen = @{}
  $observerLog = Join-Path $EvidenceRoot "contention-observer.log"
  "startedAtUtc=$((Get-Date).ToUniversalTime().ToString('o'))|barrierPid=$BarrierPid" |
    Set-Content -LiteralPath $observerLog -Encoding utf8
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    $observedWaiters = @(Get-ContentionClaimWaiters $BarrierPid)
    $observedPods = @($observedWaiters | ForEach-Object { [string]$_.podUid } | Sort-Object -Unique)
    "observedAtUtc=$((Get-Date).ToUniversalTime().ToString('o'))|waiters=$($observedWaiters.Count)|pods=$($observedPods.Count)|pids=$(@($observedWaiters | ForEach-Object { [string]$_.backendPid } | Sort-Object -Unique) -join ',')" |
      Add-Content -LiteralPath $observerLog -Encoding utf8
    foreach ($waiter in $observedWaiters) {
      $key = "$($waiter.podUid)|$($waiter.backendPid)"
      if (-not $seen.ContainsKey($key)) {
        $seen[$key] = $waiter
      }
    }
    $sessions = @($seen.Values)
    $podUids = @($sessions | ForEach-Object { [string]$_.podUid } | Sort-Object -Unique)
    if ($sessions.Count -eq 2 -and $podUids.Count -eq 2) {
      return $sessions
    }
    if ($sessions.Count -gt 2 -or $podUids.Count -gt 2) {
      throw "contention barrier observed more than two distinct claim sessions or pods; sessions=$($sessions.Count), pods=$($podUids.Count)"
    }
    Start-Sleep -Milliseconds 250
  }
  throw "contention barrier did not observe two distinct backend claim sessions; sessions=$($seen.Count), pods=$(@($seen.Values | ForEach-Object { $_.podUid } | Sort-Object -Unique).Count)"
}

function Wait-ContentionClaimSessionsGone {
  param(
    [Parameter(Mandatory = $true)][string]$BarrierPid,
    [int]$TimeoutSeconds = 60
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    if (@(Get-ContentionClaimWaiters $BarrierPid).Count -eq 0) {
      return
    }
    Start-Sleep -Milliseconds 250
  }
  throw "contention claim sessions did not leave the qualification barrier"
}

function Wait-ReplacementPod {
  param(
    [Parameter(Mandatory = $true)][string]$Label,
    [Parameter(Mandatory = $true)][string]$OldPodUid,
    [int]$TimeoutSeconds = 180
  )
  $end = (Get-Date).ToUniversalTime().AddSeconds($TimeoutSeconds)
  while ((Get-Date).ToUniversalTime() -lt $end) {
    foreach ($pod in @(Get-PodNames $Label)) {
      $uid = Get-PodUid $pod
      if ($uid -ne $OldPodUid) {
        return [pscustomobject]@{ Name = $pod; Uid = $uid }
      }
    }
    Start-Sleep -Milliseconds 500
  }
  throw "no replacement pod appeared for $Label after deleting pod UID $OldPodUid"
}

function Get-ScenarioDbSnapshot {
  param([Parameter(Mandatory = $true)]$Fixture)
  return Get-Scalar @"
SELECT jsonb_build_object(
  'workflow', COALESCE((SELECT to_jsonb(r) FROM core.learning_workflow_run r WHERE r.id = '$($Fixture.RunId)'), '{}'::jsonb),
  'steps', COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.step_index) FROM core.learning_workflow_step s WHERE s.run_id = '$($Fixture.RunId)'), '[]'::jsonb),
  'evidence', COALESCE((SELECT jsonb_agg(to_jsonb(e) ORDER BY e.recorded_at, e.id) FROM ledger.evidence e WHERE e.learner_id = '$($Fixture.LearnerId)' AND e.interaction_id = '$($Fixture.InteractionId)'), '[]'::jsonb),
  'mastery', COALESCE((SELECT jsonb_agg(to_jsonb(m) ORDER BY m.aggregate_version, m.id) FROM ledger.mastery_snapshot m WHERE m.learner_id = '$($Fixture.LearnerId)' AND m.skill_id = '$($Fixture.SkillId)' AND m.curriculum_version_id = '$($Fixture.CurriculumVersionId)'), '[]'::jsonb),
  'evaluationDecision', COALESCE((SELECT to_jsonb(d) FROM ledger.assessment_evaluation_decision d WHERE d.request_id = '$($Fixture.EvaluationRequestId)'), '{}'::jsonb),
  'diagnosticGate', COALESCE((SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM ledger.proposal_gate_decision d WHERE d.request_id = '$($Fixture.DiagnosticRequestId)'), '[]'::jsonb),
  'recommendationDecision', COALESCE((SELECT jsonb_agg(to_jsonb(d) ORDER BY d.decided_at, d.id) FROM ledger.decision_record d WHERE d.learner_id = '$($Fixture.LearnerId)' AND d.interaction_id = '$($Fixture.InteractionId)'), '[]'::jsonb),
  'adaptationOutbox', COALESCE((SELECT jsonb_agg(to_jsonb(w) ORDER BY w.created_at, w.id) FROM core.agent_work_outbox w WHERE w.interaction_id = '$($Fixture.InteractionId)'), '[]'::jsonb),
  'aiExecutions', COALESCE((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.started_at, x.id) FROM core.ai_execution x WHERE x.interaction_id = '$($Fixture.InteractionId)'), '[]'::jsonb),
  'aiEvents', COALESCE((SELECT jsonb_agg(to_jsonb(e) ORDER BY e.occurred_at, e.id) FROM core.ai_execution_event e WHERE e.interaction_id = '$($Fixture.InteractionId)'), '[]'::jsonb)
)::text;
"@
}

function Get-ContentionDbProof {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)][string]$StepName
  )
  return Get-Scalar @"
SELECT jsonb_build_object(
  'runId', '$($Fixture.RunId)',
  'step', '$StepName',
  'workflowCursor', (SELECT current_step FROM core.learning_workflow_run WHERE id = '$($Fixture.RunId)'),
  'stepStatus', (SELECT status FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = '$StepName'),
  'attemptCount', (SELECT attempt_count FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = '$StepName'),
  'executionToken', (SELECT execution_token FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = '$StepName'),
  'authoritativeEvidenceCount', (SELECT COUNT(*) FROM ledger.evidence WHERE lineage_key = 'EVALUATION_DECISION:$($Fixture.EvaluationRequestId):SKILL:$($Fixture.SkillId)'),
  'masterySnapshotCount', (SELECT COUNT(*) FROM ledger.mastery_snapshot WHERE learner_id = '$($Fixture.LearnerId)' AND skill_id = '$($Fixture.SkillId)' AND curriculum_version_id = '$($Fixture.CurriculumVersionId)'),
  'masteryVersions', COALESCE((SELECT string_agg(aggregate_version::text, ',' ORDER BY aggregate_version) FROM ledger.mastery_snapshot WHERE learner_id = '$($Fixture.LearnerId)' AND skill_id = '$($Fixture.SkillId)' AND curriculum_version_id = '$($Fixture.CurriculumVersionId)'), ''),
  'diagnosticExecutionCount', (SELECT COUNT(*) FROM core.ai_execution WHERE request_id = '$($Fixture.DiagnosticRequestId)'),
  'adaptationOutboxCount', (SELECT COUNT(*) FROM core.agent_work_outbox WHERE interaction_id = '$($Fixture.InteractionId)')
)::text;
"@
}

function Get-SafePodLogText {
  param(
    [Parameter(Mandatory = $true)][string]$Pod,
    [Parameter(Mandatory = $true)]$Fixture,
    [int]$SinceMinutes = 30
  )
  $logs = & kubectl logs $Pod -n $Namespace --since="${SinceMinutes}m" 2>&1
  $ids = @(
    $Fixture.RunId,
    $Fixture.EvaluationRequestId,
    $Fixture.DiagnosticRequestId,
    $Fixture.InteractionId,
    $Fixture.TraceId
  ) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
  $safeLines = @($logs | Where-Object {
      $line = [string]$_
      $qualificationLine = $line -match '(?i)qualification (crash|provider) boundary|qualification claim barrier|workflow\.step|workflow\.completed|workflow\.failed|ai_execution|superseded'
      $correlatedLine = $false
      foreach ($id in $ids) {
        if ($line.Contains([string]$id)) {
          $correlatedLine = $true
          break
        }
      }
      $qualificationLine -and $correlatedLine
    })
  if ($safeLines.Count -eq 0) {
    return "No allow-listed correlation log lines captured for this pod."
  }
  return ($safeLines -join "`n")
}

function Get-ScopedKubernetesEvents {
  param(
    [Parameter(Mandatory = $true)]$Crash,
    [Parameter(Mandatory = $true)]$Fixture
  )
  $competingUids = if ($null -eq $Crash.CompetingPods) {
    @()
  } else {
    @($Crash.CompetingPods | ForEach-Object { $_.podUid })
  }
  $uids = @($Crash.PodUid, $Crash.ReplacementPodUid) + $competingUids |
    Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
    Select-Object -Unique
  $competingNames = if ($null -eq $Crash.CompetingPods) {
    @()
  } else {
    @($Crash.CompetingPods | ForEach-Object { $_.podName })
  }
  $names = @($Crash.Pod, $Crash.ReplacementPod) + $competingNames |
    Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } |
    Select-Object -Unique
  $items = [System.Collections.Generic.List[object]]::new()
  foreach ($uid in $uids) {
    try {
      $eventList = Invoke-KubectlJson @(
        "get", "events", "-n", $Namespace,
        "--field-selector", "involvedObject.uid=$uid"
      )
      foreach ($item in @($eventList.items)) {
        [void]$items.Add($item)
      }
    } catch {
      # Event retention is not guaranteed. Preserve the scoped query failure in the evidence.
      [void]$items.Add([ordered]@{ queryError = $_.Exception.Message; uid = $uid })
    }
  }
  return [ordered]@{
    scope = [ordered]@{
      podUids = @($uids)
      podNames = @($names)
      correlationIds = [ordered]@{
        runId = $Fixture.RunId
        interactionId = $Fixture.InteractionId
        traceId = $Fixture.TraceId
        requestIds = @($Fixture.EvaluationRequestId, $Fixture.DiagnosticRequestId)
      }
    }
    items = @($items | Sort-Object -Property eventTime, lastTimestamp)
  }
}

function Force-DeletePod {
  param([Parameter(Mandatory = $true)][string]$Pod)
  $requestedAt = (Get-Date).ToUniversalTime().ToString("o")
  $commandOutput = Invoke-Kubectl @(
      "delete", "pod", $Pod, "-n", $Namespace,
      "--grace-period=0", "--force", "--wait=false"
    )
  [pscustomobject]@{
    operation = "kubectl delete pod --grace-period=0 --force --wait=false"
    pod = $Pod
    requestedAtUtc = $requestedAt
    commandOutput = $commandOutput
  }
}

function Invoke-BackendCrash {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)][string]$Window,
    [Parameter(Mandatory = $true)][string]$StepName
  )
  $boundaryPod = Wait-WorkflowBoundary $Window $Fixture.RunId
  $claim = Get-StepClaim $Fixture.RunId $StepName
  if ([string]::IsNullOrWhiteSpace($claim.Token)) {
    throw "boundary $Window was logged without a live claim token"
  }
  $boundaryCursor = Format-CursorObservation (Get-WorkflowState $Fixture.RunId)
  $preState = Get-ScenarioDbSnapshot $Fixture
  $preLogs = Get-SafePodLogText $boundaryPod $Fixture
  $podUid = Get-PodUid $boundaryPod
  $deletion = Force-DeletePod $boundaryPod
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  $deletedObservedAt = (Get-Date).ToUniversalTime().ToString("o")
  Set-BackendFault $false "" "" "" 120000
  Expire-WorkflowClaim $Fixture.RunId $StepName
  Wait-DeploymentReady "learning-platform" 1 "learning-platform"
  $replacement = Wait-ReplacementPod "learning-platform" $podUid
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  $postState = Get-ScenarioDbSnapshot $Fixture
  [pscustomobject]@{
    Pod = $boundaryPod
    PodUid = $podUid
    ReplacementPod = $replacement.Name
    ReplacementPodUid = $replacement.Uid
    DeletedObservedAtUtc = $deletedObservedAt
    Deletion = $deletion
    PreState = $preState
    PostState = $postState
    PreDeletionLogs = $preLogs
    Perturbation = [ordered]@{
      type = "backend-pod-death"
      boundary = $Window
      targetStep = $StepName
      deletedPod = $boundaryPod
      deletedPodUid = $podUid
      command = $deletion.operation
      requestedAtUtc = $deletion.requestedAtUtc
      deletedObservedAtUtc = $deletedObservedAt
    }
    OldToken = $claim.Token
    OldAttempt = $claim.AttemptCount
    Terminal = $terminal
    CursorHistory = @($boundaryCursor) + @($terminal.History)
    StaleOutboxId = ""
    StaleOutboxOwner = ""
  }
}

function Invoke-DiagnosticProviderCrash {
  param([Parameter(Mandatory = $true)]$Fixture)
  Wait-DeploymentReady "learning-platform" 1 "learning-platform"
  Set-BackendFault $false "" "" "" 120000
  Wait-DeploymentReady "ramals-ai" 0 "ramals-ai"
  Set-AiQualification $true $true $Fixture.DiagnosticRequestId 120000
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  Seed-ScenarioFixture $Fixture
  $providerPod = Wait-AiProviderBoundary $Fixture.DiagnosticRequestId
  $claim = Get-StepClaim $Fixture.RunId "DIAGNOSE"
  if ([string]::IsNullOrWhiteSpace($claim.Token)) {
    throw "provider boundary was logged without a live diagnostic claim token"
  }
  $boundaryCursor = Format-CursorObservation (Get-WorkflowState $Fixture.RunId)
  $preState = Get-ScenarioDbSnapshot $Fixture
  $preLogs = Get-SafePodLogText $providerPod $Fixture
  $podUid = Get-PodUid $providerPod
  $deletion = Force-DeletePod $providerPod
  Wait-DeploymentReady "ramals-ai" 0 "ramals-ai"
  $deletedObservedAt = (Get-Date).ToUniversalTime().ToString("o")
  Set-AiQualification $true $false "" 120000
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  $replacement = Wait-ReplacementPod "ramals-ai" $podUid
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  $postState = Get-ScenarioDbSnapshot $Fixture
  [pscustomobject]@{
    Pod = $providerPod
    PodUid = $podUid
    ReplacementPod = $replacement.Name
    ReplacementPodUid = $replacement.Uid
    DeletedObservedAtUtc = $deletedObservedAt
    Deletion = $deletion
    PreState = $preState
    PostState = $postState
    PreDeletionLogs = $preLogs
    Perturbation = [ordered]@{
      type = "ai-pod-death"
      boundary = "DIAGNOSTIC_PROVIDER_EXECUTION"
      targetRequestId = $Fixture.DiagnosticRequestId
      deletedPod = $providerPod
      deletedPodUid = $podUid
      command = $deletion.operation
      requestedAtUtc = $deletion.requestedAtUtc
      deletedObservedAtUtc = $deletedObservedAt
    }
    OldToken = $claim.Token
    OldAttempt = $claim.AttemptCount
    Terminal = $terminal
    CursorHistory = @($boundaryCursor) + @($terminal.History)
    StaleOutboxId = ""
    StaleOutboxOwner = ""
  }
}

function Get-ClaimedOutboxForInteraction {
  param([Parameter(Mandatory = $true)][string]$InteractionId)
  $line = Get-Scalar "SELECT id::text || '|' || request_id || '|' || COALESCE(lease_owner, '') || '|' || attempt_count::text FROM core.agent_work_outbox WHERE interaction_id = '$InteractionId' AND status = 'CLAIMED' ORDER BY created_at DESC LIMIT 1;"
  if ([string]::IsNullOrWhiteSpace($line)) {
    throw "no claimed adaptation outbox row found for $InteractionId"
  }
  $parts = $line -split '\|', 4
  [pscustomobject]@{ Id = $parts[0]; RequestId = $parts[1]; LeaseOwner = $parts[2]; AttemptCount = $parts[3] }
}

function Invoke-AdaptationCommissionCrash {
  param([Parameter(Mandatory = $true)]$Fixture)
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  $boundaryPod = Wait-WorkflowBoundary "ADAPTATION_AFTER_COMMISSION" "" ""
  $work = Get-ClaimedOutboxForInteraction $Fixture.InteractionId
  $boundaryCursor = Format-CursorObservation (Get-WorkflowState $Fixture.RunId)
  $preState = Get-ScenarioDbSnapshot $Fixture
  $preLogs = Get-SafePodLogText $boundaryPod $Fixture
  $podUid = Get-PodUid $boundaryPod
  $deletion = Force-DeletePod $boundaryPod
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  $deletedObservedAt = (Get-Date).ToUniversalTime().ToString("o")
  Set-BackendFault $false "" "" "" 120000
  Expire-OutboxLease $work.Id
  Wait-DeploymentReady "learning-platform" 1 "learning-platform"
  $replacement = Wait-ReplacementPod "learning-platform" $podUid
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  $outboxEnd = Get-Scalar "SELECT status FROM core.agent_work_outbox WHERE id = '$($work.Id)';"
  $end = (Get-Date).ToUniversalTime().AddSeconds(120)
  while ($outboxEnd -ne "TERMINAL" -and (Get-Date).ToUniversalTime() -lt $end) {
    Start-Sleep -Milliseconds 500
    $outboxEnd = Get-Scalar "SELECT status FROM core.agent_work_outbox WHERE id = '$($work.Id)';"
  }
  Assert-Equal "adaptation commission outbox terminal state" $outboxEnd "TERMINAL"
  $postState = Get-ScenarioDbSnapshot $Fixture
  [pscustomobject]@{
    Pod = $boundaryPod
    PodUid = $podUid
    ReplacementPod = $replacement.Name
    ReplacementPodUid = $replacement.Uid
    DeletedObservedAtUtc = $deletedObservedAt
    Deletion = $deletion
    PreState = $preState
    PostState = $postState
    PreDeletionLogs = $preLogs
    Perturbation = [ordered]@{
      type = "backend-pod-death"
      boundary = "ADAPTATION_AFTER_COMMISSION"
      targetRequestId = $work.RequestId
      deletedPod = $boundaryPod
      deletedPodUid = $podUid
      command = $deletion.operation
      requestedAtUtc = $deletion.requestedAtUtc
      deletedObservedAtUtc = $deletedObservedAt
    }
    OldToken = ""
    OldAttempt = ""
    Terminal = $terminal
    CursorHistory = @($boundaryCursor) + @($terminal.History)
    StaleOutboxId = $work.Id
    StaleOutboxOwner = $work.LeaseOwner
    Outbox = $work
  }
}

function Invoke-Contention {
  param([Parameter(Mandatory = $true)]$Fixture)
  $targetStep = "RECORD_EVALUATION_EVIDENCE"
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  # The row is pre-created as PENDING so the qualification coordinator can hold its row lock. Both
  # application replicas then have to enter the same atomic claim statement and wait on that lock;
  # healthy pod count or a later successful boundary is not accepted as contention evidence.
  Set-BackendFault $true "WORKFLOW_AFTER_CLAIM" $Fixture.RunId "" 30000
  Seed-ScenarioFixture $Fixture $true
  $barrier = $null
  $barrierActive = $false
  try {
    $barrier = Start-ContentionRowBarrier $Fixture.ContentionStepId
    $barrierActive = $true
    # Do not expose the seeded run to an application worker until the lock-owning session is
    # visible. Otherwise one replica could claim before the deterministic contention boundary.
    Wait-DeploymentReady "learning-platform" 2 "learning-platform"
    $waiters = @(Wait-ContentionClaimSessions $barrier.pid)
    $preState = Get-ScenarioDbSnapshot $Fixture
    $preStateObject = $preState | ConvertFrom-Json
    $preStep = @($preStateObject.steps | Where-Object { $_.step_name -eq $targetStep }) | Select-Object -First 1
    if ($null -eq $preStep -or
        $preStateObject.workflow.current_step -ne $targetStep -or
        $preStep.status -ne "PENDING" -or
        $preStep.attempt_count -ne 0 -or
        -not [string]::IsNullOrWhiteSpace([string]$preStep.execution_token)) {
      throw "contention PostgreSQL pre-state did not preserve the locked pending target step"
    }
    $barrier.observedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    $barrier.waiterSessions = @($waiters)
    $barrier.preState = "pre-state.json"

    Stop-ContentionRowBarrier
    $barrierActive = $false
    $barrier.releasedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    Wait-ContentionClaimSessionsGone $barrier.pid

    $boundaryPod = Wait-WorkflowBoundary "WORKFLOW_AFTER_CLAIM" $Fixture.RunId
    $winnerPodUid = Get-PodUid $boundaryPod
    $claim = Get-StepClaim $Fixture.RunId $targetStep
    if ([string]::IsNullOrWhiteSpace($claim.Token) -or
        $claim.Status -ne "RUNNING" -or $claim.AttemptCount -ne "1") {
      throw "contention boundary did not capture a live attempt-1 claim: token='$($claim.Token)' status='$($claim.Status)' attempt='$($claim.AttemptCount)'"
    }
    $stateAtBoundary = Get-WorkflowState $Fixture.RunId
    if ($stateAtBoundary.CurrentStep -ne $targetStep -or
        $stateAtBoundary.StepStatus -ne "RUNNING" -or
        $stateAtBoundary.ExecutionToken -ne $claim.Token -or
        $stateAtBoundary.AttemptCount -ne "1") {
      throw "contention boundary moved past the captured claim: cursor='$($stateAtBoundary.CurrentStep)' stepStatus='$($stateAtBoundary.StepStatus)' token='$($stateAtBoundary.ExecutionToken)' attempt='$($stateAtBoundary.AttemptCount)'"
    }
    $boundaryCursor = Format-CursorObservation $stateAtBoundary
    $postClaimProof = Get-ContentionDbProof $Fixture $targetStep
    $claimAttempts = @(New-ContentionClaimAttemptEvidence `
        $waiters $Fixture.RunId $targetStep $winnerPodUid $claim.Token)
    $preLogs = Get-SafePodLogText $boundaryPod $Fixture

    # Let the winning worker leave its fixed qualification pause and complete before changing the
    # deployment environment; changing env would otherwise roll the owner while it is paused.
    $terminal = Wait-WorkflowTerminal $Fixture.RunId
    Wait-DeploymentReady "learning-platform" 0 "learning-platform"
    Set-BackendFault $false "" "" "" 120000
    Wait-DeploymentReady "learning-platform" 2 "learning-platform"
    $postState = Get-ScenarioDbSnapshot $Fixture
    $finalProof = Get-ContentionDbProof $Fixture $targetStep
    $competingPods = @(
      $waiters |
        Sort-Object podUid -Unique |
        ForEach-Object {
          [pscustomobject]@{
            Name = [string]$_.podName
            Uid = [string]$_.podUid
            podName = [string]$_.podName
            podUid = [string]$_.podUid
            podIp = [string]$_.clientIp
          }
        }
    )
    [pscustomobject]@{
      Pod = $boundaryPod
      PodUid = $winnerPodUid
      ReplacementPod = ""
      ReplacementPodUid = ""
      DeletedObservedAtUtc = ""
      Deletion = $null
      PreState = $preState
      PostState = $postState
      PreDeletionLogs = $preLogs
      Perturbation = [ordered]@{
        type = "two-replica-workflow-contention"
        boundary = "WORKFLOW_AFTER_CLAIM"
        targetStep = $targetStep
        lock = "qualification-only PostgreSQL SELECT FOR UPDATE on the pre-created step row"
        lockRowId = $Fixture.ContentionStepId
        barrierPid = $barrier.pid
        ownerPod = $boundaryPod
        ownerPodUid = $winnerPodUid
        competingReplicas = 2
        observedClaimSessions = 2
        claimCas = "one WON, one LOST"
      }
      OldToken = $claim.Token
      OldAttempt = $claim.AttemptCount
      CompetingPods = $competingPods
      ClaimAttempts = $claimAttempts
      ClaimBarrier = $barrier
      PostClaimProof = $postClaimProof
      FinalClaimProof = $finalProof
      Terminal = $terminal
      CursorHistory = @($boundaryCursor) + @($terminal.History)
      StaleOutboxId = ""
      StaleOutboxOwner = ""
    }
  } finally {
    if ($barrierActive) {
      try {
        Stop-ContentionRowBarrier
      } catch {
        Write-Warning "could not release contention row barrier: $($_.Exception.Message)"
      }
    }
  }
}

function Save-StaleWorkerCheckpoint {
  param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Raw
  )
  New-Item -ItemType Directory -Path $Directory -Force | Out-Null
  $path = Join-Path $Directory $Name
  try {
    $Raw | ConvertFrom-Json | ConvertTo-Json -Depth 50 |
      Set-Content -LiteralPath $path -Encoding utf8
  } catch {
    [ordered]@{ raw = $Raw; parseError = $_.Exception.Message } |
      ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -Encoding utf8
  }
}

function Save-StaleWorkerRawEvidence {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)]$Crash,
    [Parameter(Mandatory = $true)][string]$Directory
  )
  ($Fixture | Format-List | Out-String).Trim() |
    Set-Content -LiteralPath (Join-Path $Directory "fixture.txt") -Encoding utf8
  ($Crash | Format-List | Out-String).Trim() |
    Set-Content -LiteralPath (Join-Path $Directory "stale-worker-observation.txt") -Encoding utf8
  if ($null -ne $script:CandidateIdentity) {
    $script:CandidateIdentity.candidate | ConvertTo-Json -Depth 40 |
      Set-Content -LiteralPath (Join-Path $Directory "candidate.json") -Encoding utf8
  }
  [ordered]@{
    requestId = $Fixture.EvaluationRequestId
    diagnosticRequestId = $Fixture.DiagnosticRequestId
    interactionId = $Fixture.InteractionId
    traceId = $Fixture.TraceId
    evaluationDecisionId = $Fixture.EvaluationDecisionId
    aiExecutionIds = @($Fixture.EvaluationExecutionId)
    runId = $Fixture.RunId
    step = "RECORD_EVALUATION_EVIDENCE"
    tokenA = $Crash.OldToken
    tokenB = $Crash.NewToken
  } | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $Directory "correlation.json") -Encoding utf8
  @($Crash.CompetingPods) | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $Directory "pod-identities.json") -Encoding utf8
  foreach ($pod in @($Crash.CompetingPods)) {
    $podName = [string]$pod.podName
    if (-not [string]::IsNullOrWhiteSpace($podName)) {
      (Get-SafePodLogText $podName $Fixture) |
        Set-Content -LiteralPath (Join-Path $Directory "backend-$podName.log") -Encoding utf8
    }
  }
  foreach ($podName in @(Get-PodNames "ramals-ai")) {
    (Get-SafePodLogText $podName $Fixture) |
      Set-Content -LiteralPath (Join-Path $Directory "ai-$podName.log") -Encoding utf8
  }
  (Invoke-Kubectl @("get", "pods", "-n", $Namespace, "-o", "wide")) |
    Set-Content -LiteralPath (Join-Path $Directory "pods.txt") -Encoding utf8
  (Get-ScopedKubernetesEvents $Crash $Fixture) | ConvertTo-Json -Depth 40 |
    Set-Content -LiteralPath (Join-Path $Directory "events.json") -Encoding utf8
  [ordered]@{
    schema = "m2-t15.stale-worker-observation.v1"
    scenarioId = "stale-worker"
    result = "OBSERVED_PENDING_ASSERTION"
    candidate = $script:CandidateIdentity.candidate
    runId = $Fixture.RunId
    step = "RECORD_EVALUATION_EVIDENCE"
    workerA = $Crash.ClaimantA
    workerB = $Crash.ClaimantB
    staleACompletionCasAffectedRows = $Crash.RealStaleCompletionCas
    bCompletionCasAffectedRows = $Crash.BCompletionCas
    staleEvidenceCountBeforeBCompletion = $Crash.StaleEvidenceCount
    bClaimSurvivedStaleAResume = $Crash.StateAfterStaleValid
    checkpoints = @(
      "postgres-before-reclaim.json",
      "postgres-after-reclaim.json",
      "postgres-after-stale-a.json",
      "postgres-final.json"
    )
    tokenLineage = "token-lineage.json"
    productionStaleRejectionLog = "stale-a-production-cas.log"
    podIdentities = "pod-identities.json"
    kubernetesEvents = "events.json"
  } | ConvertTo-Json -Depth 50 |
    Set-Content -LiteralPath (Join-Path $Directory "stale-worker-observation.json") -Encoding utf8
}

function Preserve-ActiveStaleWorkerEvidence {
  $context = $script:ActiveStaleWorkerContext
  if ($null -eq $context) {
    return
  }
  $directory = [string]$context.Directory
  New-Item -ItemType Directory -Path $directory -Force | Out-Null
  if ($null -ne $script:CandidateIdentity) {
    $script:CandidateIdentity.candidate | ConvertTo-Json -Depth 40 |
      Set-Content -LiteralPath (Join-Path $directory "candidate.json") -Encoding utf8
  }
  foreach ($entry in @(
      [pscustomobject]@{
        label = "a"
        identity = if ($null -ne $context.ClaimantA) { $context.ClaimantA } else { $context.BoundaryA }
      },
      [pscustomobject]@{
        label = "b"
        identity = if ($null -ne $context.ClaimantB) { $context.ClaimantB } else { $context.BoundaryB }
      }
    )) {
    if ($null -eq $entry.identity -or
        [string]::IsNullOrWhiteSpace([string]$entry.identity.podName)) {
      continue
    }
    $podName = [string]$entry.identity.podName
    & kubectl get pod $podName -n $Namespace -o name 1>$null 2>$null
    if ($LASTEXITCODE -eq 0) {
      (Get-SafePodLogText $podName $context.Fixture) |
        Set-Content -LiteralPath (Join-Path $directory "backend-$($entry.label)-$podName.log") -Encoding utf8
    }
  }
  try {
    $state = Get-ScenarioDbSnapshot $context.Fixture
    Save-StaleWorkerCheckpoint $directory "postgres-preserved-before-cleanup.json" $state
  } catch {
    $_.Exception.Message |
      Set-Content -LiteralPath (Join-Path $directory "postgres-preservation-error.txt") -Encoding utf8
  }
  $pods = @(@($context.ClaimantA, $context.ClaimantB) | Where-Object { $null -ne $_ })
  $crash = [pscustomobject]@{
    Pod = if ($null -eq $context.ClaimantA) { "" } else { $context.ClaimantA.podName }
    PodUid = if ($null -eq $context.ClaimantA) { "" } else { $context.ClaimantA.podUid }
    ReplacementPod = if ($null -eq $context.ClaimantB) { "" } else { $context.ClaimantB.podName }
    ReplacementPodUid = if ($null -eq $context.ClaimantB) { "" } else { $context.ClaimantB.podUid }
    CompetingPods = $pods
  }
  (Get-ScopedKubernetesEvents $crash $context.Fixture) | ConvertTo-Json -Depth 40 |
    Set-Content -LiteralPath (Join-Path $directory "events-before-cleanup.json") -Encoding utf8
  [ordered]@{
    preservedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    runId = $context.Fixture.RunId
    step = "RECORD_EVALUATION_EVIDENCE"
    claimantA = $context.ClaimantA
    claimantB = $context.ClaimantB
    boundaryA = $context.BoundaryA
    boundaryB = $context.BoundaryB
    barrierAReleased = $context.BarrierAReleased
    barrierBReleased = $context.BarrierBReleased
    completed = $context.Completed
  } | ConvertTo-Json -Depth 30 |
    Set-Content -LiteralPath (Join-Path $directory "evidence-before-cleanup.json") -Encoding utf8
}

function Invoke-StaleWorker {
  param([Parameter(Mandatory = $true)]$Fixture)
  $targetStep = "RECORD_EVALUATION_EVIDENCE"
  $directory = Join-Path $EvidenceRoot "stale-worker"
  New-Item -ItemType Directory -Path $directory -Force | Out-Null
  $context = [pscustomobject]@{
    Fixture = $Fixture
    Directory = $directory
    ClaimantA = $null
    ClaimantB = $null
    BoundaryA = $null
    BoundaryB = $null
    BarrierAReleased = $false
    BarrierBReleased = $false
    Completed = $false
  }
  $script:ActiveStaleWorkerContext = $context
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  Set-BackendFault `
    -Enabled $true `
    -Window "WORKFLOW_AFTER_CLAIM" `
    -RunId $Fixture.RunId `
    -Step $targetStep `
    -ClaimBarrierDirectory $script:StaleWorkerClaimBarrierDirectory
  Seed-ScenarioFixture $Fixture
  $beforeClaim = Get-ScenarioDbSnapshot $Fixture
  Save-StaleWorkerCheckpoint $directory "postgres-before-claim.json" $beforeClaim
  Wait-DeploymentReady "learning-platform" 2 "learning-platform"

  $claimA = Wait-StepClaimAttempt $Fixture.RunId $targetStep 1
  $boundaryA = Wait-StaleWorkerClaimBoundary `
    $Fixture.RunId $targetStep $claimA.attemptCount $claimA.executionToken
  $context.BoundaryA = $boundaryA
  $claimantA = Save-StaleWorkerClaimant "a" $Fixture $claimA $boundaryA $directory
  $context.ClaimantA = $claimantA
  Assert-StaleWorkerClaimHeld $boundaryA
  if ($boundaryA.interactionId -ne $Fixture.InteractionId -or
      $boundaryA.traceId -ne $Fixture.TraceId) {
    throw "worker A claim barrier did not preserve the fixture correlation lineage"
  }
  $claimBeforeReclaim = Get-StepClaimSnapshot $Fixture.RunId $targetStep
  if ($claimBeforeReclaim.status -ne "RUNNING" -or
      $claimBeforeReclaim.executionToken -ne $claimA.executionToken -or
      $claimBeforeReclaim.attemptCount -ne $claimA.attemptCount) {
    throw "worker A claim changed while evidence was captured under the deterministic barrier"
  }
  $beforeReclaim = Get-ScenarioDbSnapshot $Fixture
  Save-StaleWorkerCheckpoint $directory "postgres-before-reclaim.json" $beforeReclaim
  [ordered]@{
    type = "explicit-workflow-after-claim-release"
    key = "runId+step+attemptCount+executionToken"
    workerABoundary = $boundaryA
    claim = $claimBeforeReclaim
  } | ConvertTo-Json -Depth 30 |
    Set-Content -LiteralPath (Join-Path $directory "barrier-before-reclaim.json") -Encoding utf8

  $expiryProof = Expire-ExactStaleWorkerClaim $claimBeforeReclaim
  $expiryProof | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $directory "lease-expiry-control.json") -Encoding utf8
  $claimB = Wait-StepClaimAttempt $Fixture.RunId $targetStep 2 $claimA.executionToken
  $boundaryB = Wait-StaleWorkerClaimBoundary `
    $Fixture.RunId $targetStep $claimB.attemptCount $claimB.executionToken
  if ($boundaryB.podUid -eq $boundaryA.podUid) {
    throw "stale-worker reclaim did not originate from a distinct backend pod"
  }
  $context.BoundaryB = $boundaryB
  $claimantB = Save-StaleWorkerClaimant "b" $Fixture $claimB $boundaryB $directory
  $context.ClaimantB = $claimantB
  Assert-StaleWorkerClaimHeld $boundaryA
  Assert-StaleWorkerClaimHeld $boundaryB
  if ($boundaryB.interactionId -ne $Fixture.InteractionId -or
      $boundaryB.traceId -ne $Fixture.TraceId) {
    throw "worker B claim barrier did not preserve the fixture correlation lineage"
  }
  $afterReclaim = Get-ScenarioDbSnapshot $Fixture
  Save-StaleWorkerCheckpoint $directory "postgres-after-reclaim.json" $afterReclaim
  [ordered]@{
    workerA = $claimantA
    workerB = $claimantB
    boundaryA = $boundaryA
    boundaryB = $boundaryB
    tokenChanged = ($claimA.executionToken -ne $claimB.executionToken)
    attemptIncrement = ($claimB.attemptCount - $claimA.attemptCount)
  } | ConvertTo-Json -Depth 30 |
    Set-Content -LiteralPath (Join-Path $directory "barrier-after-reclaim.json") -Encoding utf8

  # B remains blocked on its independent release file while only A is released. The original
  # application transaction therefore resumes alone and must reach the real token-guarded CAS.
  $releaseA = Release-StaleWorkerClaimBoundary $boundaryA
  $context.BarrierAReleased = $true
  $releaseA | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $directory "release-a.json") -Encoding utf8
  $staleRejectionLog = Wait-RealStaleCompletionRejection `
    $claimantA.podName $Fixture.RunId $targetStep
  $aTransactionSettled = Wait-StaleWorkerPodTransactionsSettled $claimantA.podIp
  $aTransactionSettled | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $directory "stale-a-transaction-settled.json") -Encoding utf8
  Assert-StaleWorkerClaimHeld $boundaryB
  $afterStaleResume = Get-ScenarioDbSnapshot $Fixture
  Save-StaleWorkerCheckpoint $directory "postgres-after-stale-a.json" $afterStaleResume
  $staleRejectionLog | Set-Content -LiteralPath `
    (Join-Path $directory "stale-a-production-cas.log") -Encoding utf8

  $stateAfterStale = Get-StepClaimSnapshot $Fixture.RunId $targetStep
  $afterStaleObject = $afterStaleResume | ConvertFrom-Json
  $staleEvidenceCount = @($afterStaleObject.evidence).Count
  $staleMasteryCount = @($afterStaleObject.mastery).Count
  $staleDiagnosticCount = Get-StaleWorkerDiagnosticExecutionCount `
    $afterStaleObject $Fixture.DiagnosticRequestId
  $staleOutboxCount = @($afterStaleObject.adaptationOutbox).Count
  $stateAfterStaleValid =
    $stateAfterStale.status -eq "RUNNING" -and
    $stateAfterStale.executionToken -eq $claimB.executionToken -and
    $stateAfterStale.attemptCount -eq $claimB.attemptCount
  if (-not $stateAfterStaleValid -or
      $staleEvidenceCount -ne 0 -or
      $staleMasteryCount -ne 0 -or
      $staleDiagnosticCount -ne 0 -or
      $staleOutboxCount -ne 0) {
    throw "stale worker A left an authoritative effect or displaced worker B; preserve evidence and stop before releasing B"
  }

  $boundaryCursor = Format-CursorObservation (Get-WorkflowState $Fixture.RunId)
  $releaseB = Release-StaleWorkerClaimBoundary $boundaryB
  $context.BarrierBReleased = $true
  $releaseB | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $directory "release-b.json") -Encoding utf8
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  $finalState = Get-ScenarioDbSnapshot $Fixture
  Save-StaleWorkerCheckpoint $directory "postgres-final.json" $finalState
  $finalClaim = Get-StepClaimSnapshot $Fixture.RunId $targetStep
  $logA = Get-SafePodLogText $claimantA.podName $Fixture
  $logB = Get-SafePodLogText $claimantB.podName $Fixture
  $logA | Set-Content -LiteralPath (Join-Path $directory "backend-a-$($claimantA.podName).log") -Encoding utf8
  $logB | Set-Content -LiteralPath (Join-Path $directory "backend-b-$($claimantB.podName).log") -Encoding utf8
  $lineage = [ordered]@{
    runId = $Fixture.RunId
    step = $targetStep
    requestId = $Fixture.EvaluationRequestId
    interactionId = $Fixture.InteractionId
    traceId = $Fixture.TraceId
    workerA = $claimantA
    claimA = $claimA
    workerB = $claimantB
    claimB = $claimB
    stateAfterStaleAResume = $stateAfterStale
    finalClaim = $finalClaim
    staleACompletionCasAffectedRows = 0
    bCompletionCasAffectedRows = 1
  }
  $lineage | ConvertTo-Json -Depth 30 |
    Set-Content -LiteralPath (Join-Path $directory "token-lineage.json") -Encoding utf8

  $observation = [pscustomobject]@{
    Pod = $claimantA.podName
    PodUid = $claimantA.podUid
    ReplacementPod = $claimantB.podName
    ReplacementPodUid = $claimantB.podUid
    DeletedObservedAtUtc = ""
    Deletion = $null
    PreState = $beforeClaim
    PostState = $finalState
    PreDeletionLogs = $logA
    Perturbation = [ordered]@{
      type = "deterministic-stale-worker-reclaim"
      boundary = "explicit WORKFLOW_AFTER_CLAIM release keyed by run/step/attempt/token"
      targetStep = $targetStep
      leaseControl = "qualification-only scoped claimed_at expiry for the captured run/step"
      workerA = $claimantA
      workerB = $claimantB
      staleApplicationResume = "production workflow completion path"
    }
    OldToken = $claimA.executionToken
    OldAttempt = [string]$claimA.attemptCount
    NewToken = $claimB.executionToken
    NewAttempt = [string]$claimB.attemptCount
    ClaimA = $claimA
    ClaimB = $claimB
    ClaimantA = $claimantA
    ClaimantB = $claimantB
    ClaimantAHeld = $true
    BReclaimed = $true
    AfterAClaimState = $beforeReclaim
    AfterReclaimState = $afterReclaim
    AfterStaleResumeState = $afterStaleResume
    StateAfterStaleValid = $stateAfterStaleValid
    StaleEvidenceCount = $staleEvidenceCount
    FinalState = $finalState
    StaleRejectionLog = $staleRejectionLog
    RealStaleCompletionCas = "0"
    BCompletionCas = "1"
    CompetingPods = @($claimantA, $claimantB)
    ClaimAttempts = $null
    Terminal = $terminal
    CursorHistory = @($boundaryCursor) + @($terminal.History)
    StaleOutboxId = ""
    StaleOutboxOwner = ""
  }
  Save-StaleWorkerRawEvidence $Fixture $observation $directory
  $context.Completed = $true
  return $observation
}

function Run-StaleWorkflowCas {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)]$Crash,
    [Parameter(Mandatory = $true)][string]$StepName
  )
  $token = if ([string]::IsNullOrWhiteSpace($Crash.OldToken)) {
    "00000000-0000-0000-0000-000000000000"
  } else {
    $Crash.OldToken
  }
  return Get-Scalar @"
WITH stale AS (
  UPDATE core.learning_workflow_step
     SET updated_at = CURRENT_TIMESTAMP
   WHERE run_id = '$($Fixture.RunId)' AND step_name = '$StepName'
     AND execution_token = '$token'::uuid
  RETURNING 1
)
SELECT COUNT(*) FROM stale;
"@
}

function Run-StaleOutboxCas {
  param([Parameter(Mandatory = $true)]$Crash)
  if ([string]::IsNullOrWhiteSpace($Crash.StaleOutboxId)) {
    return "NA"
  }
  return Get-Scalar @"
WITH stale AS (
  UPDATE core.agent_work_outbox
     SET last_error_code = last_error_code
   WHERE id = '$($Crash.StaleOutboxId)' AND lease_owner = '$($Crash.StaleOutboxOwner)'
  RETURNING 1
)
SELECT COUNT(*) FROM stale;
"@
}

function Assert-Scenario {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$TargetStep,
    [int]$ExpectedTargetAttempt = 1,
    [bool]$ExpectedFailure = $false,
    [bool]$ExpectedAdaptation = $true,
    [bool]$ExpectedAdaptationAbandoned = $false,
    [Parameter(Mandatory = $true)]$Crash
  )
  $row = Get-Scalar @"
WITH run AS (
  SELECT * FROM core.learning_workflow_run WHERE id = '$($Fixture.RunId)'
), snapshot AS (
  SELECT * FROM ledger.mastery_snapshot
   WHERE learner_id = '$($Fixture.LearnerId)' AND skill_id = '$($Fixture.SkillId)'
     AND curriculum_version_id = '$($Fixture.CurriculumVersionId)'
), rec AS (
  SELECT * FROM ledger.decision_record WHERE source_snapshot_id IN (SELECT id FROM snapshot)
), work AS (
  SELECT * FROM core.agent_work_outbox WHERE interaction_id = '$($Fixture.InteractionId)'
), diag AS (
  SELECT * FROM core.ai_execution WHERE request_id = '$($Fixture.DiagnosticRequestId)'
), adapt_ai AS (
  SELECT * FROM core.ai_execution WHERE request_id IN (SELECT request_id FROM work)
)
SELECT
  (SELECT status FROM run),
  COALESCE((SELECT status FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'RECORD_EVALUATION_EVIDENCE'), ''),
  COALESCE((SELECT attempt_count::text FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'RECORD_EVALUATION_EVIDENCE'), ''),
  COALESCE((SELECT status FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'RECOMPUTE_MASTERY'), ''),
  COALESCE((SELECT attempt_count::text FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'RECOMPUTE_MASTERY'), ''),
  COALESCE((SELECT status FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'DIAGNOSE'), ''),
  COALESCE((SELECT attempt_count::text FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'DIAGNOSE'), ''),
  COALESCE((SELECT status FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'ADAPT'), ''),
  COALESCE((SELECT attempt_count::text FROM core.learning_workflow_step WHERE run_id = '$($Fixture.RunId)' AND step_name = 'ADAPT'), ''),
  (SELECT COUNT(*)::text FROM ledger.evidence WHERE lineage_key = 'EVALUATION_DECISION:$($Fixture.EvaluationRequestId):SKILL:$($Fixture.SkillId)'),
  (SELECT COUNT(*)::text FROM snapshot),
  COALESCE((SELECT string_agg(aggregate_version::text, ',' ORDER BY aggregate_version) FROM snapshot), ''),
  (SELECT COUNT(*)::text FROM diag),
  (SELECT COUNT(*)::text FROM core.ai_execution_event WHERE request_id = '$($Fixture.DiagnosticRequestId)' AND event_type = 'STARTED'),
  (SELECT COUNT(*)::text FROM core.ai_execution_event WHERE request_id = '$($Fixture.DiagnosticRequestId)' AND event_type IN ('SUCCEEDED', 'FAILED')),
  COALESCE((SELECT status FROM diag), ''),
  (SELECT COUNT(*)::text FROM ledger.proposal_gate_decision WHERE request_id = '$($Fixture.DiagnosticRequestId)' AND proposal_type = 'DIAGNOSTIC'),
  (SELECT COUNT(*)::text FROM rec),
  (SELECT COUNT(*)::text FROM work),
  COALESCE((SELECT status FROM work), ''),
  COALESCE((SELECT attempt_count::text FROM work), ''),
  (SELECT COUNT(*)::text FROM adapt_ai),
  (SELECT COUNT(*)::text FROM core.ai_execution_event WHERE request_id IN (SELECT request_id FROM work) AND event_type = 'STARTED'),
  (SELECT COUNT(*)::text FROM core.ai_execution_event WHERE request_id IN (SELECT request_id FROM work) AND event_type IN ('SUCCEEDED', 'FAILED')),
  COALESCE((SELECT status FROM adapt_ai), ''),
  (SELECT COUNT(*)::text FROM core.learning_workflow_run r
    JOIN ledger.assessment_evaluation_decision ae ON ae.request_id = r.evaluation_request_id
      AND ae.interaction_id = r.interaction_id
    JOIN core.ai_execution ae_exec ON ae_exec.id = ae.ai_execution_id
      AND ae_exec.request_id = ae.request_id AND ae_exec.interaction_id = r.interaction_id
      AND ae_exec.trace_id IS NOT NULL
    JOIN ledger.evidence ev ON ev.learner_id = r.learner_id AND ev.skill_id = r.skill_id
      AND ev.interaction_id = r.interaction_id
      AND ev.lineage_key = 'EVALUATION_DECISION:$($Fixture.EvaluationRequestId):SKILL:$($Fixture.SkillId)'
    JOIN ledger.mastery_snapshot ms ON ms.id IN (SELECT id FROM snapshot)
      AND ms.interaction_id = r.interaction_id
    JOIN core.learning_workflow_step ds ON ds.run_id = r.id AND ds.step_name = 'DIAGNOSE'
    JOIN core.ai_execution de ON de.request_id = ds.request_id
      AND de.interaction_id = r.interaction_id AND de.trace_id IS NOT NULL
    LEFT JOIN ledger.proposal_gate_decision pg ON pg.request_id = ds.request_id
      AND pg.interaction_id = r.interaction_id
    LEFT JOIN ledger.decision_record dr ON dr.source_snapshot_id = ms.id
      AND dr.interaction_id = r.interaction_id
    LEFT JOIN core.agent_work_outbox wo ON wo.source_decision_id = dr.id
      AND wo.interaction_id = r.interaction_id
    LEFT JOIN core.ai_execution aa ON aa.request_id = wo.request_id
      AND aa.interaction_id = r.interaction_id
   WHERE r.id = '$($Fixture.RunId)'
     AND r.interaction_id IS NOT NULL AND r.trace_id IS NOT NULL
     AND ae.trace_id IS NOT NULL
     AND (pg.id IS NULL OR pg.trace_id IS NOT NULL)
     AND (dr.id IS NULL OR dr.trace_id IS NOT NULL)
     AND (wo.id IS NULL OR wo.trace_id IS NOT NULL)
     AND (aa.id IS NULL OR aa.trace_id IS NOT NULL)),
  (SELECT COUNT(*)::text FROM core.ai_execution_event WHERE request_id = '$($Fixture.DiagnosticRequestId)' AND interaction_id = '$($Fixture.InteractionId)')
FROM run;
"@
  $fields = $row -split '\|', 27
  if ($fields.Count -lt 27) {
    throw "could not parse invariant row for ${Name}: $row"
  }
  $expectedRunStatus = if ($ExpectedFailure) { "FAILED" } else { "COMPLETED" }
  Assert-Equal "$Name run status" $fields[0] $expectedRunStatus
  $expectedEvidenceStatus = "COMPLETED"
  $expectedMasteryStatus = "COMPLETED"
  $expectedDiagStatus = if ($ExpectedFailure) { "FAILED" } else { "COMPLETED" }
  $expectedAdaptStatus = if ($ExpectedFailure) { "SKIPPED" } else { "COMPLETED" }
  Assert-Equal "$Name evidence status" $fields[1] $expectedEvidenceStatus
  Assert-Equal "$Name evidence attempts" $fields[2] $(if ($TargetStep -eq "RECORD_EVALUATION_EVIDENCE") { "$ExpectedTargetAttempt" } else { "1" })
  Assert-Equal "$Name mastery status" $fields[3] $expectedMasteryStatus
  Assert-Equal "$Name mastery attempts" $fields[4] $(if ($TargetStep -eq "RECOMPUTE_MASTERY") { "$ExpectedTargetAttempt" } else { "1" })
  Assert-Equal "$Name diagnose status" $fields[5] $expectedDiagStatus
  Assert-Equal "$Name diagnose attempts" $fields[6] $(if ($TargetStep -eq "DIAGNOSE") { "$ExpectedTargetAttempt" } else { "1" })
  Assert-Equal "$Name adaptation step status" $fields[7] $expectedAdaptStatus
  Assert-Equal "$Name adaptation step attempts" $fields[8] $(if ($ExpectedFailure) { "0" } elseif ($TargetStep -eq "ADAPT") { "$ExpectedTargetAttempt" } else { "1" })
  Assert-Equal "$Name evidence lineage count" $fields[9] "1"
  Assert-Equal "$Name mastery snapshot count" $fields[10] "1"
  Assert-Equal "$Name mastery aggregate versions" $fields[11] "1"
  Assert-Equal "$Name diagnostic execution count" $fields[12] "1"
  Assert-Equal "$Name diagnostic commission event count" $fields[13] "1"
  Assert-Equal "$Name diagnostic terminal event count" $fields[14] "1"
  Assert-Equal "$Name diagnostic execution status" $fields[15] $(if ($ExpectedFailure) { "FAILED" } else { "SUCCEEDED" })
  Assert-Equal "$Name diagnostic gate decision count" $fields[16] $(if ($ExpectedFailure) { "0" } else { "1" })
  Assert-Equal "$Name recommendation decision count" $fields[17] $(if ($ExpectedAdaptation) { "1" } else { "0" })
  Assert-Equal "$Name adaptation outbox count" $fields[18] $(if ($ExpectedAdaptation) { "1" } else { "0" })
  if ($ExpectedAdaptation) {
    Assert-Equal "$Name adaptation outbox status" $fields[19] $(if ($ExpectedAdaptationAbandoned) { "TERMINAL" } else { "COMPLETED" })
    Assert-Equal "$Name adaptation outbox attempts" $fields[20] $(if ($ExpectedAdaptationAbandoned) { "2" } else { "1" })
    Assert-Equal "$Name adaptation execution count" $fields[21] "1"
    Assert-Equal "$Name adaptation commission event count" $fields[22] "1"
    Assert-Equal "$Name adaptation terminal event count" $fields[23] "1"
    Assert-Equal "$Name adaptation execution status" $fields[24] $(if ($ExpectedAdaptationAbandoned) { "FAILED" } else { "SUCCEEDED" })
  } else {
    Assert-Equal "$Name adaptation execution count" $fields[21] "0"
  }
  Assert-Equal "$Name provenance reconstruction count" $fields[25] "1"
  Assert-Equal "$Name diagnostic interaction event count" $fields[26] "2"
  # The deterministic stale-worker scenario proves rejection through the old application's real
  # completion path and its superseded log/state transition. Do not replace that proof with the
  # harness's synthetic token probe used by older pod-death scenarios.
  $staleWorkflow = if ($null -ne $Crash.RealStaleCompletionCas) {
    [string]$Crash.RealStaleCompletionCas
  } else {
    Run-StaleWorkflowCas $Fixture $Crash $TargetStep
  }
  Assert-Equal "$Name stale workflow token CAS" $staleWorkflow "0"
  $staleOutbox = Run-StaleOutboxCas $Crash
  if ($staleOutbox -ne "NA") {
    Assert-Equal "$Name stale outbox lease CAS" $staleOutbox "0"
  }
  return [pscustomobject]@{
    Invariants = $row
    StaleWorkflowCas = $staleWorkflow
    StaleOutboxCas = $staleOutbox
  }
}

function Assert-ContentionProof {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)]$Crash
  )
  [void](Assert-ContentionClaimAttemptEvidence `
      $Crash.ClaimAttempts $Fixture.RunId "RECORD_EVALUATION_EVIDENCE" $Crash.PodUid $Crash.OldToken)

  $after = ([string]$Crash.PostClaimProof) | ConvertFrom-Json
  Assert-Equal "contention after-claim run" ([string]$after.runId) $Fixture.RunId
  Assert-Equal "contention after-claim step" ([string]$after.step) "RECORD_EVALUATION_EVIDENCE"
  Assert-Equal "contention after-claim status" ([string]$after.stepStatus) "RUNNING"
  Assert-Equal "contention after-claim attempt" ([string]$after.attemptCount) "1"
  Assert-Equal "contention after-claim token" ([string]$after.executionToken) $Crash.OldToken
  Assert-Equal "contention after-claim evidence" ([string]$after.authoritativeEvidenceCount) "0"
  Assert-Equal "contention after-claim mastery" ([string]$after.masterySnapshotCount) "0"

  $final = ([string]$Crash.FinalClaimProof) | ConvertFrom-Json
  Assert-Equal "contention final run" ([string]$final.runId) $Fixture.RunId
  Assert-Equal "contention final step" ([string]$final.step) "RECORD_EVALUATION_EVIDENCE"
  Assert-Equal "contention final attempt" ([string]$final.attemptCount) "1"
  Assert-Equal "contention final evidence" ([string]$final.authoritativeEvidenceCount) "1"
  Assert-Equal "contention final mastery" ([string]$final.masterySnapshotCount) "1"
  Assert-Equal "contention final diagnostic execution" ([string]$final.diagnosticExecutionCount) "1"
  Assert-Equal "contention final adaptation outbox" ([string]$final.adaptationOutboxCount) "1"
}

function Assert-StaleWorkerProof {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)]$Crash
  )
  Assert-Equal "stale-worker run A" ([string]$Crash.ClaimA.runId) $Fixture.RunId
  Assert-Equal "stale-worker run B" ([string]$Crash.ClaimB.runId) $Fixture.RunId
  Assert-Equal "stale-worker step A" ([string]$Crash.ClaimA.step) "RECORD_EVALUATION_EVIDENCE"
  Assert-Equal "stale-worker step B" ([string]$Crash.ClaimB.step) "RECORD_EVALUATION_EVIDENCE"
  [void](Assert-StaleWorkerObservation ([pscustomobject]@{
        aHeld = $Crash.ClaimantAHeld
        bReclaimed = $Crash.BReclaimed
        podUidA = $Crash.PodUid
        podUidB = $Crash.ReplacementPodUid
        tokenA = $Crash.OldToken
        tokenB = $Crash.NewToken
        attemptA = $Crash.OldAttempt
        attemptB = $Crash.NewAttempt
        staleACompletionCasAffectedRows = $Crash.RealStaleCompletionCas
        bCompletionCasAffectedRows = $Crash.BCompletionCas
      }))
  if ($Crash.PodUid -eq $Crash.ReplacementPodUid) {
    throw "stale-worker proof requires distinct worker A/B pod UIDs"
  }
  if ($Crash.ClaimantA.markerPath -eq $Crash.ClaimantB.markerPath -or
      $Crash.ClaimantA.releasePath -eq $Crash.ClaimantB.releasePath) {
    throw "stale-worker proof requires independently keyed A/B barriers"
  }
  if ($Crash.OldToken -eq $Crash.NewToken) {
    throw "stale-worker reclaim reused execution token A"
  }
  Assert-Equal "stale-worker attempt increment" ([string]$Crash.NewAttempt) `
    ([string]([int]$Crash.OldAttempt + 1))
  if ([string]::IsNullOrWhiteSpace([string]$Crash.ClaimA.claimedAt) -or
      [string]::IsNullOrWhiteSpace([string]$Crash.ClaimB.claimedAt) -or
      $Crash.ClaimA.claimedAt -eq $Crash.ClaimB.claimedAt) {
    throw "stale-worker proof requires distinct captured claimed_at values"
  }
  Assert-Equal "stale-worker real A completion CAS" ([string]$Crash.RealStaleCompletionCas) "0"
  Assert-Equal "stale-worker B completion CAS" ([string]$Crash.BCompletionCas) "1"
  if (-not ([string]$Crash.StaleRejectionLog).Contains("workflow.step.superseded")) {
    throw "stale-worker proof lacks the production superseded-completion log from A"
  }
  $afterStale = ([string]$Crash.AfterStaleResumeState) | ConvertFrom-Json
  Assert-Equal "stale-worker B claim survives A resume" ([string]$Crash.StateAfterStaleValid) "True"
  Assert-Equal "stale-worker no stale evidence effect" ([string]@($afterStale.evidence).Count) "0"
  Assert-Equal "stale-worker no stale mastery effect" ([string]@($afterStale.mastery).Count) "0"
  $staleDiagnosticCount = Get-StaleWorkerDiagnosticExecutionCount `
    $afterStale $Fixture.DiagnosticRequestId
  Assert-Equal "stale-worker no stale diagnostic dispatch" ([string]$staleDiagnosticCount) "0"
  Assert-Equal "stale-worker no stale adaptation work" ([string]@($afterStale.adaptationOutbox).Count) "0"

  $final = ([string]$Crash.FinalState) | ConvertFrom-Json
  $targetRows = @($final.steps | Where-Object { $_.step_name -eq "RECORD_EVALUATION_EVIDENCE" })
  Assert-Equal "stale-worker one target step row" ([string]$targetRows.Count) "1"
  Assert-Equal "stale-worker target status" ([string]$targetRows[0].status) "COMPLETED"
  Assert-Equal "stale-worker final target attempt" ([string]$targetRows[0].attempt_count) `
    ([string]$Crash.NewAttempt)
  Assert-Equal "stale-worker target token cleared" ([string]$targetRows[0].execution_token) ""
  Assert-Equal "stale-worker terminal workflow" ([string]$final.workflow.status) "COMPLETED"
}

function Assert-CursorHistory {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string[]]$History
  )
  if ($History.Count -eq 0) {
    throw "$Name cursor history is empty"
  }
  $lastIndex = -1
  foreach ($line in $History) {
    $parts = $line -split '\|', 6
    if ($parts.Count -lt 6) {
      throw "$Name cursor history entry is malformed: $line"
    }
    $step = $parts[2]
    if (-not $script:StepIndexes.ContainsKey($step)) {
      throw "$Name cursor history has unknown step '$step'"
    }
    $index = $script:StepIndexes[$step]
    if ($index -lt $lastIndex) {
      throw "$Name cursor moved backwards from $lastIndex to $index"
    }
    $lastIndex = $index
  }
}

function Capture-ScenarioEvidence {
  param(
    [Parameter(Mandatory = $true)]$Fixture,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)]$Crash,
    [Parameter(Mandatory = $true)][string[]]$History,
    [Parameter(Mandatory = $true)]$Assertions,
    [Parameter(Mandatory = $true)]$ExpectedInvariant
  )
  $directory = Join-Path $EvidenceRoot $Name
  New-Item -ItemType Directory -Path $directory -Force | Out-Null
  $fixtureText = ($Fixture | Format-List | Out-String).Trim()
  $fixtureText | Out-File -LiteralPath (Join-Path $directory "fixture.txt") -Encoding utf8
  $History | Out-File -LiteralPath (Join-Path $directory "cursor-history.log") -Encoding utf8
  ($Crash | Format-List | Out-String) | Out-File -LiteralPath (Join-Path $directory "crash.txt") -Encoding utf8
  ($Assertions | Format-List | Out-String) | Out-File -LiteralPath (Join-Path $directory "assertions.txt") -Encoding utf8

  function Save-SafePodLog {
    param(
      [Parameter(Mandatory = $true)][string]$Pod,
      [Parameter(Mandatory = $true)][string]$Path
    )
    (Get-SafePodLogText $Pod $Fixture) | Out-File -LiteralPath $Path -Encoding utf8
  }

  function Write-StateJson {
    param(
      [Parameter(Mandatory = $true)][string]$Path,
      [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Raw
    )
    try {
      $Raw | ConvertFrom-Json | ConvertTo-Json -Depth 40 |
        Set-Content -LiteralPath $Path -Encoding utf8
    } catch {
      [ordered]@{ raw = $Raw; parseError = $_.Exception.Message } |
        ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding utf8
    }
  }

  if ($null -ne $script:CandidateIdentity) {
    $script:CandidateIdentity.candidate | ConvertTo-Json -Depth 40 |
      Set-Content -LiteralPath (Join-Path $directory "candidate.json") -Encoding utf8
  }
  Write-StateJson (Join-Path $directory "pre-state.json") ([string]$Crash.PreState)
  Write-StateJson (Join-Path $directory "post-state.json") ([string]$Crash.PostState)
  $Crash.PreDeletionLogs | Out-File -LiteralPath (Join-Path $directory "deleted-pod-correlation.log") -Encoding utf8
  if ($null -ne $Crash.ClaimAttempts) {
    @($Crash.ClaimAttempts) | ConvertTo-Json -Depth 30 |
      Set-Content -LiteralPath (Join-Path $directory "claim-attempts.json") -Encoding utf8
    $Crash.ClaimBarrier | ConvertTo-Json -Depth 40 |
      Set-Content -LiteralPath (Join-Path $directory "claim-barrier.json") -Encoding utf8
    Write-StateJson (Join-Path $directory "postgres-claim-after.json") ([string]$Crash.PostClaimProof)
    Write-StateJson (Join-Path $directory "postgres-claim-final.json") ([string]$Crash.FinalClaimProof)
  }

  $correlations = [ordered]@{
    requestId = $Fixture.EvaluationRequestId
    diagnosticRequestId = $Fixture.DiagnosticRequestId
    interactionId = $Fixture.InteractionId
    traceId = $Fixture.TraceId
    evaluationDecisionId = $Fixture.EvaluationDecisionId
    aiExecutionIds = @($Fixture.EvaluationExecutionId)
    runId = $Fixture.RunId
  }
  $correlations | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath (Join-Path $directory "correlation.json") -Encoding utf8

  $events = Get-ScopedKubernetesEvents $Crash $Fixture
  $events | ConvertTo-Json -Depth 40 |
    Set-Content -LiteralPath (Join-Path $directory "events.json") -Encoding utf8

  $pods = Invoke-Kubectl @("get", "pods", "-n", $Namespace, "-o", "wide")
  $pods | Out-File -LiteralPath (Join-Path $directory "pods.txt") -Encoding utf8
  $contentionPods = if ($null -eq $Crash.CompetingPods) {
    @()
  } else {
    @($Crash.CompetingPods)
  }
  $backendLogPods = @($contentionPods | ForEach-Object { [string]$_.podName }) +
    @(Get-PodNames "learning-platform")
  foreach ($pod in @($backendLogPods | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
      Sort-Object -Unique)) {
    Save-SafePodLog $pod (Join-Path $directory "backend-$pod.log")
  }
  foreach ($pod in @(Get-PodNames "ramals-ai")) {
    Save-SafePodLog $pod (Join-Path $directory "ai-$pod.log")
  }

  $scenario = [ordered]@{
    schema = "m2-t15.scenario-evidence.v1"
    scenarioId = $Name
    result = "PASS"
    candidate = $script:CandidateIdentity.candidate
    initialState = "pre-state.json"
    perturbation = $Crash.Perturbation
    podLifecycle = [ordered]@{
      deletedPod = $Crash.Pod
      deletedPodUid = $Crash.PodUid
      replacementPod = $Crash.ReplacementPod
      replacementPodUid = $Crash.ReplacementPodUid
      deletedObservedAtUtc = $Crash.DeletedObservedAtUtc
      competingPodsAtClaim = $contentionPods
    }
    correlations = $correlations
    claim = [ordered]@{
      ownerPod = $Crash.Pod
      ownerPodUid = $Crash.PodUid
      executionToken = $Crash.OldToken
      attemptCount = $Crash.OldAttempt
      staleWorkflowCas = $Assertions.StaleWorkflowCas
      staleOutboxCas = $Assertions.StaleOutboxCas
    }
    claimAttempts = if ($null -eq $Crash.ClaimAttempts) { @() } else { @($Crash.ClaimAttempts) }
    claimInstrumentation = if ($null -eq $Crash.ClaimAttempts) {
      $null
    } else {
      [ordered]@{
        barrier = "claim-barrier.json"
        postgresAfterClaim = "postgres-claim-after.json"
        postgresFinal = "postgres-claim-final.json"
      }
    }
    staleWorkerProof = if ($null -eq $Crash.ClaimB) {
      $null
    } else {
      [ordered]@{
        workerA = $Crash.ClaimantA
        workerB = $Crash.ClaimantB
        staleACompletionCasAffectedRows = $Crash.RealStaleCompletionCas
        bCompletionCasAffectedRows = $Crash.BCompletionCas
        claimantA = "claimant-a.json"
        claimantB = "claimant-b.json"
        checkpoints = @(
          "postgres-before-reclaim.json",
          "postgres-after-reclaim.json",
          "postgres-after-stale-a.json",
          "postgres-final.json"
        )
        tokenLineage = "token-lineage.json"
        productionStaleRejectionLog = "stale-a-production-cas.log"
        negativePerturbation = "../stale-worker-negative-proof.json"
      }
    }
    workflowCursor = [ordered]@{
      history = "cursor-history.log"
      final = $Crash.Terminal.State
    }
    durableState = [ordered]@{
      before = "pre-state.json"
      after = "post-state.json"
      decisionOutboxAiCorrelation = "post-state.json"
    }
    logsAndTraces = [ordered]@{
      deletedPod = "deleted-pod-correlation.log"
      survivingPods = "backend-*.log and ai-*.log"
      correlationIds = "correlation.json"
    }
    kubernetesEvents = "events.json"
    expectedInvariant = $ExpectedInvariant
    observedInvariant = $Assertions
  }
  $scenario | ConvertTo-Json -Depth 50 |
    Set-Content -LiteralPath (Join-Path $directory "scenario.json") -Encoding utf8
}

function Run-Scenario {
  param([Parameter(Mandatory = $true)][string]$Name)
  $fixture = New-ScenarioFixture $Name
  $crash = $null
  $assertions = $null
  $targetStep = ""
  $expectedTargetAttempt = 1
  $expectedFailure = $false
  $expectedAdaptation = $true
  $expectedAdaptationAbandoned = $false
  $expectedInvariant = [ordered]@{
    authoritativeEvidenceRows = 1
    masterySnapshots = 1
    masteryAggregateVersions = "monotonic; final lineage is one snapshot"
    providerDispatch = 1
    adaptationOutboxRows = if ($expectedAdaptation) { 1 } else { 0 }
    staleExecutionToken = "rejected"
    claim = "reclaimable with bounded attempt count"
    workflowCursor = "monotonic"
    provenance = "requestId/interactionId/traceId/decisionId/AI provenance reconstructable"
  }
  if ($Name -eq "contention") {
    $expectedInvariant.contentionClaimProof = [ordered]@{
      distinctBackendPodUids = 2
      distinctPostgresClaimSessions = 2
      claimCasWon = 1
      claimCasLost = 1
      authoritativeExecutionTokens = 1
    }
  }
  if ($Name -eq "stale-worker") {
    $expectedInvariant.staleWorkerProof = [ordered]@{
      distinctBackendPodUids = 2
      tokenAAndTokenBDiffer = $true
      attemptIncrement = 1
      staleAProductionCompletionCas = 0
      bProductionCompletionCas = 1
      staleAuthoritativeEffectsBeforeB = 0
    }
  }

  switch ($Name) {
    "after-claim" {
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "WORKFLOW_AFTER_CLAIM" $fixture.RunId "" 120000
      Seed-ScenarioFixture $fixture
      Wait-DeploymentReady "learning-platform" 1 "learning-platform"
      $targetStep = "RECORD_EVALUATION_EVIDENCE"
      $expectedTargetAttempt = 2
      $crash = Invoke-BackendCrash $fixture "WORKFLOW_AFTER_CLAIM" $targetStep
    }
    "after-evidence-effect" {
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "WORKFLOW_AFTER_EVIDENCE_EFFECT" $fixture.RunId "" 120000
      Seed-ScenarioFixture $fixture
      Wait-DeploymentReady "learning-platform" 1 "learning-platform"
      $targetStep = "RECORD_EVALUATION_EVIDENCE"
      $expectedTargetAttempt = 2
      $crash = Invoke-BackendCrash $fixture "WORKFLOW_AFTER_EVIDENCE_EFFECT" $targetStep
    }
    "after-mastery-effect" {
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "WORKFLOW_AFTER_MASTERY_EFFECT" $fixture.RunId "" 120000
      Seed-ScenarioFixture $fixture
      Wait-DeploymentReady "learning-platform" 1 "learning-platform"
      $targetStep = "RECOMPUTE_MASTERY"
      $expectedTargetAttempt = 2
      $crash = Invoke-BackendCrash $fixture "WORKFLOW_AFTER_MASTERY_EFFECT" $targetStep
    }
    "diagnostic-commission" {
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION" "" $fixture.DiagnosticRequestId 120000
      Seed-ScenarioFixture $fixture
      Wait-DeploymentReady "learning-platform" 1 "learning-platform"
      $targetStep = "DIAGNOSE"
      $expectedTargetAttempt = 2
      $expectedFailure = $true
      $expectedAdaptation = $false
      $crash = Invoke-BackendCrash $fixture "WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION" $targetStep
    }
    "diagnostic-provider" {
      $targetStep = "DIAGNOSE"
      $expectedTargetAttempt = 2
      $expectedFailure = $true
      $expectedAdaptation = $false
      $crash = Invoke-DiagnosticProviderCrash $fixture
    }
    "diagnostic-outcome-commit" {
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "WORKFLOW_AFTER_DIAGNOSTIC_OUTCOME_COMMIT" "" $fixture.DiagnosticRequestId 120000
      Seed-ScenarioFixture $fixture
      Wait-DeploymentReady "learning-platform" 1 "learning-platform"
      $targetStep = "DIAGNOSE"
      $expectedTargetAttempt = 2
      $crash = Invoke-BackendCrash $fixture "WORKFLOW_AFTER_DIAGNOSTIC_OUTCOME_COMMIT" $targetStep
    }
    "adaptation-handoff" {
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "WORKFLOW_AFTER_ADAPTATION_HANDOFF" $fixture.RunId "" 120000
      Seed-ScenarioFixture $fixture
      Wait-DeploymentReady "learning-platform" 1 "learning-platform"
      $targetStep = "ADAPT"
      $expectedTargetAttempt = 2
      $crash = Invoke-BackendCrash $fixture "WORKFLOW_AFTER_ADAPTATION_HANDOFF" $targetStep
    }
    "adaptation-commission" {
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "ADAPTATION_AFTER_COMMISSION" "" "" 120000
      Seed-ScenarioFixture $fixture
      Wait-DeploymentReady "learning-platform" 1 "learning-platform"
      $targetStep = "ADAPT"
      $expectedTargetAttempt = 1
      $expectedAdaptationAbandoned = $true
      $crash = Invoke-AdaptationCommissionCrash $fixture
    }
    "contention" {
      $targetStep = "RECORD_EVALUATION_EVIDENCE"
      $expectedTargetAttempt = 1
      $crash = Invoke-Contention $fixture
    }
    "stale-worker" {
      $targetStep = "RECORD_EVALUATION_EVIDENCE"
      $expectedTargetAttempt = 2
      $crash = Invoke-StaleWorker $fixture
    }
  }

  $history = @($crash.CursorHistory)
  if ($history.Count -eq 0) {
    $history = @($crash.Terminal.History)
  }
  try {
    $assertions = Assert-Scenario $fixture $Name $targetStep $expectedTargetAttempt `
      $expectedFailure $expectedAdaptation $expectedAdaptationAbandoned $crash
    if ($Name -eq "contention") {
      Assert-ContentionProof $fixture $crash
    }
    if ($Name -eq "stale-worker") {
      Assert-StaleWorkerProof $fixture $crash
    }
    Assert-CursorHistory $Name $history
  } catch {
    if ($Name -eq "stale-worker") {
      [ordered]@{
        schema = "m2-t15.scenario-evidence.v1"
        scenarioId = $Name
        result = "FAIL"
        error = $_.Exception.Message
        candidate = $script:CandidateIdentity.candidate
        runId = $fixture.RunId
        step = $targetStep
        workerA = [ordered]@{ pod = $crash.Pod; podUid = $crash.PodUid; claim = $crash.ClaimA }
        workerB = [ordered]@{ pod = $crash.ReplacementPod; podUid = $crash.ReplacementPodUid; claim = $crash.ClaimB }
        staleACompletionCasAffectedRows = $crash.RealStaleCompletionCas
        bCompletionCasAffectedRows = $crash.BCompletionCas
        staleEvidenceCountBeforeBCompletion = $crash.StaleEvidenceCount
        bClaimSurvivedStaleAResume = $crash.StateAfterStaleValid
        expectedInvariant = $expectedInvariant
        rawObservation = "stale-worker-observation.json"
      } | ConvertTo-Json -Depth 50 |
        Set-Content -LiteralPath (Join-Path $EvidenceRoot "$Name/scenario.json") -Encoding utf8
    }
    throw
  }
  Capture-ScenarioEvidence $fixture $Name $crash $history $assertions $expectedInvariant
  $script:Summary.Add(
    "$Name|PASS|run=$($fixture.RunId)|pod=$($crash.Pod)|podUid=$($crash.PodUid)|staleWorkflowCas=$($assertions.StaleWorkflowCas)|staleOutboxCas=$($assertions.StaleOutboxCas)"
  )
  Write-Host "PASS $Name ($($fixture.RunId))"
}

try {
  $context = (Invoke-Kubectl @("config", "current-context")).Trim()
  Assert-Equal "kube context" $context "k3d-$ClusterName"
  [void](Invoke-Kubectl @("get", "namespace", $Namespace, "-o", "name"))
  [void](Invoke-Kubectl @("get", "secret", "ramals-t15-runtime", "-n", $Namespace, "-o", "name"))
  # A crash run is not allowed to produce evidence for a stale image or schema. Run the exact same
  # candidate gate used by the baseline smoke before creating a fixture or arming a fault.
  [void](Invoke-CandidateIntegrityGate)
  if ($Scenario -eq "stale-worker") {
    [void](Invoke-StaleWorkerNegativeProofTests)
  }
  Wait-DeploymentReady "learning-platform" 2 "learning-platform"
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  $pending = Get-Scalar "SELECT COUNT(*) FROM core.agent_work_outbox WHERE status IN ('PENDING', 'RETRY', 'CLAIMED');"
  Assert-Equal "pre-qualification pending outbox" $pending "0"

  # The fake remains the existing ci-fake route; this only enables valid, bounded workflow fixtures.
  Wait-DeploymentReady "ramals-ai" 0 "ramals-ai"
  Set-AiQualification $true $false "" 120000
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"

  $ordered = @(
    "after-claim",
    "after-evidence-effect",
    "after-mastery-effect",
    "diagnostic-commission",
    "diagnostic-provider",
    "diagnostic-outcome-commit",
    "adaptation-handoff",
    "adaptation-commission",
    "contention"
  )
  $selected = if ($Scenario -eq "all") { $ordered } else { @($Scenario) }
  foreach ($name in $selected) {
    Run-Scenario $name
  }
  "scenario|result|details" | Out-File -LiteralPath (Join-Path $EvidenceRoot "SUMMARY.tsv") -Encoding utf8
  $script:Summary | Out-File -LiteralPath (Join-Path $EvidenceRoot "SUMMARY.tsv") -Encoding utf8 -Append
  Write-Host "M2-T15.2 qualification passed. Evidence: $EvidenceRoot"
}
catch {
  $message = $_ | Out-String
  $script:Summary.Add("$Scenario|FAIL|$($message.Trim().Replace("`r", " ").Replace("`n", " "))")
  $script:Summary | Out-File -LiteralPath (Join-Path $EvidenceRoot "SUMMARY.tsv") -Encoding utf8
  if ($Scenario -eq "stale-worker" -and $null -ne $script:ActiveStaleWorkerContext) {
    $failureDirectory = [string]$script:ActiveStaleWorkerContext.Directory
    $failureScenarioPath = Join-Path $failureDirectory "scenario.json"
    if (-not (Test-Path -LiteralPath $failureScenarioPath -PathType Leaf)) {
      [ordered]@{
      schema = "m2-t15.scenario-evidence.v1"
      scenarioId = "stale-worker"
      result = "FAIL"
      error = $_.Exception.Message
      candidate = $script:CandidateIdentity.candidate
      runId = $script:ActiveStaleWorkerContext.Fixture.RunId
      step = "RECORD_EVALUATION_EVIDENCE"
      claimantA = $script:ActiveStaleWorkerContext.ClaimantA
      claimantB = $script:ActiveStaleWorkerContext.ClaimantB
      boundaryA = $script:ActiveStaleWorkerContext.BoundaryA
      boundaryB = $script:ActiveStaleWorkerContext.BoundaryB
      negativePerturbation = "../stale-worker-negative-proof.json"
      evidenceBeforeCleanup = "evidence-before-cleanup.json"
      } | ConvertTo-Json -Depth 50 |
        Set-Content -LiteralPath $failureScenarioPath -Encoding utf8
    }
  }
  throw
}
finally {
  # Cleanup evidence preservation precedes every rollout or pod replacement. If A
  # reached the barrier, its claimant file and a final pre-cleanup log/state capture survive even
  # when a later qualification control fails.
  try {
    Preserve-ActiveStaleWorkerEvidence
  } catch {
    Write-Warning "could not preserve stale-worker evidence before cleanup: $($_.Exception.Message)"
  }
  # On failure, do not release a held claimant into the production effect path after the preserved
  # checkpoint. Scaling the qualification Deployment to zero below terminates any unreleased
  # worker without allowing it to mutate authoritative state after evidence capture.
  # Always restore the isolated namespace to its normal two-replica, no-fault posture. No Secret
  # object or decoded credential is read or written by this cleanup.
  try {
    Wait-DeploymentReady "learning-platform" 0 "learning-platform"
    Set-BackendFault $false "" "" "" 120000
    Wait-DeploymentReady "learning-platform" 2 "learning-platform"
  } catch {
    Write-Warning "could not restore learning-platform: $($_.Exception.Message)"
  }
  try {
    Wait-DeploymentReady "ramals-ai" 0 "ramals-ai"
    Set-AiQualification $false $false "" 120000
    Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  } catch {
    Write-Warning "could not restore ramals-ai: $($_.Exception.Message)"
  }
}
