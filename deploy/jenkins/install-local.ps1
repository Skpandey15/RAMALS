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

function Install-VerifiedDownload(
  [string]$Uri,
  [string]$Destination,
  [string]$ExpectedSha256
) {
  if (Test-Path $Destination) {
    $actualHash = (Get-FileHash $Destination -Algorithm SHA256).Hash
    if ($actualHash -eq $ExpectedSha256) { return }
    Write-Host "Removing cached file with an unexpected checksum: $Destination"
    Remove-Item -LiteralPath $Destination
  }
  Write-Host "Downloading pinned artifact $Uri"
  Invoke-WebRequest -Uri $Uri -OutFile $Destination -UseBasicParsing
  $actualHash = (Get-FileHash $Destination -Algorithm SHA256).Hash
  if ($actualHash -ne $ExpectedSha256) {
    Remove-Item -LiteralPath $Destination
    throw "Checksum mismatch for $Uri."
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

function Wait-PortReleased([int]$TimeoutSeconds = 30) {
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if (-not $listener) { return }
    Start-Sleep -Milliseconds 500
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Port $Port is still listening after $TimeoutSeconds seconds."
}

# Keep the controller runtime reproducible. Upgrades are explicit changes to this version/hash pair.
$temurinVersion = "21.0.12.1+1"
$temurinSha256 = "F9D6E191AB098C0D416E7D588A24420A8621CD2F4720DAB2459B8B7B2D2D8B4E"
$jdkDirectory = "jdk-$temurinVersion"
$jdkArchive = Join-Path $downloads "OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
$jdkHome = Join-Path $installRoot $jdkDirectory
if (-not (Test-Path $jdkHome)) {
  Install-VerifiedDownload `
    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip" `
    $jdkArchive $temurinSha256
  Expand-Archive -Path $jdkArchive -DestinationPath $installRoot -Force
}
if (-not (Test-Path $jdkHome)) { throw "Java extraction did not produce $jdkDirectory." }
$java = Join-Path $jdkHome "bin\java.exe"

$jenkinsVersion = "2.568.2"
$jenkinsSha256 = "9BBB2B329E52730BA7DECD1A7A1095987F6250EC761FB21157DBB2CBCD1EF590"
$war = Join-Path $installRoot "jenkins-$jenkinsVersion.war"
Install-VerifiedDownload `
  "https://get.jenkins.io/war-stable/$jenkinsVersion/jenkins.war" `
  $war $jenkinsSha256

$passwordFile = Join-Path $jenkinsHome "secrets\ramals-admin-password"
if (-not (Test-Path $passwordFile)) {
  $bytes = New-Object byte[] 32
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  [Convert]::ToBase64String($bytes) | Set-Content $passwordFile -NoNewline -Encoding ascii
}
$storedPassword = (Get-Content $passwordFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($storedPassword)) {
  throw "Jenkins recovery credential is missing or empty: $passwordFile"
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
if (!passwordFile.exists()) {
  throw new IllegalStateException('ramals-admin password file is missing')
}
def adminPassword = passwordFile.text.trim()
if (adminPassword.isEmpty()) {
  throw new IllegalStateException('ramals-admin password file is empty')
}
if (!(instance.securityRealm instanceof HudsonPrivateSecurityRealm)) {
  instance.setSecurityRealm(new HudsonPrivateSecurityRealm(false))
}
// The restricted recovery file is the bootstrap credential authority. Reconcile the Jenkins
// password property on every controller startup so browser/CLI login cannot drift from that file.
def adminUser = User.getById('ramals-admin', true)
adminUser.addProperty(HudsonPrivateSecurityRealm.Details.fromPlainPassword(adminPassword))
adminUser.save()
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
println('RAMALS Jenkins admin credential synchronized successfully')
'@
$initScript = $initScript.Replace('__JENKINS_URL__', $jenkinsUrl)
$initScript | Set-Content (Join-Path $jenkinsHome "init.groovy.d\10-ramals-security.groovy") `
  -Encoding utf8

$startScript = @"
`$env:JENKINS_HOME = '$jenkinsHome'
# Scope Windows long-path support to Jenkins and every Git process it launches. RAMALS contains
# legitimate tracked paths that exceed Git for Windows' legacy 260-character checkout limit.
`$env:GIT_CONFIG_COUNT = '1'
`$env:GIT_CONFIG_KEY_0 = 'core.longpaths'
`$env:GIT_CONFIG_VALUE_0 = 'true'
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
  $controllerPid = $null
  if (Test-Path $pidFile) {
    $recordedPid = 0
    if ([int]::TryParse((Get-Content $pidFile -Raw).Trim(), [ref]$recordedPid)) {
      if (Get-Process -Id $recordedPid -ErrorAction SilentlyContinue) { $controllerPid = $recordedPid }
    }
  }
  if (-not $controllerPid) {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($listener) { $controllerPid = $listener.OwningProcess }
  }
  if ($controllerPid) {
    $controller = Get-Process -Id $controllerPid -ErrorAction Stop
    if ($controller.Path -notlike "$installRoot\jdk-21*\bin\java.exe") {
      throw "Refusing to stop unexpected PID $controllerPid at $($controller.Path)."
    }
    Stop-Process -Id $controllerPid
    Wait-Process -Id $controllerPid -ErrorAction SilentlyContinue
    Wait-PortReleased
  }
  & pwsh -NoProfile -NonInteractive -File $startPath
  Wait-Jenkins 360
}

# A rerun must apply the current bootstrap script before attempting authenticated CLI operations.
# This also repairs an existing Jenkins credential that drifted from the protected recovery file.
if ($running) {
  Write-Host "Restarting existing Jenkins controller to apply bootstrap configuration"
  Restart-LocalJenkins
}

$cli = Join-Path $installRoot "jenkins-cli.jar"
Invoke-WebRequest -Uri "$jenkinsUrl/jnlpJars/jenkins-cli.jar" -OutFile $cli -UseBasicParsing
$adminPassword = (Get-Content $passwordFile -Raw).Trim()
$auth = "ramals-admin:$adminPassword"

function Wait-JenkinsCliAuthentication([int]$TimeoutSeconds = 60) {
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    & $java -jar $cli -s $jenkinsUrl -auth $auth who-am-i *> $null
    if ($LASTEXITCODE -eq 0) { return }
    Start-Sleep -Seconds 2
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Jenkins bootstrap account authentication failed for ramals-admin. See $installRoot\jenkins-error.log."
}

function Assert-BlueOceanReady {
  Write-Host "Verifying Blue Ocean plugin and UI endpoint"
  $pluginList = & $java -jar $cli -s $jenkinsUrl -auth $auth list-plugins 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "Could not verify installed Jenkins plugins after restart."
  }
  if (-not ($pluginList -match '(?m)^blueocean\s')) {
    throw "Blue Ocean plugin is not active after installation and restart."
  }

  $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($auth))
  try {
    $response = Invoke-WebRequest -Uri "$jenkinsUrl/blue/" -Headers @{ Authorization = "Basic $basic" } `
      -TimeoutSec 15 -MaximumRedirection 5 -UseBasicParsing
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
      throw "Blue Ocean endpoint returned HTTP $($response.StatusCode)."
    }
  } catch {
    throw "Blue Ocean plugin is installed but the UI endpoint is not reachable at $jenkinsUrl/blue/. $($_.Exception.Message)"
  }

  Write-Host "Blue Ocean ready: $jenkinsUrl/blue/"
}

