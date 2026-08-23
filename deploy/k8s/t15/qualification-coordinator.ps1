[CmdletBinding()]
param(
  [ValidateSet("start", "stop", "inspect")]
  [string]$Action = "inspect",
  [string]$ClusterName = "t15",
  [string]$Namespace = "ramals-t15",
  [ValidateSet(
    "core.learning_workflow_run",
    "core.learning_workflow_step",
    "core.agent_work_outbox",
    "core.ai_execution"
  )]
  [string]$Table = "core.learning_workflow_step",
  [string]$RowId = ""
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

Invoke-Checked "kubectl" @("config", "use-context", "k3d-$ClusterName")
$podName = "t15-pg-lock-coordinator"

if ($Action -eq "stop") {
  Invoke-Checked "kubectl" @("delete", "pod", $podName, "-n", $Namespace, "--ignore-not-found=true")
  Write-Host "Stopped the qualification lock coordinator; any held transaction lock is released"
  exit 0
}

if ($Action -eq "inspect") {
  Invoke-Checked "kubectl" @("get", "pod", $podName, "-n", $Namespace, "-o", "wide", "--ignore-not-found=true")
  # The application container's local PostgreSQL socket is used so no password is put in a command
  # line. The query returns lock ownership/wait state only; it does not select application payloads.
  Invoke-Checked "kubectl" @(
    "exec", "statefulset/postgres", "-n", $Namespace, "--",
    "sh", "-ec",
    'psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "SELECT a.pid, l.locktype, l.relation::regclass AS relation, l.mode, l.granted, a.wait_event_type, left(a.query, 160) AS query FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid WHERE a.datname = current_database() ORDER BY l.granted, a.pid;"'
  )
  exit 0
}

if (-not [string]::IsNullOrWhiteSpace($RowId) -and $RowId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
  throw "-RowId must be a UUID"
}

$existing = (& kubectl get pod $podName -n $Namespace --ignore-not-found=true -o name 2>$null) -join ""
if (-not [string]::IsNullOrWhiteSpace($existing)) {
  throw "$podName already exists; stop it before starting another lock"
}

$sql = if ([string]::IsNullOrWhiteSpace($RowId)) {
  "BEGIN; LOCK TABLE $Table IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(86400);"
} else {
  "BEGIN; SELECT id FROM $Table WHERE id = '$RowId' FOR UPDATE; SELECT pg_sleep(86400);"
}

# Keep the shell variables escaped in the generated command. They are resolved inside the pod from
# Secret-backed environment variables; this PowerShell process never reads decoded Secret values.
$command = 'psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "@@SQL@@"'
$command = $command.Replace("@@SQL@@", $sql)

$pod = [ordered]@{
  apiVersion = "v1"
  kind = "Pod"
  metadata = [ordered]@{
    name = $podName
    namespace = $Namespace
    labels = [ordered]@{
      "app.kubernetes.io/name" = $podName
      "ramals.io/qualification" = "m2-t15"
      "ramals.io/qualification-helper" = "true"
    }
  }
  spec = [ordered]@{
    restartPolicy = "Never"
    securityContext = [ordered]@{
      runAsUser = 999
      runAsGroup = 999
      runAsNonRoot = $true
      seccompProfile = [ordered]@{ type = "RuntimeDefault" }
    }
    containers = @([ordered]@{
      name = "psql"
      image = "k3d-ramals-t15-registry:5000/ramals-postgres@sha256:f2e582060a6a0cbf5eecd729f472fe54d39702f382f0d72fde5194cee2c1914e"
      imagePullPolicy = "IfNotPresent"
      command = @("sh", "-ec")
      args = @($command)
      env = @(
        [ordered]@{ name = "PGHOST"; value = "postgres" },
        [ordered]@{ name = "POSTGRES_USER"; valueFrom = [ordered]@{ secretKeyRef = [ordered]@{ name = "ramals-t15-runtime"; key = "db-admin-user" } } },
        [ordered]@{ name = "POSTGRES_DB"; valueFrom = [ordered]@{ secretKeyRef = [ordered]@{ name = "ramals-t15-runtime"; key = "db-name" } } },
        [ordered]@{ name = "PGPASSWORD"; valueFrom = [ordered]@{ secretKeyRef = [ordered]@{ name = "ramals-t15-runtime"; key = "db-admin-password" } } }
      )
      resources = [ordered]@{
        requests = [ordered]@{ cpu = "25m"; memory = "64Mi" }
        limits = [ordered]@{ cpu = "100m"; memory = "128Mi" }
      }
      securityContext = [ordered]@{
        allowPrivilegeEscalation = $false
        capabilities = [ordered]@{ drop = @("ALL") }
      }
    })
  }
}

$podJson = $pod | ConvertTo-Json -Depth 20
$podJson | & kubectl apply -f -
if ($LASTEXITCODE -ne 0) {
  throw "could not start the qualification lock coordinator"
}
Write-Host "Started $podName for $Table$(if ($RowId) { " row $RowId" } else { " table lock" })"
Write-Host "Run the T15 crash action, then invoke this script with -Action stop"
