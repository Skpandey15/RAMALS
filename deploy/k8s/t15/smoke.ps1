[CmdletBinding()]
param(
  [string]$ApprovedCommit = "",
  [string]$ApprovedRef = "origin/main",
  [string]$ClusterName = "t15",
  [string]$Namespace = "ramals-t15",
  [string]$EvidenceDirectory = "",
  [int]$BackendPort = 18080,
  [int]$AiPort = 18000,
  [int]$WebPort = 15173,
  [int]$KeycloakPort = 19000
)

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
Set-Location $repositoryRoot
$lockPath = Join-Path $scriptRoot "images.lock.json"
$candidateSource = "explicit"

if ([string]::IsNullOrWhiteSpace($ApprovedCommit)) {
  if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) {
    throw "qualification lock does not exist: $lockPath"
  }
  try {
    $reviewedLock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
  } catch {
    throw "qualification lock is not valid JSON: $($_.Exception.Message)"
  }
  $ApprovedCommit = [string]$reviewedLock.sourceCommit
  $candidateSource = "reviewed-lock"
}
if ($ApprovedCommit -notmatch '^[0-9a-fA-F]{40}$') {
  throw "ApprovedCommit must be a full 40-character Git commit, supplied explicitly or read from images.lock.json"
}

if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
  $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
  $EvidenceDirectory = Join-Path $scriptRoot (Join-Path "evidence" $stamp)
}
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null

function Invoke-Kubectl {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)

  $output = & kubectl @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "kubectl $($Arguments -join ' ') failed: $($output -join ' ')"
  }
  return $output
}

function Invoke-KubectlJson {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)

  $output = Invoke-Kubectl ($Arguments + @("-o", "json"))
  return (($output -join "`n") | ConvertFrom-Json)
}

function Save-Kubectl {
  param(
    [Parameter(Mandatory = $true)][string]$FileName,
    [Parameter(Mandatory = $true)][string[]]$Arguments
  )

  $output = Invoke-Kubectl $Arguments
  $output | Set-Content -Path (Join-Path $EvidenceDirectory $FileName) -Encoding utf8
}

function Assert-Rollout {
  param(
    [Parameter(Mandatory = $true)][string]$Resource,
    [Parameter(Mandatory = $true)][string]$FileName
  )

  $output = Invoke-Kubectl @("rollout", "status", $Resource, "-n", $Namespace, "--timeout=300s")
  $output | Set-Content -Path (Join-Path $EvidenceDirectory $FileName) -Encoding utf8
}

function Invoke-CandidateIntegrityGate {
  $gatePath = Join-Path $scriptRoot "candidate-integrity.ps1"
  $output = & pwsh -NoProfile -File $gatePath `
    -ApprovedCommit $ApprovedCommit `
    -ApprovedRef $ApprovedRef `
    -ClusterName $ClusterName `
    -Namespace $Namespace `
    -ManifestRoot $scriptRoot `
    -LockPath (Join-Path $scriptRoot "images.lock.json") `
    -EvidenceDirectory $EvidenceDirectory 2>&1
  $output | Set-Content -Path (Join-Path $EvidenceDirectory "candidate-integrity-gate.log") -Encoding utf8
  if ($LASTEXITCODE -ne 0) {
    throw "candidate-integrity gate failed with exit code $LASTEXITCODE"
  }
  $resultPath = Join-Path $EvidenceDirectory "candidate-integrity.json"
  if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
    throw "candidate-integrity gate did not produce $resultPath"
  }
  return (Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json)
}

function Start-PortForward {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][int]$LocalPort,
    [Parameter(Mandatory = $true)][int]$RemotePort,
    [Parameter(Mandatory = $true)][string]$Path
  )

  $stdout = Join-Path $EvidenceDirectory "$Name.port-forward.out"
  $stderr = Join-Path $EvidenceDirectory "$Name.port-forward.err"
  $arguments = @(
    "port-forward", "--namespace", $Namespace, $Target,
    "${LocalPort}:${RemotePort}"
  )
  $process = Start-Process -FilePath "kubectl" -ArgumentList $arguments -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru

  try {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
      if ($process.HasExited) {
        throw "$Name port-forward exited with code $($process.ExitCode)"
      }
      try {
        $response = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}{1}" -f $LocalPort, $Path) `
          -UseBasicParsing -TimeoutSec 3
        if ($response.StatusCode -eq 200) {
          return [pscustomobject]@{ Process = $process; StatusCode = $response.StatusCode }
        }
      } catch {
        # The forwarding socket can take a few cycles to become available.
      }
      Start-Sleep -Milliseconds 500
    }
    throw "$Name did not answer through port-forward within 30 seconds"
  } catch {
    if (-not $process.HasExited) {
      Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    throw
  }
}

