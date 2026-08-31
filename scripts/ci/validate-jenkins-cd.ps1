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
$installScript = Join-Path $repositoryRoot "deploy\jenkins\install-local.ps1"
$jobConfigPath = Join-Path $repositoryRoot "deploy\jenkins\job-config.xml"
$jenkinsfilePath = Join-Path $repositoryRoot "Jenkinsfile"
Assert-PowerShellParses $deployScript
Assert-PowerShellParses $installScript
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
Assert-True ($installer -notmatch '/latest/') "Installer downloads must not use mutable latest URLs."
Assert-True ($installer -match '\$jenkinsVersion\s*=') "Jenkins must be explicitly version-pinned."
Assert-True ($installer -match '\$temurinVersion\s*=') "Temurin must be explicitly version-pinned."
Assert-True ($installer -match '\$jenkinsSha256\s*=\s*"[A-F0-9]{64}"') "Jenkins SHA256 must be pinned."
Assert-True ($installer -match '\$temurinSha256\s*=\s*"[A-F0-9]{64}"') "Temurin SHA256 must be pinned."
Assert-True ($installer -match 'install-plugin[\s\S]*\btimestamper\b') `
  "Installer must provide the Timestamper plugin required by timestamps()."
Assert-True ($installer -match 'install-plugin[\s\S]*\bblueocean\b') `
  "Installer must provide the requested Blue Ocean pipeline UI."

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
