<#
.SYNOPSIS
Mirrors immutable GHCR application images and builds local-only infrastructure images for k3d.
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit,
  [string]$RegistryOwner = "skpandey15",
  [string]$RegistryName = "ramals-registry",
  [int]$RegistryPort = 5000,
  [int]$WaitMinutes = 20,
  [string]$EvidencePath
)

$ErrorActionPreference = "Stop"
$ErrorView = "ConciseView"
$env:NO_COLOR = "1"
$env:CLICOLOR = "0"
$env:TERM = "dumb"
if ($null -ne (Get-Variable -Name PSStyle -ErrorAction SilentlyContinue)) {
  $PSStyle.OutputRendering = "PlainText"
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repositoryRoot

function ConvertTo-PlainLogText {
  param([AllowNull()][object]$Value)
  if ($null -eq $Value) { return "" }
  return ([string]$Value -replace "`e\[[0-9;?]*[ -/]*[@-~]", "").Trim()
}

function Invoke-NativeCommand {
  param(
    [Parameter(Mandatory)][string]$Description,
    [Parameter(Mandatory)][scriptblock]$Command,
    [switch]$Quiet
  )

  $output = & $Command 2>&1
  $exitCode = $LASTEXITCODE
  $plainOutput = @($output | ForEach-Object { ConvertTo-PlainLogText $_ } | Where-Object { $_ })

  if (-not $Quiet) {
    $plainOutput | ForEach-Object { Write-Host $_ }
  }

  if ($exitCode -ne 0) {
    $detail = ($plainOutput -join " | ")
    if (-not $detail) { $detail = "no diagnostic output" }
    throw "$Description failed (exit $exitCode). $detail"
  }

  return ,$plainOutput
}

function Assert-DockerRuntimeReady {
  Write-Host "[preflight] Verifying Docker runtime..."
  $probeOutput = & docker info --format '{{.ServerVersion}}' 2>&1
  $probeExitCode = $LASTEXITCODE
  $plainOutput = @($probeOutput | ForEach-Object { ConvertTo-PlainLogText $_ } | Where-Object { $_ })
  $serverVersion = @($plainOutput | Where-Object { $_ -match '^v?\d+(\.\d+){1,3}([+-][0-9A-Za-z.-]+)?$' } | Select-Object -First 1)

  if ($probeExitCode -ne 0 -or $serverVersion.Count -ne 1) {
    $detail = ($plainOutput -join " | ")
    if (-not $detail) { $detail = "docker info returned no diagnostic output" }
    throw "Docker runtime is unavailable. $detail"
  }

  Write-Host "[preflight] Docker runtime ready: $($serverVersion[0])"
}

$applicationReleaseCommit = $Commit

foreach ($tool in @("docker", "k3d")) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required on PATH." }
}

Assert-DockerRuntimeReady

$deploymentConfigCommit = $Commit
$deploymentConfigShortCommit = $deploymentConfigCommit.Substring(0, 7)
$applicationShortCommit = $applicationReleaseCommit.Substring(0, 7)
$registryHost = "k3d-$RegistryName"

$registryJsonLines = Invoke-NativeCommand -Description "Query local k3d registries" -Quiet -Command {
  k3d registry list -o json
}
$registryJson = $registryJsonLines -join "`n"
$registries = if ($registryJson.Trim()) { $registryJson | ConvertFrom-Json } else { @() }
if (-not ($registries | Where-Object name -eq $registryHost)) {
  Write-Host "[registry] Creating local k3d registry $RegistryName..."
  Invoke-NativeCommand -Description "Create local k3d registry $RegistryName" -Command {
    k3d registry create $RegistryName --port $RegistryPort
  } | Out-Null
}

$applicationImages = @(
  @{ Component = "learning-platform"; Source = "ghcr.io/$RegistryOwner/ramals-learning-platform"; Local = "ramals-learning-platform" },
  @{ Component = "web-ui";            Source = "ghcr.io/$RegistryOwner/ramals-web-ui";            Local = "ramals-web-ui" },
  @{ Component = "ramals-ai";          Source = "ghcr.io/$RegistryOwner/ramals-ai";                 Local = "ramals-ai" }
)
$deadline = [DateTimeOffset]::UtcNow.AddMinutes($WaitMinutes)
$verified = @()
$resolved = @()

