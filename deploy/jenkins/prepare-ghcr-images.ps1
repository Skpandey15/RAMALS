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
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repositoryRoot
if (-not $DesiredVersionPath) {
  $DesiredVersionPath = Join-Path $repositoryRoot "deploy\desired-version.json"
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
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker is not responding." }

$deploymentConfigCommit = $Commit
$deploymentConfigShortCommit = $deploymentConfigCommit.Substring(0, 7)
$applicationShortCommit = $applicationReleaseCommit.Substring(0, 7)
$registryHost = "k3d-$RegistryName"
if (-not (k3d registry list -o json | ConvertFrom-Json | Where-Object name -eq $registryHost)) {
  k3d registry create $RegistryName --port $RegistryPort | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Could not create local k3d registry $RegistryName." }
}

$applicationImages = @(
  @{ Component = "learning-platform"; Local = "ramals-learning-platform" },
  @{ Component = "web-ui";            Local = "ramals-web-ui" },
  @{ Component = "ramals-ai";          Local = "ramals-ai" }
)
$deadline = [DateTimeOffset]::UtcNow.AddMinutes($WaitMinutes)
$verified = @()
$resolved = @()

Write-Host "== approved digest-pinned GHCR application images ==" -ForegroundColor Cyan
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
    Write-Host "  waiting for GitHub Actions to publish $source"
    Start-Sleep -Seconds 15
  }

  Write-Host "  pulling $source"
  docker pull --platform linux/amd64 $source | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Could not pull $source." }
  $revision = (docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' $source).Trim()
  if ($LASTEXITCODE -ne 0 -or $revision -ne $applicationReleaseCommit) {
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

Write-Host "== mirror verified application release $applicationShortCommit ==" -ForegroundColor Cyan
foreach ($image in $verified) {
  $local = "localhost:${RegistryPort}/$($image.component):$applicationShortCommit"
  docker tag $image.pullReference $local
  if ($LASTEXITCODE -ne 0) { throw "Could not tag $($image.pullReference) as $local." }
  docker push $local | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Could not mirror $($image.pullReference) into the local registry." }
  $resolved += [ordered]@{
    component = $image.component
    sourceImage = $image.sourceImage
    sourceDigest = $image.digest
    ociRevision = $image.revision
    localMirroredImage = $local
  }
}

Write-Host "== local infrastructure images ==" -ForegroundColor Cyan
$infrastructureImages = @(
  @{ Repo = "ramals-postgres"; File = "infrastructure/docker/postgres-init/Dockerfile" },
  @{ Repo = "ramals-keycloak"; File = "infrastructure/docker/keycloak/Dockerfile" }
)
foreach ($image in $infrastructureImages) {
  $local = "localhost:${RegistryPort}/$($image.Repo):$deploymentConfigShortCommit"
  Write-Host "  building $($image.Repo) locally"
  docker build -t $local -f $image.File . | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Build failed: $($image.Repo)." }
  docker push $local | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Push failed: $($image.Repo)." }
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

Write-Host "Prepared three immutable GHCR application images and two local infrastructure images." -ForegroundColor Green
