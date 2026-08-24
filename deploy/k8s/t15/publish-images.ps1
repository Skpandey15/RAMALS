[CmdletBinding()]
param(
  [string]$Commit = "",
  [string]$DockerRegistry = "localhost:5111",
  [string]$KubernetesRegistry = "k3d-ramals-t15-registry:5000",
  [string]$Tag = "",
  [switch]$UpdateLock
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

if ([string]::IsNullOrWhiteSpace($Commit)) {
  $Commit = (git rev-parse --verify origin/main).Trim()
}
$Commit = (Invoke-Checked "git" @("rev-parse", "--verify", "$Commit^{commit}")).Trim().ToLowerInvariant()
if ($Commit -notmatch '^[0-9a-f]{40}$') {
  throw "Commit must resolve to a full 40-character Git commit"
}
$sourceTree = (Invoke-Checked "git" @("rev-parse", "--verify", "$Commit^{tree}")).Trim().ToLowerInvariant()

if ([string]::IsNullOrWhiteSpace($Tag)) {
  $Tag = "t15-" + $Commit
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

$worktree = Join-Path ([System.IO.Path]::GetTempPath()) ("ramals-t15-source-" + [guid]::NewGuid().ToString("N"))
$metadataRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ramals-t15-build-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $metadataRoot -Force | Out-Null
$built = [System.Collections.Generic.List[object]]::new()

try {
  Write-Host "Materializing exact source commit $Commit ($sourceTree)"
  Invoke-Checked "git" @("worktree", "add", "--detach", $worktree, $Commit) | Out-Null

  foreach ($image in $images) {
    $dockerReference = "$DockerRegistry/$($image.Repository):$Tag"
    $metadataPath = Join-Path $metadataRoot ($image.Repository + ".json")
    Write-Host "Building and pushing $dockerReference from $($image.Dockerfile)"
    Invoke-Checked "docker" @(
      "buildx", "build",
      "--platform", "linux/amd64",
      "--file", (Join-Path $worktree $image.Dockerfile),
      "--tag", $dockerReference,
      "--label", "org.opencontainers.image.revision=$Commit",
      "--label", "org.opencontainers.image.source.tree=$sourceTree",
      "--provenance=false",
      "--sbom=false",
      "--push",
      "--metadata-file", $metadataPath,
      $worktree
    ) | Out-Null
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $digest = [string]$metadata."containerimage.digest"
    if ($digest -notmatch '^sha256:[0-9a-fA-F]{64}$') {
      throw "buildx did not return an immutable digest for $($image.Repository): $digest"
    }
    $digest = $digest.ToLowerInvariant()
    [void]$built.Add([pscustomobject]@{
        name = switch ($image.Repository) {
          "ramals-learning-platform" { "learning-platform" }
          "ramals-ai" { "ramals-ai" }
          "ramals-web-ui" { "web-ui" }
          "ramals-postgres" { "postgres" }
          "ramals-keycloak" { "keycloak" }
        }
        repository = $image.Repository
        digest = $digest
        dockerReference = $dockerReference
        reference = "$KubernetesRegistry/$($image.Repository)@$digest"
      })
    Write-Host "  $($image.Repository) -> $digest"
  }

  $migrationSet = 1..34 | ForEach-Object { "{0:D3}" -f $_ }
  $lock = [ordered]@{
    '$comment' = "M2-T15.1 qualification candidate artifacts built from the exact approved main commit. All deployment references are immutable digests."
    qualification = "m2-t15.1"
    sourceCommit = $Commit
    sourceTree = $sourceTree
    registry = $KubernetesRegistry
    migrationSet = @($migrationSet)
    manifest = [ordered]@{
      kustomizationSha256 = ""
      renderedManifestSha256 = ""
    }
    supportingImages = [ordered]@{}
    images = [ordered]@{}
  }
  # Busybox is the fixed qualification init-container helper. It is intentionally not rebuilt from
  # this repository, but it remains part of the approved image graph and is checked by the live gate.
  $lock.supportingImages.busybox = [ordered]@{
    repository = "ramals-t15-busybox"
    digest = "sha256:b7f3d86d6e84fc17718c48bcde1450807faa2d56704205c697b4bd5df7b9e29f"
    reference = "$KubernetesRegistry/ramals-t15-busybox@sha256:b7f3d86d6e84fc17718c48bcde1450807faa2d56704205c697b4bd5df7b9e29f"
  }
  foreach ($entry in $built) {
    $value = [ordered]@{
      repository = $entry.repository
      digest = $entry.digest
      reference = $entry.reference
    }
    if ($entry.name -in @("learning-platform", "ramals-ai", "web-ui")) {
      $lock.images[$entry.name] = $value
    } else {
      $lock.supportingImages[$entry.name] = $value
    }
  }

  $lockPath = Join-Path $repositoryRoot "deploy/k8s/t15/images.lock.json"
  if ($UpdateLock) {
    $kustomizationPath = Join-Path $repositoryRoot "deploy/k8s/t15/kustomization.yaml"
    $kustomization = Get-Content -LiteralPath $kustomizationPath -Raw
    foreach ($entry in $built) {
      $pattern = "(?m)^(\s*digest:\s*)sha256:[0-9a-fA-F]{64}(\s*)$"
      $match = [regex]::Match($kustomization, "(?m)^\s*digest:\s*" + [regex]::Escape(([string]$entry.digest)) + "\s*$")
      $repositoryPattern = "(?ms)(- name: qualification/" + [regex]::Escape(([string]$entry.name)) + ":current-main.*?digest:) sha256:[0-9a-fA-F]{64}"
      if (-not [regex]::IsMatch($kustomization, $repositoryPattern)) {
        throw "kustomization has no digest transformer for $($entry.name)"
      }
      $kustomization = [regex]::Replace($kustomization, $repositoryPattern, ('$1 ' + $entry.digest), 1)
    }
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($kustomizationPath, $kustomization, $utf8)

    $renderedPath = Join-Path $metadataRoot "rendered.yaml"
    $rendered = & kubectl kustomize (Join-Path $repositoryRoot "deploy/k8s/t15") 2>&1
    if ($LASTEXITCODE -ne 0) {
      throw "kubectl kustomize failed while preparing the lock: $($rendered -join "`n")"
    }
    [System.IO.File]::WriteAllText($renderedPath, (($rendered -join [Environment]::NewLine) + [Environment]::NewLine), $utf8)
    $lock.manifest.kustomizationSha256 = ((Get-FileHash -LiteralPath $kustomizationPath -Algorithm SHA256).Hash).ToLowerInvariant()
    $lock.manifest.renderedManifestSha256 = ((Get-FileHash -LiteralPath $renderedPath -Algorithm SHA256).Hash).ToLowerInvariant()
    [System.IO.File]::WriteAllText($lockPath, (($lock | ConvertTo-Json -Depth 30) + [Environment]::NewLine), $utf8)
    Write-Host "Updated $lockPath and kustomization.yaml for the exact candidate"
  } else {
    $outputPath = Join-Path $metadataRoot "images.lock.json"
    $lock | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $outputPath -Encoding utf8
    Write-Host "Candidate lock written to $outputPath"
    Write-Host "Re-run with -UpdateLock only after reviewing the digest set"
  }
} finally {
  if (Test-Path -LiteralPath $worktree) {
    & git worktree remove --force $worktree 2>$null
  }
  if (Test-Path -LiteralPath $metadataRoot) {
    Remove-Item -LiteralPath $metadataRoot -Recurse -Force -ErrorAction SilentlyContinue
  }
}

Write-Host "Qualification candidate source: $Commit ($sourceTree)"
