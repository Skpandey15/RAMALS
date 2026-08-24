[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ApprovedCommit,
  [string]$ApprovedRef = "origin/main",
  [string]$ClusterName = "t15",
  [string]$Namespace = "ramals-t15",
  [string]$ManifestRoot = "",
  [string]$LockPath = "",
  [string]$EvidenceDirectory = "",
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
Set-Location $repositoryRoot

if ([string]::IsNullOrWhiteSpace($ManifestRoot)) {
  $ManifestRoot = $scriptRoot
}
$ManifestRoot = (Resolve-Path $ManifestRoot).Path
if ([string]::IsNullOrWhiteSpace($LockPath)) {
  $LockPath = Join-Path $ManifestRoot "images.lock.json"
}
$LockPath = (Resolve-Path $LockPath).Path

if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
  $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
  $EvidenceDirectory = Join-Path $ManifestRoot "evidence\m2-t15.1-$stamp"
}
New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null

$script:Checks = [System.Collections.Generic.List[object]]::new()

function Write-Utf8 {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Content
  )
  $utf8 = [System.Text.UTF8Encoding]::new($false)
  [System.IO.File]::WriteAllText($Path, $Content, $utf8)
}

function Write-JsonEvidence {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)]$Value
  )
  Write-Utf8 $Path (($Value | ConvertTo-Json -Depth 30) + [Environment]::NewLine)
}

function Invoke-Checked {
  param(
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][string[]]$Arguments
  )
  $output = & $Command @Arguments 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "$Command $($Arguments -join ' ') failed with exit code $LASTEXITCODE`n$($output -join "`n")"
  }
  return ($output -join "`n")
}

function Resolve-GitCommit {
  param([Parameter(Mandatory = $true)][string]$Reference)
  return (Invoke-Checked "git" @("rev-parse", "--verify", "$Reference^{commit}")).Trim().ToLowerInvariant()
}

function Test-CommitReachable {
  param(
    [Parameter(Mandatory = $true)][string]$CandidateCommit,
    [Parameter(Mandatory = $true)][string]$ApprovedRefCommit
  )
  # git merge-base returns 1 for a valid, unrelated/non-ancestor pair. That is a
  # qualification failure, not a command failure; only other exit codes are tool errors.
  $null = & git merge-base --is-ancestor $CandidateCommit $ApprovedRefCommit 2>&1
  $exitCode = $LASTEXITCODE
  if ($exitCode -eq 0) {
    return $true
  }
  if ($exitCode -eq 1) {
    return $false
  }
  throw "git merge-base --is-ancestor failed with exit code $exitCode"
}

function Invoke-Kubectl {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  return Invoke-Checked "kubectl" $Arguments
}

function Invoke-KubectlJson {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  $text = Invoke-Kubectl ($Arguments + @("-o", "json"))
  try {
    return $text | ConvertFrom-Json
  } catch {
    throw "kubectl JSON output could not be parsed: $($_.Exception.Message)"
  }
}

function Invoke-PsqlAt {
  param([Parameter(Mandatory = $true)][string]$Sql)
  $output = $Sql | & kubectl exec -i postgres-0 -n $Namespace -- sh -ec `
    'psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -X -A -t -F "|"' 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "in-cluster PostgreSQL command failed with exit code $LASTEXITCODE`n$($output -join "`n")"
  }
  return ($output -join "`n")
}

function Get-Hash {
  param([Parameter(Mandatory = $true)][string]$Path)
  return ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash).ToLowerInvariant()
}

function Add-Check {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Observed
  )
  [void]$script:Checks.Add([ordered]@{
      name = $Name
      result = "PASS"
      observed = $Observed
    })
}

function Require-Check {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][bool]$Condition,
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Observed
  )
  if (-not $Condition) {
    [void]$script:Checks.Add([ordered]@{
        name = $Name
        result = "FAIL"
        observed = $Observed
      })
    throw "$Name failed: $Observed"
  }
  Add-Check $Name $Observed
}

function Read-Lock {
  if (-not (Test-Path -LiteralPath $LockPath -PathType Leaf)) {
    throw "qualification lock does not exist: $LockPath"
  }
  try {
    return (Get-Content -LiteralPath $LockPath -Raw | ConvertFrom-Json)
  } catch {
    throw "qualification lock is not valid JSON: $($_.Exception.Message)"
  }
}

