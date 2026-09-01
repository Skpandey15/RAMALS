[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw $Message }
}

function Assert-PowerShellParses([string]$Path) {
  $tokens = $null
  $errors = $null
  [void][Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
  if ($errors.Count -gt 0) { throw "PowerShell parse failed for ${Path}: $($errors -join '; ')" }
}

$deployScript = Join-Path $repositoryRoot "deploy\jenkins\deploy-main.ps1"
$prepareImagesScript = Join-Path $repositoryRoot "deploy\jenkins\prepare-ghcr-images.ps1"
$installScript = Join-Path $repositoryRoot "deploy\jenkins\install-local.ps1"
$jobConfigPath = Join-Path $repositoryRoot "deploy\jenkins\job-config.xml"
$jenkinsfilePath = Join-Path $repositoryRoot "Jenkinsfile"
$desiredVersionPath = Join-Path $repositoryRoot "deploy\desired-version.json"
$releaseWorkflowPath = Join-Path $repositoryRoot ".github\workflows\release.yml"
$bootstrapPath = Join-Path $repositoryRoot "deploy\k8s\dev\bootstrap.ps1"
$bootstrapCorePath = Join-Path $repositoryRoot "deploy\k8s\dev\bootstrap-core.ps1"
Assert-PowerShellParses $deployScript
Assert-PowerShellParses $prepareImagesScript
Assert-PowerShellParses $installScript
Assert-PowerShellParses $bootstrapPath
Assert-PowerShellParses $bootstrapCorePath
Assert-PowerShellParses $PSCommandPath

[xml]$jobConfig = Get-Content $jobConfigPath -Raw
$pollTrigger = $jobConfig.'flow-definition'.triggers.'hudson.triggers.SCMTrigger'
Assert-True ($null -ne $pollTrigger) "job-config.xml must install SCM polling before the first build."
Assert-True ($pollTrigger.spec -eq 'H/2 * * * *') "Unexpected SCM polling schedule."
Assert-True ($jobConfig.'flow-definition'.definition.scriptPath -eq 'Jenkinsfile') `
  "The job must load the repository Jenkinsfile."

$jenkinsfile = Get-Content $jenkinsfilePath -Raw
foreach ($invariant in @(
    'disableConcurrentBuilds',
    "pollSCM('H/2 * * * *')",
    "stage('Approve local k3d DEV')",
    'Do you want to deploy to the local/dev k3d environment?',
    "submitter: 'ramals-admin'",
    'deploy\\jenkins\\deploy-main.ps1 -ValidateOnly',
    'deploy\\jenkins\\deploy-main.ps1')) {
  Assert-True ($jenkinsfile.Contains($invariant)) "Jenkinsfile invariant missing: $invariant"
}
Assert-True (-not $jenkinsfile.Contains('timestamps()')) `
  "Jenkinsfile must not depend on the optional Timestamper plugin to compile."

$installer = Get-Content $installScript -Raw
$deploymentBoundary = Get-Content $deployScript -Raw
$imagePreparation = Get-Content $prepareImagesScript -Raw
$releaseWorkflow = Get-Content $releaseWorkflowPath -Raw
Assert-True ($deploymentBoundary.Contains('prepare-ghcr-images.ps1')) `
  "Jenkins deployment must prepare immutable GHCR application images."
Assert-True ($deploymentBoundary -match 'bootstrap\.ps1[\s\S]*-SkipBuild') `
  "Jenkins must not rebuild application images after mirroring GHCR."
Assert-True ($imagePreparation.Contains('deploy\desired-version.json')) `
  "Jenkins must select application images from the approved desired-version manifest."
Assert-True ($imagePreparation -notmatch '\$applicationReleaseCommit\s+-ne\s+\$Commit') `
  "Deployment configuration and application release commits must remain independent."
Assert-True ($imagePreparation -match '\$source\s*=\s*"\$\{sourceImage\}@\$\{sourceDigest\}"') `
  "GHCR application images must be pulled by approved digest."
Assert-True ($imagePreparation -notmatch 'sourceTag|:sha-\$Commit') `
  "A mutable SHA tag must not be the GHCR deployment identity."
Assert-True ($imagePreparation.Contains('$applicationShortCommit = $applicationReleaseCommit.Substring(0, 7)')) `
  "Local application tags must derive from the approved application release commit."
Assert-True ($imagePreparation.Contains('$deploymentConfigShortCommit = $deploymentConfigCommit.Substring(0, 7)')) `
  "Local infrastructure tags must derive from the deployment configuration commit."
Assert-True ($imagePreparation -match '\$revision\s+-ne\s+\$applicationReleaseCommit') `
  "Every OCI revision must equal the approved application release commit."
Assert-True ($imagePreparation.Contains('$componentRevisions.Count -ne 1')) `
  "The three application components must resolve to one OCI revision."
foreach ($applicationDockerfile in @(
    'learning-platform/Dockerfile', 'web-ui/Dockerfile', 'ramals-ai/Dockerfile')) {
  Assert-True (-not $imagePreparation.Contains($applicationDockerfile)) `
    "Jenkins must not build application image $applicationDockerfile."
}
foreach ($evidenceInvariant in @(
    'deploymentConfigCommit', 'applicationReleaseCommit', 'sourceImage = $image.sourceImage',
    'sourceDigest = $image.digest', 'ociRevision = $image.revision',
    'localMirroredImage = $local')) {
  Assert-True ($imagePreparation.Contains($evidenceInvariant)) `
    "GHCR deployment evidence invariant missing: $evidenceInvariant"
}
Assert-True ($releaseWorkflow.Contains('VITE_KEYCLOAK_URL=http://keycloak.localhost:8080')) `
  "The released DEV web UI must use the local k3d Keycloak ingress URL."
Assert-True ($releaseWorkflow -match 'VITE_API_BASE_URL=\s') `
  "The released DEV web UI must use same-origin API routing."

$desiredVersion = Get-Content $desiredVersionPath -Raw | ConvertFrom-Json
Assert-True ($desiredVersion.manifest_version -eq 1) "Unexpected desired-version manifest version."
Assert-True ([string]$desiredVersion.release.commit -match '^[0-9a-f]{40}$') `
  "The approved release commit must be a full SHA."
foreach ($component in @('learning-platform', 'web-ui', 'ramals-ai')) {
  $approved = $desiredVersion.components.$component
  Assert-True ([string]$approved.image -match '^ghcr\.io/[a-z0-9._-]+/[a-z0-9._/-]+$') `
    "Invalid approved GHCR image for $component."
  Assert-True ([string]$approved.digest -match '^sha256:[0-9a-f]{64}$') `
    "Invalid approved digest for $component."
}

$bootstrapCore = Get-Content $bootstrapCorePath -Raw
Assert-True ($deploymentBoundary -match '-ApplicationImageTag\s+\$applicationImageTag') `
  "Jenkins must pass the application release tag to bootstrap."
Assert-True ($deploymentBoundary -match '-InfrastructureImageTag\s+\$infrastructureImageTag') `
  "Jenkins must pass the deployment configuration tag to bootstrap."
Assert-True ($bootstrapCore.Contains('Kind = "application"')) `
  "Bootstrap must classify application images separately from infrastructure."
Assert-True ($bootstrapCore.Contains('$applicationReleaseImageTag')) `
  "Bootstrap must render application images with the application release tag."
Assert-True ($bootstrapCore.Contains('$deploymentConfigImageTag')) `
  "Bootstrap must render infrastructure images with the deployment configuration tag."
Assert-True ($installer -notmatch '/latest/') "Installer downloads must not use mutable latest URLs."
Assert-True ($installer -match '\$jenkinsVersion\s*=') "Jenkins must be explicitly version-pinned."
Assert-True ($installer -match '\$temurinVersion\s*=') "Temurin must be explicitly version-pinned."
Assert-True ($installer -match '\$jenkinsSha256\s*=\s*"[A-F0-9]{64}"') "Jenkins SHA256 must be pinned."
Assert-True ($installer -match '\$temurinSha256\s*=\s*"[A-F0-9]{64}"') "Temurin SHA256 must be pinned."
Assert-True ($installer -match 'install-plugin[\s\S]*\bblueocean\b') `
  "Installer must provide the requested Blue Ocean pipeline UI."

# Persistent local Jenkins credential contract.
Assert-True ($installer.Contains('[Security.Cryptography.RandomNumberGenerator]::Create()')) `
  "Password generation must be compatible with Windows PowerShell 5.1."
Assert-True ($installer.Contains('Jenkins recovery credential is missing or empty')) `
  "The installer must fail closed when the protected recovery credential is empty."
Assert-True ($installer.Contains("if (!passwordFile.exists())")) `
  "The Jenkins bootstrap must fail closed when the recovery credential file is missing."
Assert-True ($installer.Contains("if (adminPassword.isEmpty())")) `
  "The Jenkins bootstrap must fail closed when the recovery credential is empty."
Assert-True ($installer.Contains("User.getById('ramals-admin', true)")) `
  "The Jenkins bootstrap must resolve or create ramals-admin deterministically."
Assert-True ($installer.Contains('HudsonPrivateSecurityRealm.Details.fromPlainPassword(adminPassword)')) `
  "The protected recovery credential must be reconciled into Jenkins on every startup."
Assert-True ($installer -notmatch 'hasPrivateRealmCredentials') `
  "Credential synchronization must not be conditional on an existing Jenkins password property."
Assert-True ($installer.Contains('Get-NetTCPConnection -LocalPort $Port -State Listen')) `
  "Restart must recover from a stale PID file by inspecting the actual loopback listener."
Assert-True ($installer.Contains('Refusing to stop unexpected PID')) `
  "Restart must never stop a process outside the verified RAMALS Jenkins runtime."
Assert-True ($installer.Contains('Wait-PortReleased')) `
  "Restart must wait for the Jenkins port to be released before starting a replacement controller."

# Exercise the trusted-source boundary in disposable repositories without Docker or k3d.
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) "ramals-jenkins-validation-$([guid]::NewGuid())"
$remote = Join-Path $temporaryRoot "origin.git"
$seed = Join-Path $temporaryRoot "seed"
$checkout = Join-Path $temporaryRoot "checkout"
try {
  New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
  git init --bare --initial-branch=main $remote | Out-Null
  git init --initial-branch=main $seed | Out-Null
  git -C $seed config user.email ci-test@ramals.invalid
  git -C $seed config user.name RAMALS-CI-Test
  New-Item -ItemType Directory -Path (Join-Path $seed "deploy\jenkins") -Force | Out-Null
  Copy-Item $deployScript (Join-Path $seed "deploy\jenkins\deploy-main.ps1")
  "baseline" | Set-Content (Join-Path $seed "README.md")
  git -C $seed add .
  git -C $seed commit -m baseline --quiet
  git -C $seed remote add origin $remote
  git -C $seed push -u origin main --quiet
  git clone --quiet --branch main $remote $checkout

  & pwsh -NoProfile -NonInteractive -File (Join-Path $checkout "deploy\jenkins\deploy-main.ps1") `
    -ValidateOnly -ExpectedRepository $remote | Out-Null
  Assert-True ($LASTEXITCODE -eq 0) "Trusted current main validation should succeed."

  & pwsh -NoProfile -NonInteractive -File (Join-Path $checkout "deploy\jenkins\deploy-main.ps1") `
    -ValidateOnly -ExpectedRepository "https://example.invalid/wrong.git" *> $null
  Assert-True ($LASTEXITCODE -ne 0) "An unexpected origin must be rejected."

  "new main" | Set-Content (Join-Path $seed "README.md")
  git -C $seed add README.md
  git -C $seed commit -m newer-main --quiet
  git -C $seed push origin main --quiet
  & pwsh -NoProfile -NonInteractive -File (Join-Path $checkout "deploy\jenkins\deploy-main.ps1") `
    -ValidateOnly -ExpectedRepository $remote *> $null
  Assert-True ($LASTEXITCODE -ne 0) "A checkout behind origin/main must be rejected."
} finally {
  if (Test-Path $temporaryRoot) { Remove-Item -LiteralPath $temporaryRoot -Recurse -Force }
}

Write-Host "Jenkins/CD validation passed."
exit 0
