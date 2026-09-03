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
$cdStateScript = Join-Path $repositoryRoot "deploy\jenkins\cd-state.ps1"
$branchDeployScript = Join-Path $repositoryRoot "deploy\jenkins\deploy-branch.ps1"
$branchJobConfigPath = Join-Path $repositoryRoot "deploy\jenkins\job-config-branch.xml"
$branchPipelinePath = Join-Path $repositoryRoot "Jenkinsfile.branch"
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

$installer = Get-Content $installScript -Raw
$deploymentBoundary = Get-Content $deployScript -Raw
$imagePreparation = Get-Content $prepareImagesScript -Raw
$releaseWorkflow = Get-Content $releaseWorkflowPath -Raw
Assert-True ($deploymentBoundary.Contains('prepare-ghcr-images.ps1')) `
  "Jenkins deployment must prepare immutable GHCR application images."

# The rollback path only runs when a deployment has already failed, so nothing exercises it during
# a normal release and it can be removed or bypassed without anybody noticing until the day it is
# needed. These assert the sequence stays wired: capture before applying, restore on failure, and
# hold the version that failed so polling cannot redeploy it every two minutes.
Assert-True ($deploymentBoundary.Contains('Get-RamalsWorkloadImages')) `
  "The deployment must capture the running workloads before it changes them."
Assert-True ($deploymentBoundary -match 'Get-RamalsWorkloadImages[\s\S]*Bootstrapping local k3d DEV') `
  "Known-good workloads must be captured BEFORE the bootstrap, or there is nothing to roll back to."
Assert-True ($deploymentBoundary.Contains('Restore-RamalsWorkloadImages')) `
  "A failed deployment must roll back to the last known-good workloads."
Assert-True ($deploymentBoundary.Contains('Add-RamalsHeldRelease')) `
  "A rolled-back version must be held so SCM polling cannot redeploy it."
Assert-True ($deploymentBoundary.Contains('Test-RamalsReleaseHeld')) `
  "A held version must be refused before it is deployed again."
Assert-True ($deploymentBoundary -match "known_good_commit'\]\s*=\s*\`$head[\s\S]*Running smoke suite" -or
             $deploymentBoundary -match "Running smoke suite[\s\S]*known_good_commit'\]\s*=\s*\`$head") `
  "Only a release that passed its smoke suite may become the known-good rollback target."
Assert-True ($jenkinsfile.Contains('FORCE_HELD_RELEASE')) `
  "Overriding a held release must be an explicit, human decision in the job."

# --- the branch deployment path ------------------------------------------------------------------
#
# A second job now writes to the same environment. What keeps that from damaging the release path is
# that a branch is never recorded as a rollback target -- so the last healthy main release stays the
# thing the environment can be returned to, however many branches are tried in between. That is one
# absent assignment in a file nobody reads on a good day, which is exactly the kind of property that
# needs asserting rather than remembering.
$branchBoundary = Get-Content $branchDeployScript -Raw
$branchPipeline = Get-Content $branchPipelinePath -Raw
Assert-PowerShellParses $branchDeployScript
Assert-True (-not ($branchBoundary -match "known_good_commit'\]\s*=")) `
  "A branch deployment must never become the known-good rollback target."
Assert-True (-not ($branchBoundary -match "known_good_images'\]\s*=")) `
  "A branch deployment must never overwrite the known-good rollback images."
Assert-True ($branchBoundary.Contains("'BRANCH_DEPLOYED'")) `
  "A branch deployment must record itself as BRANCH_DEPLOYED, never HEALTHY."
Assert-True ($branchBoundary.Contains('Refusing deployment from unexpected origin')) `
  "The branch path may relax which ref is deployed, never which repository it comes from."
Assert-True ($branchBoundary.Contains('RestoreKnownGood')) `
  "The branch path must offer a way back to the last known-good main release."
Assert-True ($branchPipeline -match "scriptPath|refs/heads/main") `
  "The branch pipeline must take its deployment scripts from main."
# A branch supplying its own pipeline script would make the origin check, the approval, and the
# no-known-good rule decorative: the branch would be reviewing itself.
Assert-True (-not ($branchPipeline -match 'branches:\s*\[\[name:\s*params\.')) `
  "The branch pipeline must not check out its own script from the branch being deployed."
Assert-True ($branchPipeline.Contains("submitter: 'ramals-admin'")) `
  "Replacing the dev environment must stay behind the same human gate as a release."
[xml]$branchJobConfig = Get-Content $branchJobConfigPath -Raw
Assert-True ($branchJobConfig.'flow-definition'.definition.scriptPath -eq 'Jenkinsfile.branch') `
  "The branch job must load Jenkinsfile.branch."
Assert-True ($null -eq $branchJobConfig.'flow-definition'.triggers.'hudson.triggers.SCMTrigger') `
  "The branch job must not poll: a scratch deployment happens because somebody asked for it."
$branchParameters = $branchJobConfig.'flow-definition'.properties.
  'hudson.model.ParametersDefinitionProperty'.parameterDefinitions
Assert-True ($branchParameters.'hudson.model.StringParameterDefinition'.name -eq 'BRANCH') `
  "The branch job must take the branch as a build parameter."
Assert-True ($branchParameters.'hudson.model.BooleanParameterDefinition'.name -eq 'RESTORE_KNOWN_GOOD') `
  "The branch job must offer the restore switch as a build parameter."
Assert-True ($deploymentBoundary -match 'bootstrap\.ps1[\s\S]*-SkipBuild') `
  "Jenkins must not rebuild application images after mirroring GHCR."
Assert-True ($deploymentBoundary -match '\$applicationReleaseCommit\s*=\s*\$head') `
  "Local/dev Jenkins must deploy the exact current main commit."