function Get-LockImages {
  param([Parameter(Mandatory = $true)]$Lock)
  $entries = [System.Collections.Generic.List[object]]::new()
  foreach ($groupName in @("images", "supportingImages")) {
    $group = $Lock.PSObject.Properties[$groupName]
    if ($null -eq $group) {
      continue
    }
    foreach ($property in $group.Value.PSObject.Properties) {
      $entry = $property.Value
      if ([string]::IsNullOrWhiteSpace([string]$entry.repository) -or
          [string]::IsNullOrWhiteSpace([string]$entry.digest) -or
          [string]::IsNullOrWhiteSpace([string]$entry.reference)) {
        throw "lock image '$groupName.$($property.Name)' is incomplete"
      }
      $digest = ([string]$entry.digest).ToLowerInvariant()
      if ($digest -notmatch '^sha256:[0-9a-f]{64}$') {
        throw "lock image '$groupName.$($property.Name)' has an invalid digest"
      }
      $reference = [string]$entry.reference
      if ($reference -notmatch ([regex]::Escape("@" + $digest) + '$')) {
        throw "lock image '$groupName.$($property.Name)' reference does not end with its digest"
      }
      if ((Get-RepositoryLeaf $reference) -ne [string]$entry.repository) {
        throw "lock image '$groupName.$($property.Name)' reference repository does not match repository"
      }
      [void]$entries.Add([pscustomobject]@{
          name = $property.Name
          repository = [string]$entry.repository
          digest = $digest
          reference = $reference
        })
    }
  }
  if ($entries.Count -eq 0) {
    throw "qualification lock contains no images"
  }
  return @($entries)
}

function Get-RepositoryLeaf {
  param([Parameter(Mandatory = $true)][string]$Reference)
  $withoutDigest = ($Reference -split "@", 2)[0]
  return ($withoutDigest -split "/")[-1] -replace ":.*$", ""
}

function Get-SafeEnv {
  param([Parameter(Mandatory = $true)]$Object)
  $values = [System.Collections.Generic.List[object]]::new()
  foreach ($container in @($Object.spec.template.spec.containers)) {
    foreach ($env in @($container.env)) {
      if ($null -eq $env.name) {
        continue
      }
      $value = if ($null -ne $env.valueFrom) {
        "<secret-or-config-reference>"
      } elseif ([string]$env.name -match '(?i)(password|secret|token|api.?key|private.?key)') {
        "<redacted>"
      } else {
        [string]$env.value
      }
      [void]$values.Add([ordered]@{
          container = [string]$container.name
          name = [string]$env.name
          value = $value
        })
    }
  }
  return @($values)
}