Write-Host "[images] GHCR application images for current main $applicationShortCommit"
foreach ($image in $applicationImages) {
  $sourceImage = [string]$image.Source
  if ($sourceImage -notmatch '^ghcr\.io/[a-z0-9._-]+/[a-z0-9._/-]+$') {
    throw "Invalid GHCR image for component '$($image.Component)': $sourceImage"
  }
  $sourceTag = "${sourceImage}:sha-$applicationReleaseCommit"
  while ($true) {
    docker manifest inspect $sourceTag *> $null
    if ($LASTEXITCODE -eq 0) { break }
    if ([DateTimeOffset]::UtcNow -ge $deadline) {
      throw "GHCR image did not become available within $WaitMinutes minutes: $sourceTag"
    }
    Write-Host "[images] Waiting for GitHub Actions to publish $sourceTag"
    Start-Sleep -Seconds 15
  }

  Write-Host "[images] Pulling $sourceTag"
  Invoke-NativeCommand -Description "Pull $sourceTag" -Command {
    docker pull --platform linux/amd64 $sourceTag
  } | Out-Null

  $revisionLines = Invoke-NativeCommand -Description "Inspect OCI revision for $sourceTag" -Quiet -Command {
    docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' $sourceTag
  }
  $revision = ($revisionLines -join " ").Trim()
  if ($revision -ne $applicationReleaseCommit) {
    throw "GHCR image revision '$revision' does not match current main '$applicationReleaseCommit': $sourceTag"
  }
  $repoDigestLines = Invoke-NativeCommand -Description "Resolve immutable digest for $sourceTag" -Quiet -Command {
    docker inspect --format '{{json .RepoDigests}}' $sourceTag
  }
  $repoDigests = @(($repoDigestLines -join "") | ConvertFrom-Json)
  $sourceRepoDigests = @($repoDigests | Where-Object { $_ -like "${sourceImage}@sha256:*" })
  if ($sourceRepoDigests.Count -ne 1) {
    throw "GHCR image did not resolve to one source-repository digest: $sourceTag"
  }
  $pullReference = $sourceRepoDigests[0]
  if ($pullReference -notmatch '^ghcr\.io/[a-z0-9._/-]+@(?<digest>sha256:[0-9a-f]{64})$') {
    throw "GHCR image did not resolve to an immutable digest: $sourceTag"
  }
  $sourceDigest = $Matches.digest
  $verified += [ordered]@{
    component = $image.Local
    sourceImage = $sourceImage
    sourceTag = $sourceTag
    pullReference = $pullReference
    digest = $sourceDigest
    revision = $revision
  }
}

$componentRevisions = @($verified | ForEach-Object revision | Select-Object -Unique)
if ($componentRevisions.Count -ne 1 -or $componentRevisions[0] -ne $applicationReleaseCommit) {
  throw "Application component OCI revisions are inconsistent with current main '$applicationReleaseCommit'."
}

Write-Host "[images] Mirroring verified current-main application release $applicationShortCommit"
foreach ($image in $verified) {
  $local = "localhost:${RegistryPort}/$($image.component):$applicationShortCommit"
  Invoke-NativeCommand -Description "Tag $($image.pullReference) as $local" -Quiet -Command {
    docker tag $image.pullReference $local
  } | Out-Null
  Invoke-NativeCommand -Description "Mirror $($image.pullReference) into local registry" -Command {
    docker push $local
  } | Out-Null
  $resolved += [ordered]@{
    component = $image.component
    sourceImage = $image.sourceImage
    sourceTag = $image.sourceTag
    sourceDigest = $image.digest
    ociRevision = $image.revision
    localMirroredImage = $local
  }
}

Write-Host "[images] Building local infrastructure images"
$infrastructureImages = @(
  @{ Repo = "ramals-postgres"; File = "infrastructure/docker/postgres-init/Dockerfile" },
  @{ Repo = "ramals-keycloak"; File = "infrastructure/docker/keycloak/Dockerfile" },
  @{ Repo = "ramals-sms-sink"; File = "infrastructure/docker/sms-sink/Dockerfile" }
)
foreach ($image in $infrastructureImages) {
  $local = "localhost:${RegistryPort}/$($image.Repo):$deploymentConfigShortCommit"
  Write-Host "[images] Building $($image.Repo) locally"
  Invoke-NativeCommand -Description "Build $($image.Repo)" -Command {
    docker build -t $local -f $image.File .
  } | Out-Null
  Invoke-NativeCommand -Description "Push $($image.Repo)" -Command {
    docker push $local
  } | Out-Null
}

if ($EvidencePath) {
  $parent = Split-Path $EvidencePath -Parent
  if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  [ordered]@{
    deploymentConfigCommit = $deploymentConfigCommit
    applicationReleaseCommit = $applicationReleaseCommit
    selectionPolicy = "current-main-commit"
    localRegistry = "localhost:$RegistryPort"
    applicationImages = $resolved
    preparedAt = [DateTimeOffset]::UtcNow.ToString("O")
  } | ConvertTo-Json -Depth 6 | Out-File $EvidencePath -Encoding utf8
}

Write-Host "[images] Prepared three immutable GHCR application images and three local infrastructure images."
