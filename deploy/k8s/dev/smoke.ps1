# Prove the local environment actually works, rather than merely that everything is Running.
#
#   pwsh -File .\deploy\k8s\dev\smoke.ps1
#
# Every check is a positive or negative assertion with a stated expected outcome. The isolation
# check in particular is a NEGATIVE control: it fails the run if the AI plane CAN reach the
# database. "Nothing crashed" is not evidence of isolation -- a path that is never exercised looks
# identical to a path that is blocked.
#
# Exit code 0 = all checks passed. Non-zero = at least one failed; the summary names which.

[CmdletBinding()]
param(
  [string]$Namespace = "ramals-dev",
  [string]$ClusterName = "ramals-dev"
)

$ErrorActionPreference = "Stop"
$context = "k3d-$ClusterName"
kubectl config use-context $context | Out-Null

$script:Results = @()
function Check {
  param([string]$Name, [scriptblock]$Test, [string]$Expect = "pass")
  try {
    $ok = & $Test
    $state = if ($ok) { "PASS" } else { "FAIL" }
  } catch {
    $state = "FAIL"
  }
  $script:Results += [pscustomobject]@{ Check = $Name; Result = $state; Expected = $Expect }
  $colour = if ($state -eq "PASS") { "Green" } else { "Red" }
  Write-Host ("{0,-46} {1}" -f $Name, $state) -ForegroundColor $colour
}

# Run a command inside a pod selected by label, returning $true when it exits 0.
function Test-InPod {
  param([string]$Selector, [string[]]$Command)
  $pod = kubectl get pod -n $Namespace -l $Selector -o jsonpath='{.items[0].metadata.name}' 2>$null
  if (-not $pod) { return $false }
  kubectl exec -n $Namespace $pod -- @Command 2>$null | Out-Null
  return $LASTEXITCODE -eq 0
}

Write-Host "== workload readiness ==" -ForegroundColor Cyan
foreach ($w in @("statefulset/postgres", "deployment/keycloak", "deployment/ramals-ai",
                 "deployment/learning-platform", "deployment/web-ui")) {
  Check "$w rolled out" { kubectl rollout status $w -n $Namespace --timeout=10s 2>$null | Out-Null; $LASTEXITCODE -eq 0 }
}

Write-Host ""
Write-Host "== service discovery and connectivity ==" -ForegroundColor Cyan

# Kubernetes DNS: the platform must resolve every peer by service name, not by address.
foreach ($svc in @("postgres", "keycloak", "ramals-ai")) {
  Check "platform resolves $svc via cluster DNS" {
    Test-InPod "app.kubernetes.io/name=learning-platform" @("sh", "-c", "getent hosts $svc")
  }
}

Check "platform -> PostgreSQL:5432 reachable" {
  Test-InPod "app.kubernetes.io/name=learning-platform" @("sh", "-c", "timeout 5 sh -c '</dev/tcp/postgres/5432'")
}
Check "platform -> ramals-ai:8000 healthy" {
  Test-InPod "app.kubernetes.io/name=learning-platform" @("sh", "-c", "timeout 5 sh -c '</dev/tcp/ramals-ai/8000'")
}
Check "platform -> keycloak:8080 reachable" {
  Test-InPod "app.kubernetes.io/name=learning-platform" @("sh", "-c", "timeout 5 sh -c '</dev/tcp/keycloak/8080'")
}
Check "platform readiness endpoint UP" {
  Test-InPod "app.kubernetes.io/name=learning-platform" @("sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q UP")
}
Check "ramals-ai readiness endpoint UP" {
  Test-InPod "app.kubernetes.io/name=ramals-ai" @("sh", "-c", "python -c `"import urllib.request,sys; sys.exit(0 if b'UP' in urllib.request.urlopen('http://127.0.0.1:8000/health/ready',timeout=5).read() else 1)`"")
}
Check "web-ui -> platform reachable" {
  Test-InPod "app.kubernetes.io/name=web-ui" @("sh", "-c", "timeout 5 sh -c '</dev/tcp/learning-platform/8080'")
}

Write-Host ""
Write-Host "== isolation (negative controls) ==" -ForegroundColor Cyan

# The invariant the whole architecture rests on. This asserts the connection FAILS. If the AI plane
# can open a socket to PostgreSQL, the NetworkPolicy is not doing its job and this run must fail.
Check "ramals-ai CANNOT reach PostgreSQL (NetworkPolicy)" -Expect "connection refused/timeout" {
  $blocked = -not (Test-InPod "app.kubernetes.io/name=ramals-ai" `
    @("python", "-c", "import socket;s=socket.socket();s.settimeout(5);s.connect(('postgres',5432))"))
  return $blocked
}

Check "PostgreSQL has no NodePort/LoadBalancer" {
  $t = kubectl get svc postgres -n $Namespace -o jsonpath='{.spec.type}' 2>$null
  return $t -eq "ClusterIP"
}
Check "no Service is externally exposed" {
  $bad = kubectl get svc -n $Namespace -o jsonpath='{range .items[*]}{.spec.type}{"\n"}{end}' 2>$null |
    Where-Object { $_ -and $_ -ne "ClusterIP" }
  return -not $bad
}

Write-Host ""
Write-Host "== Contract B stays off ==" -ForegroundColor Cyan
foreach ($flag in @("RAMALS_CONTRACT_B_ENABLED", "RAMALS_CONTRACT_B_RECONCILIATION_ENABLED",
                    "RAMALS_CONTRACT_B_PURGE_ENABLED")) {
  Check "$flag = false" {
    (kubectl get configmap ramals-dev-config -n $Namespace -o jsonpath="{.data.$flag}" 2>$null) -eq "false"
  }
}
Check "RAMALS_AI_DURABLE_EXECUTION_ENABLED = false" {
  $v = kubectl get deployment ramals-ai -n $Namespace `
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="RAMALS_AI_DURABLE_EXECUTION_ENABLED")].value}' 2>$null
  return $v -eq "false"
}
Check "RAMALS_AI_AI_ENABLED = false (no external provider)" {
  $v = kubectl get deployment ramals-ai -n $Namespace `
    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="RAMALS_AI_AI_ENABLED")].value}' 2>$null
  return $v -eq "false"
}

Write-Host ""
Write-Host "== logs carry no credentials ==" -ForegroundColor Cyan
# Deliberately does not read Secret objects -- it reads what the applications actually emit, which
# is where a leak would surface.
Check "no credential-shaped strings in pod logs" {
  $pattern = 'sk-ant-[A-Za-z0-9_-]{8}|sk-proj-[A-Za-z0-9_-]{8}|AKIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY|password["'']?\s*[:=]\s*["'']?\S{6}'
  $hits = 0
  foreach ($p in (kubectl get pods -n $Namespace -o jsonpath='{.items[*].metadata.name}').Split(" ")) {
    if (-not $p) { continue }
    $log = kubectl logs $p -n $Namespace --all-containers --tail=500 2>$null
    if ($log -and ($log | Select-String -Pattern $pattern -Quiet)) { $hits++ }
  }
  return $hits -eq 0
}

Write-Host ""
$failed = @($script:Results | Where-Object { $_.Result -ne "PASS" })
$script:Results | Format-Table -AutoSize | Out-Host
if ($failed.Count -gt 0) {
  Write-Host "SMOKE FAILED: $($failed.Count) of $($script:Results.Count) checks" -ForegroundColor Red
  exit 1
}
Write-Host "SMOKE PASSED: $($script:Results.Count)/$($script:Results.Count) checks" -ForegroundColor Green