Write-Host "Verifying Jenkins bootstrap authentication"
Wait-JenkinsCliAuthentication

Write-Host "Installing required pipeline, Git, and UI plugins"
& $java -jar $cli -s $jenkinsUrl -auth $auth install-plugin `
  workflow-aggregator git pipeline-stage-view timestamper blueocean
if ($LASTEXITCODE -ne 0) { throw "Required Jenkins plugin installation failed." }
# User-mode Jenkins has no service lifecycle, so restart the verified controller process ourselves.
Restart-LocalJenkins
Wait-JenkinsCliAuthentication
Assert-BlueOceanReady

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

# A second, parameterised job for deploying an arbitrary branch to the same local environment.
# Separate rather than a parameter on RAMALS-main: that job refuses anything that is not trusted
# main, and the refusal is the property the release path rests on. Giving it a "which ref" parameter
# would delete that property for every build, including the ones nobody is watching.
$branchJobConfig = Get-Content (Join-Path $PSScriptRoot "job-config-branch.xml") -Raw
& $java -jar $cli -s $jenkinsUrl -auth $auth get-job RAMALS-branch *> $null
if ($LASTEXITCODE -eq 0) {
  $branchJobConfig | & $java -jar $cli -s $jenkinsUrl -auth $auth update-job RAMALS-branch
} else {
  $branchJobConfig | & $java -jar $cli -s $jenkinsUrl -auth $auth create-job RAMALS-branch
}
if ($LASTEXITCODE -ne 0) { throw "Could not create or update the RAMALS-branch Jenkins job." }

Write-Host "Jenkins ready: $jenkinsUrl" -ForegroundColor Green
Write-Host "Blue Ocean: $jenkinsUrl/blue/" -ForegroundColor Green
Write-Host "User: ramals-admin"
Write-Host "Password is stored with restricted ACLs at: $passwordFile"

if ($RunInitialBuild) {
  Write-Host "Starting initial RAMALS-main deployment"
  & $java -jar $cli -s $jenkinsUrl -auth $auth build RAMALS-main -s -v
  if ($LASTEXITCODE -ne 0) { throw "Initial RAMALS-main Jenkins build failed." }
}