function Get-MigrationState {
  $rows = Invoke-PsqlAt @"
SELECT COALESCE(version, '') || '|' || success::text
  FROM core.flyway_schema_history
 ORDER BY installed_rank;
"@
  $items = [System.Collections.Generic.List[object]]::new()
  foreach ($line in @($rows -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
    $parts = $line.Trim() -split '\|', 2
    if ($parts.Count -ne 2) {
      throw "malformed Flyway history row: $line"
    }
    [void]$items.Add([ordered]@{
        version = $parts[0]
        success = ($parts[1] -eq "t" -or $parts[1] -eq "true")
      })
  }
  return @($items)
}

function Assert-MigrationSet {
  param(
    [Parameter(Mandatory = $true)]$Lock,
    [Parameter(Mandatory = $true)]$Live
  )
  $expectedRows = @($Lock.migrationSet | ForEach-Object { [string]$_ })
  $expected = @($expectedRows | Sort-Object -Unique)
  Require-Check "approved migration set contains unique versioned rows" `
    ($expectedRows.Count -eq $expected.Count -and
      @($expectedRows | Where-Object { $_ -notmatch '^\d{3}$' }).Count -eq 0) `
    (($expectedRows -join ","))
  $failed = @($Live | Where-Object { -not $_.success })
  Require-Check "no failed Flyway migration" ($failed.Count -eq 0) `
    (($failed | ForEach-Object { $_.version }) -join ",")
  # Flyway may retain one successful baseline row with a null/empty version. It is history metadata,
  # not an approved versioned migration; every versioned row must still match exactly.
  $actualRows = @($Live | Where-Object { $_.success -and -not [string]::IsNullOrWhiteSpace([string]$_.version) } |
    ForEach-Object { [string]$_.version })
  $actual = @($actualRows | Sort-Object -Unique)
  Require-Check "live successful migration rows are unique" `
    ($actualRows.Count -eq $actual.Count) (($actualRows -join ","))
  $missing = @($expected | Where-Object { $_ -notin $actual })
  $extra = @($actual | Where-Object { $_ -notin $expected })
  Require-Check "approved migration set equals live schema history" `
    ($missing.Count -eq 0 -and $extra.Count -eq 0) `
    ("missing=" + ($missing -join ",") + "; extra=" + ($extra -join ","))
  Require-Check "V034 applied successfully" ("034" -in $actual) (($actual -join ","))
}

function Get-RenderedManifest {
  param([string]$Root = "")
  if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = $ManifestRoot
  }
  $rendered = & kubectl kustomize $Root 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "kubectl kustomize failed with exit code $LASTEXITCODE`n$($rendered -join "`n")"
  }
  return (($rendered -join [Environment]::NewLine) + [Environment]::NewLine)
}

function Get-PodContainerRecords {
  param([Parameter(Mandatory = $true)]$Pod)
  $records = [System.Collections.Generic.List[object]]::new()
  foreach ($kind in @("initContainerStatuses", "containerStatuses")) {
    foreach ($status in @($Pod.status.$kind | Where-Object { $null -ne $_ })) {
      $spec = @($Pod.spec.initContainers, $Pod.spec.containers) |
        ForEach-Object { @($_) } |
        Where-Object { $_.name -eq $status.name } |
        Select-Object -First 1
      [void]$records.Add([ordered]@{
          name = [string]$status.name
          image = if ($null -eq $spec) { "" } else { [string]$spec.image }
          imageID = [string]$status.imageID
          ready = if ($null -eq $status.ready) { $null } else { [bool]$status.ready }
          restartCount = [int]$status.restartCount
        })
    }
  }
  return @($records)
}

function Assert-LiveImages {
  param(
    [Parameter(Mandatory = $true)]$Lock,
    [Parameter(Mandatory = $true)]$Images
  )
  $byRepository = @{}
  foreach ($entry in $Images) {
    $byRepository[$entry.repository] = $entry
    Require-Check "lock reference matches digest for $($entry.name)" `
      ($entry.reference -match ([regex]::Escape("@" + $entry.digest) + '$')) $entry.reference
  }

  $workloads = @(
    [pscustomobject]@{ resource = "deployment/learning-platform"; component = "learning-platform"; replicas = 2 },
    [pscustomobject]@{ resource = "deployment/ramals-ai"; component = "ramals-ai"; replicas = 2 },
    [pscustomobject]@{ resource = "deployment/web-ui"; component = "web-ui"; replicas = 1 },
    [pscustomobject]@{ resource = "deployment/keycloak"; component = "keycloak"; replicas = 1 },
    [pscustomobject]@{ resource = "statefulset/postgres"; component = "postgres"; replicas = 1 }
  )
  $workloadRecords = [System.Collections.Generic.List[object]]::new()
  foreach ($workload in $workloads) {
    $kind, $name = $workload.resource.Split("/")
    $object = Invoke-KubectlJson @("get", $kind, $name, "-n", $Namespace)
    $entry = $Images | Where-Object { $_.name -eq $workload.component } | Select-Object -First 1
    if ($null -eq $entry) {
      throw "lock has no primary image for $($workload.component)"
    }
    $primaryContainer = @($object.spec.template.spec.containers) | Select-Object -First 1
    $actual = [string]$primaryContainer.image
    Require-Check "$($workload.resource) rendered image intent" ($actual -eq $entry.reference) $actual
    $ready = if ($null -eq $object.status.readyReplicas) { 0 } else { [int]$object.status.readyReplicas }
    Require-Check "$($workload.resource) ready replica count" ($ready -eq $workload.replicas) "$ready/$($workload.replicas)"
    [void]$workloadRecords.Add([ordered]@{
        resource = $workload.resource
        component = $workload.component
        desiredReplicas = $workload.replicas
        readyReplicas = $ready
        image = $actual
      })
  }

  $podList = Invoke-KubectlJson @(
    "get", "pods", "-n", $Namespace,
    "-l", "ramals.io/qualification=m2-t15"
  )
  $podRecords = [System.Collections.Generic.List[object]]::new()
  foreach ($pod in @($podList.items)) {
    $containers = @(Get-PodContainerRecords $pod)
    foreach ($container in $containers) {
      if ([string]::IsNullOrWhiteSpace($container.imageID)) {
        throw "$($pod.metadata.name)/$($container.name) has no resolved imageID"
      }
      $repository = Get-RepositoryLeaf $container.image
      $entry = $byRepository[$repository]
      if ($null -eq $entry) {
        throw "$($pod.metadata.name)/$($container.name) uses unapproved repository '$repository'"
      }
      $match = [regex]::Match($container.imageID, '@(?<digest>sha256:[0-9a-fA-F]{64})$')
      Require-Check "$($pod.metadata.name)/$($container.name) imageID equals lock" `
        ($match.Success -and $match.Groups["digest"].Value.ToLowerInvariant() -eq $entry.digest) `
        "$($container.imageID); expected $($entry.digest)"
    }
    [void]$podRecords.Add([ordered]@{
        name = [string]$pod.metadata.name
        uid = [string]$pod.metadata.uid
        node = [string]$pod.spec.nodeName
        phase = [string]$pod.status.phase
        containers = $containers
      })
  }
  Require-Check "qualification pods have resolved image records" ($podRecords.Count -gt 0) "count=$($podRecords.Count)"
  return [ordered]@{
    workloads = @($workloadRecords)
    pods = @($podRecords)
  }
}

