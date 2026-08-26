# Harness-level tests for the dispatch capture helpers inside crash-qualification.ps1.
#
# crash-qualification.ps1 cannot be dot-sourced (it has a mandatory parameter block and runs a
# qualification on load), so the capture functions are lifted out of its AST and exercised against
# synthetic PostgreSQL snapshots. Nothing here touches Kubernetes or PostgreSQL, and no crash
# scenario is executed.
#
# The code under test is the part most likely to be silently wrong: snake_case to camelCase
# mapping, SQL NULL handling, and the sampler's stop condition.

$ErrorActionPreference = "Stop"

$harnessPath = Join-Path $PSScriptRoot "crash-qualification.ps1"
$errors = $null
$tokens = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($harnessPath, [ref]$tokens, [ref]$errors)
if ($errors -and $errors.Count -gt 0) {
  throw "crash-qualification.ps1 does not parse: $(($errors | ForEach-Object { $_.Message }) -join '; ')"
}

$required = @(
  "Get-DiagnosticDispatchRow",
  "New-DiagnosticDispatchCheckpoint",
  "Watch-DiagnosticDispatchTransitions",
  "Get-DiagnosticCommissionCheckpointCounts"
)
foreach ($name in $required) {
  $definition = $ast.FindAll(
    { param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name },
    $true)
  if (@($definition).Count -ne 1) {
    throw "crash-qualification.ps1 must define exactly one $name"
  }
  . ([scriptblock]::Create(@($definition)[0].Extent.Text))
}

# The harness must dot-source the proof module, or the assertions would silently not exist.
$harnessText = Get-Content -LiteralPath $harnessPath -Raw
if (-not $harnessText.Contains('. (Join-Path $scriptRoot "dispatch-ownership-proof.ps1")')) {
  throw "crash-qualification.ps1 does not load dispatch-ownership-proof.ps1"
}
if (-not $harnessText.Contains("Assert-DiagnosticDispatchOwnershipProof")) {
  throw "crash-qualification.ps1 never invokes the dispatch-ownership proof"
}
if (-not $harnessText.Contains("'aiDispatch', COALESCE")) {
  throw "the scenario snapshot does not capture core.ai_execution_dispatch"
}

$requestId = "diag-req-0190000000007000"
$fixture = [pscustomobject]@{ DiagnosticRequestId = $requestId; InteractionId = "interaction-1" }

function New-SnapshotJson {
  param(
    [Parameter(Mandatory = $true)][AllowNull()]$Dispatch,
    [int]$Executions = 0,
    [string]$ExecutionStatus = "SUCCEEDED",
    [int]$Started = 1,
    [int]$Terminal = 0,
    [int]$Gate = 0
  )
  $events = @()
  for ($i = 0; $i -lt $Started; $i++) {
    $events += @{ agent_type = "DIAGNOSTIC"; request_id = $requestId; event_type = "STARTED" }
  }
  for ($i = 0; $i -lt $Terminal; $i++) {
    $events += @{ agent_type = "DIAGNOSTIC"; request_id = $requestId; event_type = "SUCCEEDED" }
  }
  $executionRows = @()
  for ($i = 0; $i -lt $Executions; $i++) {
    $executionRows += @{
      agent_type = "DIAGNOSTIC"; request_id = $requestId
      status = $ExecutionStatus; error_code = $null
    }
  }
  $gates = @()
  for ($i = 0; $i -lt $Gate; $i++) { $gates += @{ request_id = $requestId } }
  return (@{
      workflow = @{ status = "RUNNING"; terminal_reason = $null }
      steps = @()
      evidence = @(@{ id = 1 })
      mastery = @(@{ id = 1 })
      evaluationDecision = @{}
      diagnosticGate = $gates
      recommendationDecision = @()
      adaptationOutbox = @()
      aiExecutions = $executionRows
      aiEvents = $events
      aiDispatch = if ($null -eq $Dispatch) { @() } else { @($Dispatch) }
    } | ConvertTo-Json -Depth 20)
}

function New-DispatchRowJson {
  param(
    [string]$State = "AVAILABLE",
    $OwnerToken = $null,
    [long]$Fence = 0,
    $OwnershipAcquiredAt = $null,
    $InvocationStartedAt = $null
  )
  return @{
    request_id = $requestId
    commission_event_id = "01900000-0000-7000-8000-0000000000c1"
    state = $State
    context_id = "ctx-0190000000007000"
    context_as_of = "2026-08-26T03:00:00.000000Z"
    owner_token = $OwnerToken
    fence = $Fence
    commissioned_at = "2026-08-26T03:00:01.000000Z"
    ownership_acquired_at = $OwnershipAcquiredAt
    invocation_started_at = $InvocationStartedAt
  }
}