function Stop-PortForward {
  param([Parameter(Mandatory = $true)]$Forward)

  if ($null -ne $Forward -and $null -ne $Forward.Process -and -not $Forward.Process.HasExited) {
    Stop-Process -Id $Forward.Process.Id -Force -ErrorAction SilentlyContinue
  }
}

function Assert-DeploymentImage {
  param(
    [Parameter(Mandatory = $true)][string]$Resource,
    [Parameter(Mandatory = $true)][string]$Component
  )

  $lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
  $entry = @($lock.images, $lock.supportingImages) |
    ForEach-Object { $_.PSObject.Properties[$Component].Value } |
    Where-Object { $null -ne $_ } |
    Select-Object -First 1
  if ($null -eq $entry) {
    throw "qualification lock has no image entry for $Component"
  }
  $object = Invoke-KubectlJson @("get", $Resource, "-n", $Namespace)
  $actual = $object.spec.template.spec.containers[0].image
  if ($actual -ne $entry.reference) {
    throw "$Resource image is '$actual', expected immutable reference '$($entry.reference)'"
  }

  $podObject = Invoke-KubectlJson @(
    "get", "pods", "-n", $Namespace,
    "-l", "app.kubernetes.io/name=$Component"
  )
  foreach ($pod in @($podObject.items)) {
    foreach ($status in @($pod.status.containerStatuses)) {
      if ($status.imageID -notmatch ([regex]::Escape("@" + $entry.digest))) {
        throw "$($pod.metadata.name) resolved '$($status.imageID)', expected digest '$($entry.digest)'"
      }
    }
  }
}