function Get-EnvironmentRecord {
  param(
    [Parameter(Mandatory = $true)]$PodRecords,
    [Parameter(Mandatory = $true)]$WorkloadRecords
  )
  $version = Invoke-KubectlJson @("version")
  $context = (Invoke-Kubectl @("config", "current-context")).Trim()
  $namespaceObject = Invoke-KubectlJson @("get", "namespace", $Namespace)
  $configMap = Invoke-KubectlJson @("get", "configmap", "ramals-t15-platform-config", "-n", $Namespace)
  $deploymentConfig = [System.Collections.Generic.List[object]]::new()
  foreach ($workload in @("learning-platform", "ramals-ai")) {
    $object = Invoke-KubectlJson @("get", "deployment", $workload, "-n", $Namespace)
    [void]$deploymentConfig.Add([ordered]@{
        deployment = $workload
        env = @(Get-SafeEnv $object)
      })
  }
  return [ordered]@{
    context = $context
    namespace = [string]$namespaceObject.metadata.name
    namespaceUid = [string]$namespaceObject.metadata.uid
    namespaceLabels = $namespaceObject.metadata.labels
    kubernetes = $version
    k3d = (Invoke-Checked "k3d" @("version"))
    replicas = @($WorkloadRecords)
    podUids = @($PodRecords | ForEach-Object {
        [ordered]@{ name = $_.name; uid = $_.uid; node = $_.node; phase = $_.phase }
      })
    podImages = @($PodRecords | ForEach-Object {
        [ordered]@{
          name = $_.name
          uid = $_.uid
          containers = @($_.containers | ForEach-Object {
              [ordered]@{ name = $_.name; image = $_.image; imageID = $_.imageID }
            })
        }
      })
    featureFlagsAndConfiguration = [ordered]@{
      platformConfigMap = $configMap.data
      deploymentEnvironment = @($deploymentConfig)
    }
  }
}

function New-DriftFixture {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)]$Lock,
    [scriptblock]$MutateLock,
    [scriptblock]$MutateManifest
  )
  $root = Join-Path ([System.IO.Path]::GetTempPath()) ("ramals-t15-integrity-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $root -Force | Out-Null
  try {
    foreach ($file in @(Get-ChildItem -LiteralPath $ManifestRoot -Filter "*.yaml" -File)) {
      Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $root $file.Name)
    }
    $mutated = $Lock | ConvertTo-Json -Depth 30 | ConvertFrom-Json
    if ($null -ne $MutateLock) {
      & $MutateLock $mutated
    }
    $tempLock = Join-Path $root "images.lock.json"
    Write-JsonEvidence $tempLock $mutated
    if ($null -ne $MutateManifest) {
      & $MutateManifest $root $mutated
    }
    return [pscustomobject]@{ Name = $Name; Root = $root; Lock = $tempLock }
  } catch {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
    throw
  }
}

