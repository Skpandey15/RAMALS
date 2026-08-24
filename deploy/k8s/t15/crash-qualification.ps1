[CmdletBinding()]
param(
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
    "contention"
  )]
  [string]$Scenario = "all",
  [string]$ClusterName = "t15",
  [string]$Namespace = "ramals-t15",
  [string]$EvidenceRoot = ""
)

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
Set-Location $repositoryRoot

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
  $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
  $EvidenceRoot = Join-Path $scriptRoot "evidence\m2-t15.2-$stamp"
}
New-Item -ItemType Directory -Path $EvidenceRoot -Force | Out-Null

$script:Summary = [System.Collections.Generic.List[string]]::new()
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
  $output = $Sql | & kubectl exec -i postgres-0 -n $Namespace -- sh -ec `
    'psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -X -A -t -F "|"' 2>&1
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
  param([Parameter(Mandatory = $true)]$Fixture)
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
    if (@(Get-PodNames $Label).Count -eq 0) {
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
    [int]$PauseMs = 120000
  )
  $enabledValue = if ($Enabled) { "true" } else { "false" }
  [void](Invoke-Kubectl @(
      "set", "env", "deployment/learning-platform", "-n", $Namespace,
      "RAMALS_QUALIFICATION_FAULT_ENABLED=$enabledValue",
      "RAMALS_QUALIFICATION_FAULT_WINDOW=$Window",
      "RAMALS_QUALIFICATION_FAULT_RUN_ID=$RunId",
      "RAMALS_QUALIFICATION_FAULT_REQUEST_ID=$RequestId",
      "RAMALS_QUALIFICATION_FAULT_PAUSE_MS=$PauseMs"
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

function Force-DeletePod {
  param([Parameter(Mandatory = $true)][string]$Pod)
  [void](Invoke-Kubectl @(
      "delete", "pod", $Pod, "-n", $Namespace,
      "--grace-period=0", "--force", "--wait=false"
    ))
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
  $podUid = Get-PodUid $boundaryPod
  Force-DeletePod $boundaryPod
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  Set-BackendFault $false "" "" "" 120000
  Expire-WorkflowClaim $Fixture.RunId $StepName
  Wait-DeploymentReady "learning-platform" 1 "learning-platform"
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  [pscustomobject]@{
    Pod = $boundaryPod
    PodUid = $podUid
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
  $podUid = Get-PodUid $providerPod
  Force-DeletePod $providerPod
  Wait-DeploymentReady "ramals-ai" 0 "ramals-ai"
  Set-AiQualification $true $false "" 120000
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  [pscustomobject]@{
    Pod = $providerPod
    PodUid = $podUid
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
  $podUid = Get-PodUid $boundaryPod
  Force-DeletePod $boundaryPod
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  Set-BackendFault $false "" "" "" 120000
  Expire-OutboxLease $work.Id
  Wait-DeploymentReady "learning-platform" 1 "learning-platform"
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  $outboxEnd = Get-Scalar "SELECT status FROM core.agent_work_outbox WHERE id = '$($work.Id)';"
  $end = (Get-Date).ToUniversalTime().AddSeconds(120)
  while ($outboxEnd -ne "TERMINAL" -and (Get-Date).ToUniversalTime() -lt $end) {
    Start-Sleep -Milliseconds 500
    $outboxEnd = Get-Scalar "SELECT status FROM core.agent_work_outbox WHERE id = '$($work.Id)';"
  }
  Assert-Equal "adaptation commission outbox terminal state" $outboxEnd "TERMINAL"
  [pscustomobject]@{
    Pod = $boundaryPod
    PodUid = $podUid
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
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  Set-BackendFault $true "WORKFLOW_AFTER_CLAIM" $Fixture.RunId "" 5000
  Wait-DeploymentReady "learning-platform" 2 "learning-platform"
  $boundaryPod = Wait-WorkflowBoundary "WORKFLOW_AFTER_CLAIM" $Fixture.RunId
  $claim = Get-StepClaim $Fixture.RunId "RECORD_EVALUATION_EVIDENCE"
  $boundaryCursor = Format-CursorObservation (Get-WorkflowState $Fixture.RunId)
  $podUid = Get-PodUid $boundaryPod
  $terminal = Wait-WorkflowTerminal $Fixture.RunId
  Wait-DeploymentReady "learning-platform" 0 "learning-platform"
  Set-BackendFault $false "" "" "" 120000
  Wait-DeploymentReady "learning-platform" 2 "learning-platform"
  [pscustomobject]@{
    Pod = $boundaryPod
    PodUid = $podUid
    OldToken = $claim.Token
    OldAttempt = $claim.AttemptCount
    Terminal = $terminal
    CursorHistory = @($boundaryCursor) + @($terminal.History)
    StaleOutboxId = ""
    StaleOutboxOwner = ""
  }
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
  $staleWorkflow = Run-StaleWorkflowCas $Fixture $Crash $TargetStep
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
    [Parameter(Mandatory = $true)]$Assertions
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
    # Qualification evidence needs boundary/correlation lines, not arbitrary application output.
    # Keeping the allow-list here prevents a provider credential accidentally reaching an artifact.
    $safeLines = @(& kubectl logs $Pod -n $Namespace --since=30m 2>&1 |
      Where-Object {
        $_ -match '(?i)(qualification (crash|provider) boundary|interactionId|traceId|requestId|workflow|ai_execution|pod.*(kill|delet))'
      })
    if ($safeLines.Count -eq 0) {
      'No allow-listed qualification/correlation log lines captured.' |
        Out-File -LiteralPath $Path -Encoding utf8
      return
    }
    $safeLines | Out-File -LiteralPath $Path -Encoding utf8
  }

  $stateSql = @"
SELECT 'workflow' AS section, r.* FROM core.learning_workflow_run r WHERE r.id = '$($Fixture.RunId)';
SELECT 'steps' AS section, s.* FROM core.learning_workflow_step s WHERE s.run_id = '$($Fixture.RunId)' ORDER BY s.step_index;
SELECT 'evidence' AS section, e.* FROM ledger.evidence e WHERE e.learner_id = '$($Fixture.LearnerId)' ORDER BY e.recorded_at, e.id;
SELECT 'mastery' AS section, m.* FROM ledger.mastery_snapshot m WHERE m.learner_id = '$($Fixture.LearnerId)' ORDER BY m.aggregate_version;
SELECT 'assessment-evaluation' AS section, d.* FROM ledger.assessment_evaluation_decision d WHERE d.request_id = '$($Fixture.EvaluationRequestId)';
SELECT 'diagnostic-gate' AS section, d.* FROM ledger.proposal_gate_decision d WHERE d.request_id = '$($Fixture.DiagnosticRequestId)';
SELECT 'recommendation-decision' AS section, d.* FROM ledger.decision_record d WHERE d.learner_id = '$($Fixture.LearnerId)' ORDER BY d.decided_at, d.id;
SELECT 'recommendation' AS section, r.* FROM core.learning_recommendation r WHERE r.learner_id = '$($Fixture.LearnerId)' ORDER BY r.created_at, r.id;
SELECT 'adaptation-outbox' AS section, w.* FROM core.agent_work_outbox w WHERE w.interaction_id = '$($Fixture.InteractionId)' ORDER BY w.created_at, w.id;
SELECT 'ai-execution' AS section, x.* FROM core.ai_execution x WHERE x.interaction_id = '$($Fixture.InteractionId)' ORDER BY x.started_at, x.id;
SELECT 'ai-events' AS section, e.* FROM core.ai_execution_event e WHERE e.interaction_id = '$($Fixture.InteractionId)' ORDER BY e.occurred_at, e.id;
SELECT 'grounding-contexts' AS section, g.* FROM ledger.grounding_retrieval_record g WHERE g.learner_id = '$($Fixture.LearnerId)' ORDER BY g.recorded_at, g.context_id;
"@
  (Invoke-Psql $stateSql) | Out-File -LiteralPath (Join-Path $directory "durable-state.txt") -Encoding utf8

  $pods = Invoke-Kubectl @("get", "pods", "-n", $Namespace, "-o", "wide")
  $pods | Out-File -LiteralPath (Join-Path $directory "pods.txt") -Encoding utf8
  $events = Invoke-Kubectl @("get", "events", "-n", $Namespace, "--sort-by=.lastTimestamp")
  $events | Out-File -LiteralPath (Join-Path $directory "events.txt") -Encoding utf8
  foreach ($pod in @(Get-PodNames "learning-platform")) {
    Save-SafePodLog $pod (Join-Path $directory "backend-$pod.log")
  }
  foreach ($pod in @(Get-PodNames "ramals-ai")) {
    Save-SafePodLog $pod (Join-Path $directory "ai-$pod.log")
  }
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
      Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
      Wait-DeploymentReady "learning-platform" 0 "learning-platform"
      Set-BackendFault $true "WORKFLOW_AFTER_CLAIM" $fixture.RunId "" 5000
      Seed-ScenarioFixture $fixture
      $targetStep = "RECORD_EVALUATION_EVIDENCE"
      $expectedTargetAttempt = 1
      $crash = Invoke-Contention $fixture
    }
  }

  $assertions = Assert-Scenario $fixture $Name $targetStep $expectedTargetAttempt `
    $expectedFailure $expectedAdaptation $expectedAdaptationAbandoned $crash
  $history = @($crash.CursorHistory)
  if ($history.Count -eq 0) {
    $history = @($crash.Terminal.History)
  }
  Assert-CursorHistory $Name $history
  Capture-ScenarioEvidence $fixture $Name $crash $history $assertions
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
  Wait-DeploymentReady "learning-platform" 2 "learning-platform"
  Wait-DeploymentReady "ramals-ai" 2 "ramals-ai"
  $migration = Get-Scalar "SELECT version FROM core.flyway_schema_history WHERE version = '033' AND success = true;"
  Assert-Equal "V033 migration" $migration "033"
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
  throw
}
finally {
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
