function Assert-AfterClaimNaturalLeaseObservation {
  param([Parameter(Mandatory = $true)]$Observation)

  $claimA = $Observation.claimA
  foreach ($checkpointName in @("postDeletionClaim", "preLeaseClaim")) {
    $checkpoint = $Observation.$checkpointName
    if ([string]$checkpoint.status -ne "RUNNING" -or
        [int]$checkpoint.attemptCount -ne [int]$claimA.attemptCount -or
        [string]$checkpoint.executionToken -ne [string]$claimA.executionToken) {
      throw "after-claim $checkpointName did not preserve worker A ownership before reclaim"
    }
    if ([string]$checkpoint.claimedAt -ne [string]$claimA.claimedAt) {
      throw "after-claim qualification must not alter claimed_at"
    }
  }
  if ($null -ne $Observation.postLeasePreReclaimClaim) {
    $checkpoint = $Observation.postLeasePreReclaimClaim
    if ([string]$checkpoint.status -ne "RUNNING" -or
        [int]$checkpoint.attemptCount -ne [int]$claimA.attemptCount -or
        [string]$checkpoint.executionToken -ne [string]$claimA.executionToken -or
        [string]$checkpoint.claimedAt -ne [string]$claimA.claimedAt) {
      throw "after-claim post-lease/pre-reclaim checkpoint did not preserve worker A ownership"
    }
  }
  if (-not [bool]$Observation.preLeaseReclaimAttemptObserved) {
    throw "after-claim proof did not observe the replacement application's real pre-lease claim SQL"
  }
  if ([bool]$Observation.preLeaseReclaimSucceeded) {
    throw "after-claim replacement reclaimed before the production lease expired"
  }
  if (-not [bool]$Observation.leaseExpiredNaturally) {
    throw "after-claim proof did not observe natural production lease expiry"
  }
  if (-not [bool]$Observation.workerBHeld) {
    throw "after-claim worker B was not held before authoritative completion"
  }
  if ([string]::IsNullOrWhiteSpace([string]$Observation.claimB.executionToken) -or
      [string]$Observation.claimB.executionToken -eq [string]$claimA.executionToken) {
    throw "after-claim reclaim requires a new non-empty execution token"
  }
  if ([int]$Observation.claimB.attemptCount -ne ([int]$claimA.attemptCount + 1)) {
    throw "after-claim reclaim must increment the attempt exactly once"
  }
  if ([string]$Observation.podUidA -eq [string]$Observation.podUidB) {
    throw "after-claim reclaim must originate from the replacement pod"
  }
  foreach ($countsName in @("postDeletionCounts", "preLeaseCounts")) {
    $counts = $Observation.$countsName
    if ([int]$counts.evidence -ne 0 -or
        [int]$counts.mastery -ne 0 -or
        [int]$counts.diagnostic -ne 0 -or
        [int]$counts.outbox -ne 0) {
      throw "after-claim $countsName contains an authoritative effect before reclaim"
    }
  }
  if ([datetimeoffset]::Parse([string]$Observation.claimB.claimedAt) -lt
      [datetimeoffset]::Parse([string]$Observation.leaseExpiresAt)) {
    throw "after-claim worker B claimed before the production lease expiry timestamp"
  }

  return [pscustomobject]@{
    result = "PASS"
    tokenA = [string]$claimA.executionToken
    tokenB = [string]$Observation.claimB.executionToken
    attemptA = [int]$claimA.attemptCount
    attemptB = [int]$Observation.claimB.attemptCount
  }
}