function Run-ExpectedDriftFailure {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$TempManifestRoot,
    [Parameter(Mandatory = $true)][string]$TempLock,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$ExpectedFailureText,
    [string]$CandidateCommit = "",
    [string]$CandidateRef = "",
    [string]$GateScriptPath = ""
  )
  $invocation = Invoke-GateForSelfTest `
    -TempManifestRoot $TempManifestRoot `
    -TempLock $TempLock `
    -OutputDirectory $OutputDirectory `
    -CandidateCommit $CandidateCommit `
    -CandidateRef $CandidateRef `
    -GateScriptPath $GateScriptPath
  $exitCode = $invocation.exitCode
  $output = @($invocation.output)
  if ($exitCode -eq 0) {
    throw "drift self-test '$Name' unexpectedly passed"
  }
  $combinedOutput = $output -join "`n"
  if ($combinedOutput -notmatch [regex]::Escape($ExpectedFailureText)) {
    throw "drift self-test '$Name' failed for an unexpected reason; expected '$ExpectedFailureText'"
  }
  return [ordered]@{
    name = $Name
    result = "PASS"
    expected = "gate rejects drift"
    expectedFailureCheck = $ExpectedFailureText
    observedExitCode = $exitCode
  }
}

function Invoke-GateForSelfTest {
  param(
    [Parameter(Mandatory = $true)][string]$TempManifestRoot,
    [Parameter(Mandatory = $true)][string]$TempLock,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$CandidateCommit = "",
    [string]$CandidateRef = "",
    [string]$GateScriptPath = ""
  )
  if ([string]::IsNullOrWhiteSpace($CandidateCommit)) {
    $CandidateCommit = $ApprovedCommit
  }
  if ([string]::IsNullOrWhiteSpace($CandidateRef)) {
    $CandidateRef = $ApprovedRef
  }
  if ([string]::IsNullOrWhiteSpace($GateScriptPath)) {
    $GateScriptPath = Join-Path $scriptRoot "candidate-integrity.ps1"
  }
  New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
  $arguments = @(
    "-NoProfile", "-File", $GateScriptPath,
    "-ApprovedCommit", $CandidateCommit,
    "-ApprovedRef", $CandidateRef,
    "-ClusterName", $ClusterName,
    "-Namespace", $Namespace,
    "-ManifestRoot", $TempManifestRoot,
    "-LockPath", $TempLock,
    "-EvidenceDirectory", $OutputDirectory
  )
  $output = & pwsh @arguments 2>&1
  $exitCode = $LASTEXITCODE
  Write-Utf8 (Join-Path $OutputDirectory "output.log") ($output -join "`n")
  return [pscustomobject]@{
    exitCode = $exitCode
    output = @($output)
    resultPath = Join-Path $OutputDirectory "candidate-integrity.json"
  }
}

function Run-ExpectedGatePass {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$TempManifestRoot,
    [Parameter(Mandatory = $true)][string]$TempLock,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$CandidateCommit = "",
    [string]$CandidateRef = ""
  )
  $invocation = Invoke-GateForSelfTest `
    -TempManifestRoot $TempManifestRoot `
    -TempLock $TempLock `
    -OutputDirectory $OutputDirectory `
    -CandidateCommit $CandidateCommit `
    -CandidateRef $CandidateRef
  if ($invocation.exitCode -ne 0) {
    throw "provenance self-test '$Name' unexpectedly failed: $($invocation.output -join "`n")"
  }
  $gateResult = Get-Content -LiteralPath $invocation.resultPath -Raw | ConvertFrom-Json
  if ($gateResult.result -ne "PASS") {
    throw "provenance self-test '$Name' produced result '$($gateResult.result)'"
  }
  return [ordered]@{
    name = $Name
    result = "PASS"
    expected = "gate accepts reachable candidate"
    observedExitCode = $invocation.exitCode
  }
}

function New-ProvenanceGuardMutation {
  $sourcePath = Join-Path $scriptRoot "candidate-integrity.ps1"
  $source = Get-Content -LiteralPath $sourcePath -Raw
  $needle = '$provenanceCondition = Test-CommitReachable $ApprovedCommit $approvedRefCommit'
  if ($source.IndexOf($needle, [System.StringComparison]::Ordinal) -lt 0) {
    throw "provenance self-test could not locate the ancestry guard"
  }
  $mutatedPath = Join-Path $scriptRoot (".candidate-integrity-selftest-" + [guid]::NewGuid().ToString("N") + ".ps1")
  Write-Utf8 $mutatedPath ($source.Replace($needle, '$provenanceCondition = $true'))
  return $mutatedPath
}

