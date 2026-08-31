<#
.SYNOPSIS
Installs a user-mode Jenkins LTS controller for RAMALS local k3d deployment.

.DESCRIPTION
No administrator rights are required. Jenkins, its supported Java 21 runtime, controller home, and
credentials live below the current user's profile. The controller listens only on 127.0.0.1:8090.
#>

[CmdletBinding()]
param(
  [int]$Port = 8090,
  [switch]$RunInitialBuild
)

$ErrorActionPreference = "Stop"
$installRoot = Join-Path $env:LOCALAPPDATA "RAMALS\Jenkins"
$jenkinsHome = Join-Path $env:LOCALAPPDATA "RAMALS\JenkinsHome"
$downloads = Join-Path $installRoot "downloads"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$jenkinsUrl = "http://127.0.0.1:$Port"

foreach ($directory in @($installRoot, $jenkinsHome, $downloads,
    (Join-Path $jenkinsHome "init.groovy.d"), (Join-Path $jenkinsHome "secrets"))) {
  New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

function Download-IfMissing([string]$Uri, [string]$Destination) {
  if (-not (Test-Path $Destination)) {
    Write-Host "Downloading $Uri"
    Invoke-WebRequest -Uri $Uri -OutFile $Destination -UseBasicParsing
  }
}

function Wait-Jenkins([int]$TimeoutSeconds = 240) {
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    try {
      $response = Invoke-WebRequest -Uri "$jenkinsUrl/login" -TimeoutSec 5 -UseBasicParsing
      if ($response.StatusCode -eq 200) { return }
    } catch { Start-Sleep -Seconds 2 }
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Jenkins did not become ready at $jenkinsUrl within $TimeoutSeconds seconds."
}

# Jenkins LTS is tested on Java 21; do not use an unrelated system Java release for the controller.
$jdkArchive = Join-Path $downloads "temurin-21.zip"
if (-not (Get-ChildItem $installRoot -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue)) {
  Download-IfMissing `
    "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" `
    $jdkArchive
  Expand-Archive -Path $jdkArchive -DestinationPath $installRoot -Force
}
$jdkHome = (Get-ChildItem $installRoot -Directory -Filter "jdk-21*" |
  Sort-Object Name -Descending | Select-Object -First 1).FullName
if (-not $jdkHome) { throw "Java 21 extraction did not produce a jdk-21 directory." }
$java = Join-Path $jdkHome "bin\java.exe"

$war = Join-Path $installRoot "jenkins.war"
$warChecksum = Join-Path $downloads "jenkins.war.sha256"
Download-IfMissing "https://get.jenkins.io/war-stable/latest/jenkins.war" $war
Invoke-WebRequest -Uri "https://get.jenkins.io/war-stable/latest/jenkins.war.sha256" `
  -OutFile $warChecksum -UseBasicParsing
$expectedHash = ((Get-Content $warChecksum -Raw).Trim() -split '\s+')[0].ToUpperInvariant()
$actualHash = (Get-FileHash $war -Algorithm SHA256).Hash
if ($actualHash -ne $expectedHash) {
  throw "Downloaded Jenkins WAR checksum mismatch."
}

$passwordFile = Join-Path $jenkinsHome "secrets\ramals-admin-password"
if (-not (Test-Path $passwordFile)) {
  $bytes = New-Object byte[] 32
  [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [Convert]::ToBase64String($bytes) | Set-Content $passwordFile -NoNewline -Encoding ascii
}
# Restrict the controller home and recovery credential to this Windows account.
& icacls $jenkinsHome /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not restrict Jenkins home permissions." }

$initScript = @'
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import hudson.model.User
import jenkins.install.InstallState
import jenkins.model.Jenkins
import jenkins.model.JenkinsLocationConfiguration

def instance = Jenkins.get()
def passwordFile = new File(instance.rootDir, 'secrets/ramals-admin-password')
if (!(instance.securityRealm instanceof HudsonPrivateSecurityRealm)) {
  def realm = new HudsonPrivateSecurityRealm(false)
  instance.setSecurityRealm(realm)
}
def realm = (HudsonPrivateSecurityRealm) instance.securityRealm
if (User.getById('ramals-admin', false) == null) {
  realm.createAccount('ramals-admin', passwordFile.text.trim())
}
def authorization = new FullControlOnceLoggedInAuthorizationStrategy()
authorization.setAllowAnonymousRead(false)
instance.setAuthorizationStrategy(authorization)
instance.setNumExecutors(1)
instance.setLabelString('windows ramals-dev')
instance.setInstallState(InstallState.INITIAL_SETUP_COMPLETED)
instance.save()
def location = JenkinsLocationConfiguration.get()
location.setUrl('__JENKINS_URL__/')
location.save()
'@
$initScript = $initScript.Replace('__JENKINS_URL__', $jenkinsUrl)
$initScript | Set-Content (Join-Path $jenkinsHome "init.groovy.d\10-ramals-security.groovy") `
  -Encoding utf8

$startScript = @"
`$env:JENKINS_HOME = '$jenkinsHome'
`$java = '$java'
`$war = '$war'
`$log = '$installRoot\jenkins.log'
`$errorLog = '$installRoot\jenkins-error.log'
`$startParameters = @{
  FilePath = `$java
  ArgumentList = @('-jar', `$war, '--httpListenAddress=127.0.0.1', '--httpPort=$Port')
  WindowStyle = 'Hidden'
  PassThru = `$true
  RedirectStandardOutput = `$log
  RedirectStandardError = `$errorLog
}
`$process = Start-Process @startParameters
`$process.Id | Set-Content '$installRoot\jenkins.pid' -Encoding ascii
Write-Host "Jenkins started as PID `$(`$process.Id) at $jenkinsUrl"
"@
$startPath = Join-Path $installRoot "start-jenkins.ps1"
$startScript | Set-Content $startPath -Encoding utf8

$running = $false
try {
  $probe = Invoke-WebRequest -Uri "$jenkinsUrl/login" -TimeoutSec 3 -UseBasicParsing
  $running = $probe.StatusCode -eq 200
} catch { $running = $false }
if (-not $running) {
  & pwsh -NoProfile -NonInteractive -File $startPath
  Wait-Jenkins
}

function Restart-LocalJenkins {
  $pidFile = Join-Path $installRoot "jenkins.pid"
  if (Test-Path $pidFile) {
    $controllerPid = [int](Get-Content $pidFile -Raw)
    $controller = Get-Process -Id $controllerPid -ErrorAction SilentlyContinue
    if ($controller) {
      if ($controller.Path -notlike "$installRoot\jdk-21*\bin\java.exe") {
        throw "Refusing to stop unexpected PID $controllerPid at $($controller.Path)."
      }
      Stop-Process -Id $controllerPid
      Wait-Process -Id $controllerPid -ErrorAction SilentlyContinue
    }
  }
  & pwsh -NoProfile -NonInteractive -File $startPath
  Wait-Jenkins 360
}

$cli = Join-Path $installRoot "jenkins-cli.jar"
Invoke-WebRequest -Uri "$jenkinsUrl/jnlpJars/jenkins-cli.jar" -OutFile $cli -UseBasicParsing
$adminPassword = (Get-Content $passwordFile -Raw).Trim()
$auth = "ramals-admin:$adminPassword"

Write-Host "Installing required pipeline and Git plugins"
& $java -jar $cli -s $jenkinsUrl -auth $auth install-plugin `
  workflow-aggregator git pipeline-stage-view
if ($LASTEXITCODE -ne 0) { throw "Required Jenkins plugin installation failed." }
# User-mode Jenkins has no service lifecycle, so restart the verified controller process ourselves.
Restart-LocalJenkins

$jobConfig = Get-Content (Join-Path $PSScriptRoot "job-config.xml") -Raw
$jobExists = $false
& $java -jar $cli -s $jenkinsUrl -auth $auth get-job RAMALS-main *> $null
$jobExists = $LASTEXITCODE -eq 0
if ($jobExists) {
  $jobConfig | & $java -jar $cli -s $jenkinsUrl -auth $auth update-job RAMALS-main
} else {
  $jobConfig | & $java -jar $cli -s $jenkinsUrl -auth $auth create-job RAMALS-main
}
if ($LASTEXITCODE -ne 0) { throw "Could not create or update the RAMALS-main Jenkins job." }

Write-Host "Jenkins ready: $jenkinsUrl" -ForegroundColor Green
Write-Host "User: ramals-admin"
Write-Host "Password is stored with restricted ACLs at: $passwordFile"

if ($RunInitialBuild) {
  Write-Host "Starting initial RAMALS-main deployment"
  & $java -jar $cli -s $jenkinsUrl -auth $auth build RAMALS-main -s -v
  if ($LASTEXITCODE -ne 0) { throw "Initial RAMALS-main Jenkins build failed." }
}
