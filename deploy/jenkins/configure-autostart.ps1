<#
.SYNOPSIS
Registers the local RAMALS Jenkins controller to start automatically when the current Windows user signs in.

.DESCRIPTION
The task runs only in the current interactive user context and does not require administrator rights.
It starts Jenkins only when the loopback controller is not already responding. This preserves access to
Rancher Desktop and the current user's kubeconfig while avoiding duplicate controllers on port 8090.
#>

[CmdletBinding()]
param(
  [int]$Port = 8090,
  [switch]$StartNow
)

$ErrorActionPreference = "Stop"
$installRoot = Join-Path $env:LOCALAPPDATA "RAMALS\Jenkins"
$startScript = Join-Path $installRoot "start-jenkins.ps1"
$ensureScript = Join-Path $installRoot "ensure-jenkins-running.ps1"
$jenkinsUrl = "http://127.0.0.1:$Port"
$taskName = "RAMALS-Jenkins-Local"

if (-not (Test-Path $startScript)) {
  throw "Jenkins is not installed. Run deploy/jenkins/install-local.ps1 first."
}

$ensureContent = @"
`$ErrorActionPreference = 'Stop'
`$jenkinsUrl = '$jenkinsUrl'
try {
  `$response = Invoke-WebRequest -Uri "`$jenkinsUrl/login" -TimeoutSec 3 -UseBasicParsing
  if (`$response.StatusCode -eq 200) { exit 0 }
} catch {
  # Controller is not ready; start the verified user-mode Jenkins installation.
}
& pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File '$startScript'
"@
$ensureContent | Set-Content -Path $ensureScript -Encoding utf8

$currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$pwsh = (Get-Command pwsh -ErrorAction Stop).Source
$action = New-ScheduledTaskAction `
  -Execute $pwsh `
  -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$ensureScript`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
$principal = New-ScheduledTaskPrincipal `
  -UserId $currentUser `
  -LogonType Interactive `
  -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
  -AllowStartIfOnBatteries `
  -DontStopIfGoingOnBatteries `
  -StartWhenAvailable `
  -MultipleInstances IgnoreNew

Register-ScheduledTask `
  -TaskName $taskName `
  -Action $action `
  -Trigger $trigger `
  -Principal $principal `
  -Settings $settings `
  -Description "Start the RAMALS local Jenkins controller at user sign-in." `
  -Force | Out-Null

Write-Host "Configured Windows logon autostart task: $taskName" -ForegroundColor Green
Write-Host "Jenkins URL: $jenkinsUrl"
Write-Host "RAMALS-main already polls GitHub main every two minutes and deploys qualified changes."

if ($StartNow) {
  try {
    $probe = Invoke-WebRequest -Uri "$jenkinsUrl/login" -TimeoutSec 3 -UseBasicParsing
    if ($probe.StatusCode -eq 200) {
      Write-Host "Jenkins is already running; no additional controller was started."
      return
    }
  } catch {
    # Start the task below.
  }

  Start-ScheduledTask -TaskName $taskName
  $deadline = [DateTime]::UtcNow.AddMinutes(4)
  do {
    Start-Sleep -Seconds 2
    try {
      $probe = Invoke-WebRequest -Uri "$jenkinsUrl/login" -TimeoutSec 3 -UseBasicParsing
      if ($probe.StatusCode -eq 200) {
        Write-Host "Jenkins is running at $jenkinsUrl" -ForegroundColor Green
        return
      }
    } catch {
      # Keep waiting until the deadline.
    }
  } while ([DateTime]::UtcNow -lt $deadline)

  throw "The scheduled task ran but Jenkins did not become ready at $jenkinsUrl."
}
