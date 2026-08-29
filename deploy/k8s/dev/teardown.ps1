# Remove the local development environment.
#
#   pwsh -File .\deploy\k8s\dev\teardown.ps1              # cluster only, registry kept
#   pwsh -File .\deploy\k8s\dev\teardown.ps1 -IncludeRegistry
#
# Deletes only the two objects this package creates, both addressed by exact name. It never runs a
# blanket `docker system prune`, never deletes clusters it did not create, and never touches
# Rancher Desktop itself -- this workstation has other containers on it, and a teardown script that
# takes them with it is one somebody runs once and then avoids.
#
# The cluster's PostgreSQL data lives on node-local storage, so deleting the cluster destroys the
# local database. That is intended: this environment is disposable, and bootstrap.ps1 rebuilds it.

[CmdletBinding()]
param(
  [string]$ClusterName = "ramals-dev",
  [string]$RegistryName = "ramals-registry",
  [switch]$IncludeRegistry,
  [switch]$Force
)

$ErrorActionPreference = "Stop"
$registryHost = "k3d-$RegistryName"

if (-not (Get-Command k3d -ErrorAction SilentlyContinue)) {
  throw "k3d is not on PATH; nothing to tear down through this script."
}

$clusterExists = [bool](k3d cluster list -o json | ConvertFrom-Json | Where-Object { $_.name -eq $ClusterName })
$registryExists = [bool](k3d registry list -o json | ConvertFrom-Json | Where-Object { $_.name -eq $registryHost })

if (-not $clusterExists -and -not $registryExists) {
  Write-Host "Nothing to remove: neither cluster '$ClusterName' nor registry '$registryHost' exists." -ForegroundColor Yellow
  exit 0
}

Write-Host "About to delete:" -ForegroundColor Yellow
if ($clusterExists) { Write-Host "  - k3d cluster '$ClusterName' (including its PostgreSQL data)" }
if ($registryExists -and $IncludeRegistry) { Write-Host "  - k3d registry '$registryHost' (including cached images)" }
elseif ($registryExists) { Write-Host "  - registry '$registryHost' will be KEPT (pass -IncludeRegistry to remove)" }

if (-not $Force) {
  $answer = Read-Host "Type 'yes' to proceed"
  if ($answer -ne "yes") { Write-Host "Aborted; nothing was deleted."; exit 1 }
}

if ($clusterExists) {
  k3d cluster delete $ClusterName | Out-Host
}
if ($registryExists -and $IncludeRegistry) {
  k3d registry delete $registryHost | Out-Host
}

Write-Host ""
Write-Host "Remaining k3d state:" -ForegroundColor Cyan
k3d cluster list | Out-Host
k3d registry list | Out-Host