# --- SQL NULL must survive as $null, not as the empty string -----------------------------------
$availableSnapshot = New-SnapshotJson (New-DispatchRowJson)
$row = Get-DiagnosticDispatchRow $availableSnapshot $fixture
if ([string]$row.state -ne "AVAILABLE") { throw "AVAILABLE state was not read back" }
if ($null -ne $row.ownerToken) { throw "a NULL owner_token must normalise to `$null, not ''" }
if ($null -ne $row.ownershipAcquiredAt) { throw "a NULL ownership_acquired_at must normalise to `$null" }
if ($null -ne $row.invocationStartedAt) { throw "a NULL invocation_started_at must normalise to `$null" }
if ([long]$row.fence -ne 0) { throw "fence was not read back" }
if ([string]$row.requestId -ne $requestId) { throw "request_id was not mapped to requestId" }
if ([string]$row.commissionEventId -ne "01900000-0000-7000-8000-0000000000c1") {
  throw "commission_event_id was not mapped to commissionEventId"
}
if ([string]$row.contextId -ne "ctx-0190000000007000") { throw "context_id was not mapped" }
if ([string]$row.contextAsOf -ne "2026-08-26T03:00:00.000000Z") { throw "context_as_of was not mapped" }

# --- an absent dispatch row must be $null, not a fabricated empty row ---------------------------
if ($null -ne (Get-DiagnosticDispatchRow (New-SnapshotJson $null -Started 0) $fixture)) {
  throw "a missing dispatch row must normalise to `$null so the proof can fail closed"
}

# --- a dispatch row belonging to another request must not be picked up --------------------------
$foreign = New-DispatchRowJson
$foreign.request_id = "diag-req-0190000000007099"
if ($null -ne (Get-DiagnosticDispatchRow (New-SnapshotJson $foreign) $fixture)) {
  throw "a dispatch row for another request must not be captured"
}

# --- checkpoint shape ---------------------------------------------------------------------------
$checkpoint = New-DiagnosticDispatchCheckpoint "after-commission-before-death" $availableSnapshot $fixture
if ([string]$checkpoint.name -ne "after-commission-before-death") { throw "checkpoint name was not set" }
if ([int]$checkpoint.rowCount -ne 1) { throw "checkpoint rowCount was not counted" }
if ([int]$checkpoint.providerInvocationCount -ne 0) { throw "provider invocations must start at 0" }
if ([int]$checkpoint.commissionCount -ne 1) { throw "the STARTED commission was not counted" }
if ([int]$checkpoint.terminalCount -ne 0 -or [int]$checkpoint.gateCount -ne 0) {
  throw "terminal and gate counts must start at 0"
}

$inFlightRow = New-DispatchRowJson -State "IN_FLIGHT" `
  -OwnerToken "01900000-0000-7000-8000-0000000000d1" -Fence 1 `
  -OwnershipAcquiredAt "2026-08-26T03:02:10.000000Z" `
  -InvocationStartedAt "2026-08-26T03:02:10.250000Z"
$finalCheckpoint = New-DiagnosticDispatchCheckpoint "final" `
  (New-SnapshotJson $inFlightRow -Executions 1 -Terminal 1 -Gate 1) $fixture
if ([int]$finalCheckpoint.providerInvocationCount -ne 1) { throw "the final provider invocation was not counted" }
if ([int]$finalCheckpoint.terminalCount -ne 1 -or [int]$finalCheckpoint.gateCount -ne 1) {
  throw "the final terminal/gate counts were not captured"
}

# --- the sampler: distinct states only, and it stops at the diagnostic terminal event -----------
$script:SamplerScript = @(
  (New-SnapshotJson (New-DispatchRowJson)),
  (New-SnapshotJson (New-DispatchRowJson)),
  (New-SnapshotJson (New-DispatchRowJson -State "DISPATCH_OWNED" `
      -OwnerToken "01900000-0000-7000-8000-0000000000d1" -Fence 1 `
      -OwnershipAcquiredAt "2026-08-26T03:02:10.000000Z")),
  (New-SnapshotJson $inFlightRow),
  (New-SnapshotJson $inFlightRow -Executions 1 -Terminal 1 -Gate 1)
)
$script:SamplerIndex = 0
function Get-ScenarioDbSnapshot {
  param([Parameter(Mandatory = $true)]$Fixture)
  $value = $script:SamplerScript[[Math]::Min($script:SamplerIndex, $script:SamplerScript.Count - 1)]
  $script:SamplerIndex++
  return $value
}

$watch = Watch-DiagnosticDispatchTransitions $fixture -TimeoutSeconds 30
$states = @($watch.samples | ForEach-Object { [string]$_.state })
if (($states -join ",") -ne "AVAILABLE,DISPATCH_OWNED,IN_FLIGHT") {
  throw "the sampler did not record one entry per distinct dispatch state: $($states -join ',')"
}
if ($script:SamplerIndex -gt $script:SamplerScript.Count) {
  throw "the sampler did not stop at the diagnostic terminal event"
}
if ($null -eq $watch.acquisition -or [string]$watch.acquisition.dispatch.state -ne "DISPATCH_OWNED") {
  throw "the sampler did not retain the DISPATCH_OWNED checkpoint"
}
if ($null -eq $watch.inFlight -or [string]$watch.inFlight.dispatch.state -ne "IN_FLIGHT") {
  throw "the sampler did not retain the IN_FLIGHT checkpoint"
}
if ([int]$watch.inFlight.providerInvocationCount -ne 0) {
  throw "the IN_FLIGHT checkpoint must be taken before the provider invocation is recorded"
}

