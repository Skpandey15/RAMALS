$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "after-claim-proof.ps1")

$claimA = [pscustomobject]@{
  status = "RUNNING"
  executionToken = "01900000-0000-7000-8000-000000000911"
  attemptCount = 1
  claimedAt = "2026-08-25T06:00:00.000000Z"
}
$valid = [pscustomobject]@{
  claimA = $claimA
  postDeletionClaim = $claimA
  preLeaseClaim = $claimA
  postLeasePreReclaimClaim = $claimA
  preLeaseReclaimAttemptObserved = $true
  preLeaseReclaimSucceeded = $false
  leaseExpiredNaturally = $true
  workerBHeld = $true
  leaseExpiresAt = "2026-08-25T06:01:00.000000Z"
  postDeletionCounts = [pscustomobject]@{ evidence = 0; mastery = 0; diagnostic = 0; outbox = 0 }
  preLeaseCounts = [pscustomobject]@{ evidence = 0; mastery = 0; diagnostic = 0; outbox = 0 }
  claimB = [pscustomobject]@{
    status = "RUNNING"
    executionToken = "01900000-0000-7000-8000-000000000912"
    attemptCount = 2
    claimedAt = "2026-08-25T06:01:00.100000Z"
  }
  podUidA = "pod-a"
  podUidB = "pod-b"
}
[void](Assert-AfterClaimNaturalLeaseObservation $valid)

$mutated = $valid.PSObject.Copy()
$mutated.postDeletionClaim = [pscustomobject]@{
  status = "RUNNING"
  executionToken = $claimA.executionToken
  attemptCount = 1
  claimedAt = "2026-08-25T05:58:00.000000Z"
}
$mutationRejected = $false
$mutationRejection = ""
try {
  [void](Assert-AfterClaimNaturalLeaseObservation $mutated)
} catch {
  $mutationRejected = $true
  $mutationRejection = $_.Exception.Message
}
if (-not $mutationRejected) {
  throw "manual claimed_at mutation unexpectedly passed after-claim qualification"
}

[ordered]@{
  schema = "m2-t15.after-claim-natural-lease-proof.v1"
  result = "PASS"
  naturalLease = "PASS"
  claimedAtMutation = [ordered]@{
    expected = "FAIL"
    result = "PASS"
    rejection = $mutationRejection
  }
} | ConvertTo-Json -Depth 20