function Run-SelfTests {
  param([Parameter(Mandatory = $true)]$Lock)
  $tests = [System.Collections.Generic.List[object]]::new()
  $selfRoot = Join-Path $EvidenceDirectory "candidate-drift-self-tests"

  $candidateA = $ApprovedCommit.ToLowerInvariant()
  $candidateB = ""
  $headCommit = Resolve-GitCommit "HEAD"
  if ($headCommit -ne $candidateA -and (Test-CommitReachable $candidateA $headCommit)) {
    $candidateB = $headCommit
  } else {
    $descendantsText = Invoke-Checked "git" @("rev-list", "--all", "--ancestry-path", "$candidateA..")
    foreach ($descendant in @($descendantsText -split "`r?`n" | Where-Object { $_ -match '^[0-9a-fA-F]{40}$' })) {
      $normalized = $descendant.ToLowerInvariant()
      if ($normalized -ne $candidateA -and (Test-CommitReachable $candidateA $normalized)) {
        $candidateB = $normalized
        break
      }
    }
  }
  if ([string]::IsNullOrWhiteSpace($candidateB)) {
    throw "provenance self-test requires a descendant commit B for candidate $candidateA"
  }
  $candidateBTree = (Invoke-Checked "git" @("rev-parse", "--verify", "$candidateB^{tree}")).Trim().ToLowerInvariant()
  $originMainCommit = Resolve-GitCommit "origin/main"
  $sameCandidateRef = if ($originMainCommit -eq $candidateA) { "origin/main" } else { $candidateA }
  $descendantCandidateRef = if ($originMainCommit -ne $candidateA -and
      (Test-CommitReachable $candidateA $originMainCommit)) { "origin/main" } else { $candidateB }

  $provenanceFixture = New-DriftFixture "provenance-reachability" $Lock
  try {
    [void]$tests.Add((Run-ExpectedGatePass "candidate-a-ref-a" $provenanceFixture.Root $provenanceFixture.Lock `
        (Join-Path $selfRoot "candidate-a-ref-a") `
        -CandidateCommit $candidateA -CandidateRef $sameCandidateRef))
    [void]$tests.Add((Run-ExpectedGatePass "candidate-a-ref-descendant-b" $provenanceFixture.Root $provenanceFixture.Lock `
        (Join-Path $selfRoot "candidate-a-ref-descendant-b") `
        -CandidateCommit $candidateA -CandidateRef $descendantCandidateRef))
  } finally {
    Remove-Item -LiteralPath $provenanceFixture.Root -Recurse -Force -ErrorAction SilentlyContinue
  }

  $unreachableFixture = New-DriftFixture "candidate-not-reachable" $Lock `
    -MutateLock {
      param($value)
      $value.sourceCommit = $candidateB
      $value.sourceTree = $candidateBTree
    }
  try {
    # This is the adversarial case: the lock and candidate agree on B, but approved ref A
    # cannot contain B. The ancestry check must be the first failing authority check.
    [void]$tests.Add((Run-ExpectedDriftFailure "candidate-not-reachable-from-approved-ref" `
        $unreachableFixture.Root $unreachableFixture.Lock `
        (Join-Path $selfRoot "candidate-not-reachable-from-approved-ref") `
        "approved commit is reachable from approved ref" `
        -CandidateCommit $candidateB -CandidateRef $candidateA))

    # Mutation proof: if the ancestry guard is bypassed, this otherwise valid live fixture
    # passes. That makes the negative test sensitive to removal of the provenance protection.
    $mutatedGatePath = New-ProvenanceGuardMutation
    try {
      $perturbed = Invoke-GateForSelfTest `
        -TempManifestRoot $unreachableFixture.Root `
        -TempLock $unreachableFixture.Lock `
        -OutputDirectory (Join-Path $selfRoot "candidate-not-reachable-guard-perturbed") `
        -CandidateCommit $candidateB `
        -CandidateRef $candidateA `
        -GateScriptPath $mutatedGatePath
      if ($perturbed.exitCode -ne 0) {
        throw "provenance guard perturbation did not isolate the reachability check: $($perturbed.output -join "`n")"
      }
      [void]$tests.Add([ordered]@{
          name = "candidate-not-reachable-provenance-guard-perturbation"
          result = "PASS"
          expected = "unreachable candidate fails only when ancestry guard is active"
          observedOriginalExitCode = 1
          observedPerturbedExitCode = $perturbed.exitCode
        })
    } finally {
      Remove-Item -LiteralPath $mutatedGatePath -Force -ErrorAction SilentlyContinue
    }
  } finally {
    Remove-Item -LiteralPath $unreachableFixture.Root -Recurse -Force -ErrorAction SilentlyContinue
  }

  $commitDrift = New-DriftFixture "lock-source-commit-differs" $Lock `
    -MutateLock { param($value) $value.sourceCommit = "0" * 40 }
  try {
    [void]$tests.Add((Run-ExpectedDriftFailure "lock-source-commit-differs" $commitDrift.Root $commitDrift.Lock `
        (Join-Path $selfRoot "lock-source-commit-differs") `
        "lock source commit equals approved commit"))
  } finally {
    Remove-Item -LiteralPath $commitDrift.Root -Recurse -Force -ErrorAction SilentlyContinue
  }

  $migrationDrift = New-DriftFixture "migration-set-drift" $Lock `
    -MutateLock { param($value) $value.migrationSet = @("001", "999") }
  try {
    [void]$tests.Add((Run-ExpectedDriftFailure "migration-set-drift" $migrationDrift.Root $migrationDrift.Lock `
        (Join-Path $selfRoot "migration-set-drift") `
        "approved migration set equals live schema history"))
  } finally {
    Remove-Item -LiteralPath $migrationDrift.Root -Recurse -Force -ErrorAction SilentlyContinue
  }

  $imageDrift = New-DriftFixture "backend-image-drift" $Lock `
    -MutateLock {
      param($value)
      $newDigest = "sha256:" + ("d" * 64)
      $value.images.'learning-platform'.digest = $newDigest
      $value.images.'learning-platform'.reference = "$($value.registry)/ramals-learning-platform@$newDigest"
    } `
    -MutateManifest {
      param($root, $value)
      $path = Join-Path $root "kustomization.yaml"
      $text = Get-Content -LiteralPath $path -Raw
      $new = [string]$value.images.'learning-platform'.digest
      $text = [regex]::Replace(
        $text,
        '(?ms)(- name: qualification/learning-platform:current-main.*?^\s*digest:\s*)sha256:[0-9a-fA-F]{64}',
        ('$1' + $new),
        1)
      Write-Utf8 $path $text
      $renderedPath = Join-Path $root "rendered-self-test.yaml"
      Write-Utf8 $renderedPath (Get-RenderedManifest $root)
      $value.manifest.kustomizationSha256 = Get-Hash $path
      $value.manifest.renderedManifestSha256 = Get-Hash $renderedPath
      Remove-Item -LiteralPath $renderedPath -Force
      Write-JsonEvidence (Join-Path $root "images.lock.json") $value
    }
  try {
    [void]$tests.Add((Run-ExpectedDriftFailure "backend-image-drift" $imageDrift.Root $imageDrift.Lock `
        (Join-Path $selfRoot "backend-image-drift") `
        "rendered image intent"))
  } finally {
    Remove-Item -LiteralPath $imageDrift.Root -Recurse -Force -ErrorAction SilentlyContinue
  }

  $manifestDrift = New-DriftFixture "rendered-manifest-drift" $Lock `
    -MutateManifest {
      param($root, $value)
      $path = Join-Path $root "kustomization.yaml"
      $text = Get-Content -LiteralPath $path -Raw
      Write-Utf8 $path ($text + "`n# deliberate candidate manifest drift`n")
    }
  try {
    [void]$tests.Add((Run-ExpectedDriftFailure "rendered-manifest-drift" $manifestDrift.Root $manifestDrift.Lock `
        (Join-Path $selfRoot "rendered-manifest-drift") `
        "kustomization hash equals approved lock"))
  } finally {
    Remove-Item -LiteralPath $manifestDrift.Root -Recurse -Force -ErrorAction SilentlyContinue
  }
  return @($tests)
}

