[CmdletBinding()]
param(
  [string]$Registry = "localhost:5111",
  [string]$Tag = ""
)

$ErrorActionPreference = "Stop"
$scriptRoot = (Resolve-Path $PSScriptRoot).Path
$repositoryRoot = (Resolve-Path (Join-Path $scriptRoot "..\..\..")).Path
Set-Location $repositoryRoot

function Invoke-Checked {
  param(
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][string[]]$Arguments
  )

  & $Command @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$Command failed with exit code $LASTEXITCODE"
  }
}

if ([string]::IsNullOrWhiteSpace($Tag)) {
  $Tag = "t15-" + ((git rev-parse --verify HEAD).Trim())
}
if ($Tag -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$') {
  throw "Tag contains unsupported characters"
}

$images = @(
  [pscustomobject]@{ Repository = "ramals-learning-platform"; Dockerfile = "learning-platform/Dockerfile" },
  [pscustomobject]@{ Repository = "ramals-ai"; Dockerfile = "ramals-ai/Dockerfile" },
  [pscustomobject]@{ Repository = "ramals-web-ui"; Dockerfile = "web-ui/Dockerfile" },
  [pscustomobject]@{ Repository = "ramals-postgres"; Dockerfile = "infrastructure/docker/postgres-init/Dockerfile" },
  [pscustomobject]@{ Repository = "ramals-keycloak"; Dockerfile = "infrastructure/docker/keycloak/Dockerfile" }
)

foreach ($image in $images) {
  $reference = "$Registry/$($image.Repository):$Tag"
  Write-Host "Building $reference from $($image.Dockerfile)"
  Invoke-Checked "docker" @("build", "--file", $image.Dockerfile, "--tag", $reference, ".")
  Write-Host "Pushing $reference"
  Invoke-Checked "docker" @("push", $reference)

  # Docker prints the pushed manifest digest. This script deliberately does not edit images.lock.json:
  # changing the qualification artifact set is a reviewable decision, not a side effect of a build.
  $repoDigests = & docker image inspect $reference --format "{{json .RepoDigests}}"
  if ($LASTEXITCODE -ne 0) {
    throw "could not inspect the pushed digest for $reference"
  }
  Write-Host "Published digest metadata: $repoDigests"
}

Write-Host "Review the pushed manifest digests before updating images.lock.json and kustomization.yaml"