$forwards = @()
try {
  Invoke-Kubectl @("config", "use-context", "k3d-$ClusterName") | Out-Null

  # This is deliberately the first substantive check. It binds the approved commit, lock,
  # rendered intent, live imageIDs and schema before health or smoke assertions can produce a PASS.
  $candidate = Invoke-CandidateIntegrityGate

  Save-Kubectl "cluster.version.json" @("version")
  Save-Kubectl "nodes.txt" @("get", "nodes", "-o", "wide")
  Save-Kubectl "workloads.json" @("get", "deploy,statefulset,job,service", "-n", $Namespace)

  $workloads = @(
    [pscustomobject]@{ Resource = "deployment/learning-platform"; Component = "learning-platform"; Replicas = 2 },
    [pscustomobject]@{ Resource = "deployment/ramals-ai"; Component = "ramals-ai"; Replicas = 2 },
    [pscustomobject]@{ Resource = "deployment/keycloak"; Component = "keycloak"; Replicas = 1 },
    [pscustomobject]@{ Resource = "deployment/web-ui"; Component = "web-ui"; Replicas = 1 },
    [pscustomobject]@{ Resource = "statefulset/postgres"; Component = "postgres"; Replicas = 1 }
  )

  foreach ($workload in $workloads) {
    Assert-Rollout $workload.Resource (($workload.Component) + ".rollout.txt")
    Assert-DeploymentImage $workload.Resource $workload.Component
    $kind, $name = $workload.Resource.Split("/")
    $object = Invoke-KubectlJson @("get", $kind, $name, "-n", $Namespace)
    $ready = if ($null -eq $object.status.readyReplicas) { $object.status.readyReplicas } else { [int]$object.status.readyReplicas }
    if ($ready -ne $workload.Replicas) {
      throw "$($workload.Resource) has $ready ready replicas, expected $($workload.Replicas)"
    }
  }

  $job = Invoke-KubectlJson @("get", "job", "keycloak-client-bootstrap", "-n", $Namespace)
  if ([int]$job.status.succeeded -ne 1) {
    throw "Keycloak client bootstrap Job has not completed successfully"
  }
  $identityJob = Invoke-KubectlJson @("get", "job", "workload-identity-smoke", "-n", $Namespace)
  if ([int]$identityJob.status.succeeded -ne 1) {
    throw "workload identity smoke Job has not completed successfully"
  }
  Save-Kubectl "workload-identity-smoke.log" @("logs", "job/workload-identity-smoke", "-n", $Namespace)

  $forwards += Start-PortForward "backend" "service/backend" $BackendPort 8080 "/actuator/health/readiness"
  $forwards += Start-PortForward "ai" "service/ramals-ai" $AiPort 8000 "/health/ready"
  $forwards += Start-PortForward "web-ui" "service/web-ui" $WebPort 8080 "/healthz"
  $forwards += Start-PortForward "keycloak" "service/keycloak" $KeycloakPort 9000 "/health/ready"

  $health = [ordered]@{
    backendReadiness = (Invoke-WebRequest -Uri "http://127.0.0.1:$BackendPort/actuator/health/readiness" -UseBasicParsing).StatusCode
    aiReadiness = (Invoke-WebRequest -Uri "http://127.0.0.1:$AiPort/health/ready" -UseBasicParsing).StatusCode
    aiCapabilities = (Invoke-WebRequest -Uri "http://127.0.0.1:$AiPort/internal/v1/capabilities" -UseBasicParsing).StatusCode
    webHealth = (Invoke-WebRequest -Uri "http://127.0.0.1:$WebPort/healthz" -UseBasicParsing).StatusCode
    keycloakReadiness = (Invoke-WebRequest -Uri "http://127.0.0.1:$KeycloakPort/health/ready" -UseBasicParsing).StatusCode
  }
  $health | ConvertTo-Json | Set-Content -Path (Join-Path $EvidenceDirectory "health.json") -Encoding utf8

  Save-Kubectl "pods.json" @("get", "pods", "-n", $Namespace, "-o", "json")
  Save-Kubectl "events.txt" @("get", "events", "-n", $Namespace, "--sort-by=.lastTimestamp")
  $run = [ordered]@{
    qualification = "M2-T15.1"
    cluster = $ClusterName
    namespace = $Namespace
    approvedCommit = $ApprovedCommit.ToLowerInvariant()
    approvedRef = $ApprovedRef
    approvedRefCommit = $candidate.approvedRefCommit
    candidateSource = $candidateSource
    candidateIntegrity = $candidate.result
    candidateEvidence = "candidate-integrity.json"
    renderedManifestSha256 = $candidate.candidate.manifest.renderedManifestSha256
    kustomizationSha256 = $candidate.candidate.manifest.kustomizationSha256
    migrationSet = @($candidate.candidate.migrationSet)
    imageLock = (Join-Path $scriptRoot "images.lock.json")
    capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    result = "PASS"
  }
  $run | ConvertTo-Json | Set-Content -Path (Join-Path $EvidenceDirectory "run.json") -Encoding utf8
  Write-Host "T15.1 foundation smoke PASS"
  Write-Host "Evidence: $EvidenceDirectory"
} catch {
  $failure = [ordered]@{
    qualification = "M2-T15.1"
    capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    result = "FAIL"
    error = $_.Exception.Message
  }
  $failure | ConvertTo-Json | Set-Content -Path (Join-Path $EvidenceDirectory "run.json") -Encoding utf8
  throw
} finally {
  foreach ($forward in $forwards) {
    Stop-PortForward $forward
  }
}