# --- the sampler must not invent an IN_FLIGHT checkpoint once the provider has already run -------
$script:SamplerScript = @(
  (New-SnapshotJson $inFlightRow -Executions 1 -Terminal 1 -Gate 1)
)
$script:SamplerIndex = 0
$late = Watch-DiagnosticDispatchTransitions $fixture -TimeoutSeconds 30
if ($null -ne $late.inFlight) {
  throw "an IN_FLIGHT checkpoint was recorded after the provider invocation already existed"
}

# --- the captured checkpoints must satisfy the proof end to end ----------------------------------
. (Join-Path $PSScriptRoot "dispatch-ownership-proof.ps1")
$script:SamplerScript = @(
  (New-SnapshotJson (New-DispatchRowJson)),
  (New-SnapshotJson (New-DispatchRowJson -State "DISPATCH_OWNED" `
      -OwnerToken "01900000-0000-7000-8000-0000000000d1" -Fence 1 `
      -OwnershipAcquiredAt "2026-08-26T03:02:10.000000Z")),
  (New-SnapshotJson $inFlightRow),
  (New-SnapshotJson $inFlightRow -Executions 1 -Terminal 1 -Gate 1)
)
$script:SamplerIndex = 0
$endToEndWatch = Watch-DiagnosticDispatchTransitions $fixture -TimeoutSeconds 30
$endToEndWatch.acquisition.name = "after-dispatch-acquisition"
$endToEndWatch.inFlight.name = "in-flight-before-provider"
$observation = [pscustomobject]@{
  schema = "m2-t15.dispatch-ownership-observation.v1"
  requestId = $requestId
  checkpoints = @(
    (New-DiagnosticDispatchCheckpoint "after-commission-before-death" $availableSnapshot $fixture),
    (New-DiagnosticDispatchCheckpoint "after-death-before-reclaim" $availableSnapshot $fixture),
    (New-DiagnosticDispatchCheckpoint "replacement-held" $availableSnapshot $fixture),
    $endToEndWatch.acquisition,
    $endToEndWatch.inFlight,
    (New-DiagnosticDispatchCheckpoint "final" `
      (New-SnapshotJson $inFlightRow -Executions 1 -Terminal 1 -Gate 1) $fixture)
  )
  transitionSamples = @($endToEndWatch.samples)
  claimA = [pscustomobject]@{ executionToken = "tok-a"; attemptCount = 1; claimedAt = "2026-08-26T03:00:00.500000Z" }
  claimB = [pscustomobject]@{ executionToken = "tok-b"; attemptCount = 2; claimedAt = "2026-08-26T03:02:00.000000Z" }
  preReclaimClaimedAt = @("2026-08-26T03:00:00.500000Z", "2026-08-26T03:00:00.500000Z", "2026-08-26T03:00:00.500000Z")
  naturalLease = [pscustomobject]@{ expired = $true; leaseExpiresAt = "2026-08-26T03:01:00.500000Z" }
  podUidA = "uid-a"
  podUidB = "uid-b"
  finalCounts = [pscustomobject]@{
    evidence = 1; mastery = 1; diagnosticExecution = 1; diagnosticExecutionStatus = "SUCCEEDED"
    diagnosticExecutionError = ""; diagnosticCommission = 1; diagnosticTerminal = 1
    diagnosticGate = 1; outbox = 1
  }
  workflow = [pscustomobject]@{ status = "COMPLETED"; terminalReason = "WORKFLOW_COMPLETED" }
  cursorHistoryResult = "PASS"
}
# The synthetic snapshot has no adaptation outbox row; supply the one the real run commits.
$observation.finalCounts.outbox = 1
$proof = Assert-DiagnosticDispatchOwnershipProof $observation
if ([string]$proof.result -ne "PASS" -or [long]$proof.fence -ne 1) {
  throw "captured checkpoints did not satisfy the dispatch-ownership proof"
}

[ordered]@{
  schema = "m2-t15.dispatch-capture-tests.v1"
  result = "PASS"
  functionsUnderTest = $required
  cases = @(
    "SQL NULL normalises to `$null",
    "missing dispatch row normalises to `$null",
    "another request's dispatch row is not captured",
    "checkpoint counts map to the diagnostic request",
    "sampler records one entry per distinct state",
    "sampler stops at the diagnostic terminal event",
    "sampler does not record IN_FLIGHT after the provider invocation exists",
    "captured checkpoints satisfy the dispatch-ownership proof"
  )
} | ConvertTo-Json -Depth 20