$lock = $null
$result = [ordered]@{
  schema = "m2-t15.candidate-integrity.v1"
  qualification = "M2-T15.1"
  result = "FAIL"
  capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
  approvedCommit = $ApprovedCommit.ToLowerInvariant()
  approvedRef = $ApprovedRef
  checks = @()
}

try {
  $lock = Read-Lock
  $lockCommit = ([string]$lock.sourceCommit).ToLowerInvariant()
  Require-Check "lock source commit equals approved commit" ($lockCommit -eq $ApprovedCommit.ToLowerInvariant()) $lockCommit
  $approvedRefCommit = Resolve-GitCommit $ApprovedRef
  $result.approvedRefCommit = $approvedRefCommit
  $provenanceCondition = Test-CommitReachable $ApprovedCommit $approvedRefCommit
  Require-Check "approved commit is reachable from approved ref" `
    $provenanceCondition `
    ("candidate=$($ApprovedCommit.ToLowerInvariant()); approvedRef=$approvedRefCommit")
  $approvedTree = (Invoke-Checked "git" @("rev-parse", "--verify", "$ApprovedCommit^{tree}")).Trim().ToLowerInvariant()
  Require-Check "lock source tree equals approved commit tree" `
    ($approvedTree -eq ([string]$lock.sourceTree).ToLowerInvariant()) $approvedTree

  $images = @(Get-LockImages $lock)
  Require-Check "qualification lock identifies candidate" `
    (-not [string]::IsNullOrWhiteSpace([string]$lock.qualification)) ([string]$lock.qualification)
  Require-Check "qualification lock has approved migration set" `
    (@($lock.migrationSet).Count -gt 0) ((@($lock.migrationSet) -join ","))

  $renderedText = Get-RenderedManifest
  $renderedPath = Join-Path $EvidenceDirectory "rendered.yaml"
  Write-Utf8 $renderedPath $renderedText
  $renderedHash = Get-Hash $renderedPath
  $kustomizationPath = Join-Path $ManifestRoot "kustomization.yaml"
  $kustomizationHash = Get-Hash $kustomizationPath
  Require-Check "rendered manifest hash equals approved lock" `
    ($renderedHash -eq ([string]$lock.manifest.renderedManifestSha256).ToLowerInvariant()) $renderedHash
  Require-Check "kustomization hash equals approved lock" `
    ($kustomizationHash -eq ([string]$lock.manifest.kustomizationSha256).ToLowerInvariant()) $kustomizationHash
  foreach ($entry in $images) {
    Require-Check "approved image appears in rendered deployment: $($entry.name)" `
      ($renderedText.Contains($entry.reference)) $entry.reference
  }
  $mutableImageLines = @($renderedText -split "`r?`n" | Where-Object {
      $_ -match '^\s*image:\s*' -and $_ -notmatch '@sha256:[0-9a-fA-F]{64}'
    })
  Require-Check "rendered deployment contains no mutable image" `
    ($mutableImageLines.Count -eq 0) (($mutableImageLines -join "; "))

  $liveMigrations = @(Get-MigrationState)
  Assert-MigrationSet $lock $liveMigrations
  $liveImages = Assert-LiveImages $lock $images
  $environment = Get-EnvironmentRecord $liveImages.pods $liveImages.workloads
  $lockHash = Get-Hash $LockPath

  $result.candidate = [ordered]@{
    qualification = [string]$lock.qualification
    sourceCommit = $lockCommit
    sourceTree = ([string]$lock.sourceTree).ToLowerInvariant()
    lockSha256 = $lockHash
    imageDigests = @($images | ForEach-Object {
        [ordered]@{ name = $_.name; repository = $_.repository; digest = $_.digest; reference = $_.reference }
      })
    manifest = [ordered]@{
      kustomizationSha256 = $kustomizationHash
      renderedManifestSha256 = $renderedHash
    }
    migrationSet = @($lock.migrationSet)
    liveMigrationHistory = @($liveMigrations)
  }
  $result.environment = $environment
  $result.checks = @($script:Checks)
  if ($SelfTest) {
    $result.selfTests = @(Run-SelfTests $lock)
  }
  $result.result = "PASS"
  $resultPath = Join-Path $EvidenceDirectory "candidate-integrity.json"
  Write-JsonEvidence $resultPath $result
  Write-Host "Candidate integrity PASS: $ApprovedCommit"
  Write-Host "Evidence: $EvidenceDirectory"
  exit 0
} catch {
  $result.checks = @($script:Checks)
  $result.error = $_.Exception.Message
  Write-JsonEvidence (Join-Path $EvidenceDirectory "candidate-integrity.json") $result
  Write-Error $_.Exception.Message
  exit 1
}
