<#
.SYNOPSIS
Mirrors immutable GHCR application images and builds local-only infrastructure images for k3d.
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit,
  [string]$DesiredVersionPath,
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
if (-not $DesiredVersionPath) {
  $DesiredVersionPath = Join-Path $repositoryRoot "deploy\desired-version.json"
}

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

$desiredVersion = Get-Content $DesiredVersionPath -Raw | ConvertFrom-Json
if ($desiredVersion.manifest_version -ne 1 -or $desiredVersion.environment -ne "dev") {
  throw "Unsupported desired-version manifest: $DesiredVersionPath"
}
$applicationReleaseCommit = [string]$desiredVersion.release.commit
if ($applicationReleaseCommit -notmatch '^[0-9a-f]{40}$') {
  throw "desired-version release.commit must be a full lowercase Git commit SHA."
}

foreach ($tool in @("docker", "k3d")) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required on PATH." }
}

Write-Host "[preflight] Verifying Docker runtime..."
$dockerVersion = Invoke-NativeCommand -Description "Docker runtime probe" -Quiet -Command {
  docker info --format '{{.ServerVersion}}'
}
Write-Host "[preflight] Docker runtime ready: $(($dockerVersion -join ' ').Trim())"

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
  @{ Component = "learning-platform"; Local = "ramals-learning-platform" },
  @{ Component = "web-ui";            Local = "ramals-web-ui" },
  @{ Component = "ramals-ai";          Local = "ramals-ai" }
)
$deadline = [DateTimeOffset]::UtcNow.AddMinutes($WaitMinutes)
$verified = @()
$resolved = @()

Write-Host "[images] Approved digest-pinned GHCR application images"
foreach ($image in $applicationImages) {
  $approved = $desiredVersion.components.($image.Component)
  $sourceImage = [string]$approved.image
  $sourceDigest = [string]$approved.digest
  if ($sourceImage -notmatch '^ghcr\.io/[a-z0-9._-]+/[a-z0-9._/-]+$') {
    throw "Invalid GHCR image for component '$($image.Component)': $sourceImage"
  }
  if ($sourceDigest -notmatch '^sha256:[0-9a-f]{64}$') {
    throw "Invalid digest for component '$($image.Component)': $sourceDigest"
  }
  $source = "${sourceImage}@${sourceDigest}"
  while ($true) {
    docker manifest inspect $source *> $null
    if ($LASTEXITCODE -eq 0) { break }
    if ([DateTimeOffset]::UtcNow -ge $deadline) {
      throw "GHCR image did not become available within $WaitMinutes minutes: $source"
    }
    Write-Host "[images] Waiting for GitHub Actions to publish $source"
    Start-Sleep -Seconds 15
  }

  Write-Host "[images] Pulling $source"
  Invoke-NativeCommand -Description "Pull $source" -Command {
    docker pull --platform linux/amd64 $source
  } | Out-Null

  $revisionLines = Invoke-NativeCommand -Description "Inspect OCI revision for $source" -Quiet -Command {
    docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' $source
  }
  $revision = ($revisionLines -join " ").Trim()
  if ($revision -ne $applicationReleaseCommit) {
    throw "GHCR image revision '$revision' does not match approved application release commit '$applicationReleaseCommit': $source"
  }
  $verified += [ordered]@{
    component = $image.Local
    sourceImage = $sourceImage
    pullReference = $source
    digest = $sourceDigest
    revision = $revision
  }
}

$componentRevisions = @($verified | ForEach-Object revision | Select-Object -Unique)
if ($componentRevisions.Count -ne 1 -or $componentRevisions[0] -ne $applicationReleaseCommit) {
  throw "Application component OCI revisions are inconsistent with approved release '$applicationReleaseCommit'."
}

Write-Host "[images] Mirroring verified application release $applicationShortCommit"
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
    sourceDigest = $image.digest
    ociRevision = $image.revision
    localMirroredImage = $local
  }
}

Write-Host "[images] Building local infrastructure images"
$infrastructureImages = @(
  @{ Repo = "ramals-postgres"; File = "infrastructure/docker/postgres-init/Dockerfile" },
  @{ Repo = "ramals-keycloak"; File = "infrastructure/docker/keycloak/Dockerfile" }
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
    desiredVersion = (Resolve-Path $DesiredVersionPath).Path
    localRegistry = "localhost:$RegistryPort"
    applicationImages = $resolved
    preparedAt = [DateTimeOffset]::UtcNow.ToString("O")
  } | ConvertTo-Json -Depth 6 | Out-File $EvidencePath -Encoding utf8
}

Write-Host "[images] Prepared three immutable GHCR application images and two local infrastructure images."
