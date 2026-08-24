<#
  Qualification-only validation for the two-replica claim proof.

  The application does not expose a claim-loss event, and changing that production contract would
  alter the frozen candidate.  The contention runner therefore observes the two PostgreSQL claim
  sessions while a qualification row lock holds them at the same atomic claim statement, then uses
  the durable winner to classify the two observed sessions.  These functions validate that evidence
  before a scenario can be written as PASS.
#>

function Assert-ContentionClaimAttemptEvidence {
  param(
    [Parameter(Mandatory = $true)][object[]]$Attempts,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$Step,
    [Parameter(Mandatory = $true)][string]$WinnerPodUid,
    [Parameter(Mandatory = $true)][string]$WinnerToken
  )

  $records = @($Attempts)
  if ($records.Count -ne 2) {
    throw "contention proof requires exactly two observed claim attempts; observed $($records.Count)"
  }

  $sessionKeys = @(
    $records | ForEach-Object {
      "$([string]$_.podUid)|$([string]$_.backendPid)"
    } | Sort-Object -Unique
  )
  if ($sessionKeys.Count -ne 2) {
    throw "contention proof requires two distinct PostgreSQL claim sessions; observed $($sessionKeys.Count)"
  }

  $podUids = @($records | ForEach-Object { [string]$_.podUid } | Sort-Object -Unique)
  if ($podUids.Count -ne 2) {
    throw "contention proof requires two distinct backend pod UIDs; observed $($podUids.Count)"
  }
  if ($podUids -notcontains $WinnerPodUid) {
    throw "durable claim winner pod UID '$WinnerPodUid' was not one of the observed claimers"
  }

  foreach ($record in $records) {
    if ([string]$record.runId -ne $RunId) {
      throw "contention proof contains a claim for a different run: '$($record.runId)'"
    }
    if ([string]$record.step -ne $Step) {
      throw "contention proof contains a claim for a different step: '$($record.step)'"
    }
    if ([int]$record.claimAttempt -ne 1) {
      throw "contention proof expected both contenders at claim attempt 1; observed '$($record.claimAttempt)'"
    }
    if ([string]$record.result -notin @("WON", "LOST")) {
      throw "contention proof has invalid claim result '$($record.result)'"
    }
  }

  $winners = @($records | Where-Object { [string]$_.result -eq "WON" })
  $losers = @($records | Where-Object { [string]$_.result -eq "LOST" })
  if ($winners.Count -ne 1 -or $losers.Count -ne 1) {
    throw "contention proof requires exactly one WON and one LOST claim; observed WON=$($winners.Count), LOST=$($losers.Count)"
  }
  if ([string]$winners[0].podUid -ne $WinnerPodUid) {
    throw "contention proof winner pod UID does not match the durable owner"
  }
  if ([string]$winners[0].executionToken -ne $WinnerToken) {
    throw "contention proof winner token does not match the durable execution token"
  }
  if (-not [string]::IsNullOrWhiteSpace([string]$losers[0].executionToken)) {
    throw "contention proof loser must not have an execution token"
  }

  return $true
}

function New-ContentionClaimAttemptEvidence {
  param(
    [Parameter(Mandatory = $true)][object[]]$Waiters,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$Step,
    [Parameter(Mandatory = $true)][string]$WinnerPodUid,
    [Parameter(Mandatory = $true)][string]$WinnerToken
  )

  # One blocked backend session is one claim invocation. Repeated polling snapshots of that same
  # PID are evidence about one invocation, not additional attempts.
  $sessions = @(
    $Waiters |
      Group-Object -Property @{ Expression = { "$([string]$_.podUid)|$([string]$_.backendPid)" } } |
      ForEach-Object { $_.Group | Select-Object -First 1 }
  )
  if ($sessions.Count -ne 2) {
    throw "contention barrier observed $($sessions.Count) distinct claim sessions; expected two"
  }

  $records = @(
    foreach ($session in $sessions | Sort-Object podUid, backendPid) {
      $won = [string]$session.podUid -eq $WinnerPodUid
      [ordered]@{
        podUid = [string]$session.podUid
        podName = [string]$session.podName
        runId = $RunId
        step = $Step
        claimAttempt = 1
        result = if ($won) { "WON" } else { "LOST" }
        executionToken = if ($won) { $WinnerToken } else { $null }
        backendPid = [string]$session.backendPid
        clientIp = [string]$session.clientIp
        observedAtUtc = [string]$session.observedAtUtc
        waitEventType = [string]$session.waitEventType
        waitEvent = [string]$session.waitEvent
        claimSql = [string]$session.claimSql
      }
    }
  )
  [void](Assert-ContentionClaimAttemptEvidence $records $RunId $Step $WinnerPodUid $WinnerToken)
  return $records
}