Assert-True ($imagePreparation -match '\$sourceTag\s*=\s*"\$\{sourceImage\}:sha-\$applicationReleaseCommit"') `
  "Jenkins must select the GHCR image produced for the exact main commit."
Assert-True ($imagePreparation -match '\$pullReference\s+-notmatch.*sha256') `
  "The selected commit image must resolve to an immutable digest."
Assert-True ($imagePreparation.Contains('$applicationShortCommit = $applicationReleaseCommit.Substring(0, 7)')) `
  "Local application tags must derive from the current main application commit."
Assert-True ($imagePreparation.Contains('$deploymentConfigShortCommit = $deploymentConfigCommit.Substring(0, 7)')) `
  "Local infrastructure tags must derive from the deployment configuration commit."
Assert-True ($imagePreparation -match '\$revision\s+-ne\s+\$applicationReleaseCommit') `
  "Every OCI revision must equal the current main application commit."
Assert-True ($imagePreparation.Contains('$componentRevisions.Count -ne 1')) `
  "The three application components must resolve to one OCI revision."
foreach ($applicationDockerfile in @(
    'learning-platform/Dockerfile', 'web-ui/Dockerfile', 'ramals-ai/Dockerfile')) {
  Assert-True (-not $imagePreparation.Contains($applicationDockerfile)) `
    "Jenkins must not build application image $applicationDockerfile."
}
foreach ($evidenceInvariant in @(
    'deploymentConfigCommit', 'applicationReleaseCommit', 'selectionPolicy',
    'sourceImage = $image.sourceImage', 'sourceTag = $image.sourceTag',
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
  "Installer must request the Blue Ocean pipeline UI."
Assert-True ($installer.Contains('function Assert-BlueOceanReady')) `
  "Installer must verify Blue Ocean after Jenkins restarts."
Assert-True ($installer.Contains("list-plugins")) `
  "Installer must verify the active Jenkins plugin set."
Assert-True ($installer.Contains("$jenkinsUrl/blue/")) `
  "Installer must verify the Blue Ocean HTTP endpoint."
Assert-True ($installer.Contains('Blue Ocean plugin is not active after installation and restart.')) `
  "Installer must fail closed when Blue Ocean does not activate."
Assert-True ($deploymentBoundary.Contains('function Assert-DockerRuntimeReady')) `
  "Deployment must have an explicit Docker runtime preflight."
Assert-True ($imagePreparation.Contains('function Assert-DockerRuntimeReady')) `
  "Image preparation must independently verify Docker runtime readiness."
Assert-True ($deploymentBoundary -match '\$serverVersion\.Count\s+-ne\s+1') `
  "Docker preflight must reject daemon error text even when docker.exe reports an unexpected success exit code."
Assert-True ($imagePreparation -match '\$serverVersion\.Count\s+-ne\s+1') `
  "Image preparation Docker preflight must reject daemon error text before k3d is invoked."
Assert-True ($deploymentBoundary.Contains('Docker runtime is unavailable.')) `
  "Docker preflight must emit an actionable failure reason."

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
  # The deployment boundary reads its own rollback state before it validates anything, so the
  # fixture has to carry that file too. Copying it here rather than making the dependency lazy
  # keeps the held-release check on the validate path, which is where it has to be: a version that
  # will be refused should be refused before somebody is asked to approve it.
  Copy-Item $cdStateScript (Join-Path $seed "deploy\jenkins\cd-state.ps1")
  "baseline" | Set-Content (Join-Path $seed "README.md")
  git -C $seed add .
  git -C $seed commit -m baseline --quiet
  git -C $seed remote add origin $remote
  git -C $seed push -u origin main --quiet
  git clone --quiet --branch main $remote $checkout

  & pwsh -NoProfile -NonInteractive -File (Join-Path $checkout "deploy\jenkins\deploy-main.ps1") `
    -ValidateOnly -ExpectedRepository $remote | Out-Null
  Assert-True ($LASTEXITCODE -eq 0) "Trusted current main validation should succeed."

  # The anti-flapping guarantee, exercised rather than asserted from source. Jenkins polls main
  # every two minutes, so a commit that failed its health gates and was rolled back must not be
  # deployable again by the next poll -- and the refusal has to happen on the validate path, before
  # a human is asked to approve something that cannot proceed.
  $heldStateRoot = Join-Path $temporaryRoot "cd-state"
  New-Item -ItemType Directory -Path $heldStateRoot -Force | Out-Null
  $heldCommit = (git -C $checkout rev-parse HEAD).Trim()
  @{
    state = 'RELEASE_HELD'; current_commit = $heldCommit; known_good_commit = ''
    known_good_images = @{}; held_versions = @($heldCommit); failure_count = 1
    updated_at = [DateTimeOffset]::UtcNow.ToString('O')
  } | ConvertTo-Json -Depth 6 |
    Out-File (Join-Path $heldStateRoot "ramals-dev-ramals-dev.json") -Encoding utf8

  & pwsh -NoProfile -NonInteractive -File (Join-Path $checkout "deploy\jenkins\deploy-main.ps1") `
    -ValidateOnly -ExpectedRepository $remote -StateRoot $heldStateRoot 2>&1 | Out-Null
  Assert-True ($LASTEXITCODE -ne 0) "A held release must be refused during validation."

  & pwsh -NoProfile -NonInteractive -File (Join-Path $checkout "deploy\jenkins\deploy-main.ps1") `
    -ValidateOnly -ExpectedRepository $remote -StateRoot $heldStateRoot -ForceHeldRelease | Out-Null
  Assert-True ($LASTEXITCODE -eq 0) "An explicit override must be able to redeploy a held release."

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
