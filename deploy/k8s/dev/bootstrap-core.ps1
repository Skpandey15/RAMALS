# Stand up the RAMALS local Kubernetes development environment from nothing.
#
# Safe to re-run. Every step checks for what it is about to create, so a partial run followed by a
# second invocation converges rather than failing or duplicating. The one thing it will not do is
# silently adopt a cluster somebody else made under the same name with a different shape -- that is
# reported, not worked around.
#
#   pwsh -File .\deploy\k8s\dev\bootstrap.ps1
#
# This is the ordinary developer environment. It is not the M2-T15 qualification environment and it
# is not AWS DEV. Contract B stays off; no external AI provider is configured.

[CmdletBinding()]
param(
  [string]$ClusterName = "ramals-dev",
  [string]$RegistryName = "ramals-registry",
  [int]$RegistryPort = 5000,
  [string]$Namespace = "ramals-dev",
  [switch]$SkipBuild,
  [int]$IngressPort = 8080,
  [switch]$EnableOpenAI,
  [switch]$RepairDockerCredentials,
  [switch]$ShowTestCredentials
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
Set-Location $repositoryRoot

$context = "k3d-$ClusterName"
$registryHost = "k3d-$RegistryName"
$registryRef = "${registryHost}:${RegistryPort}"

function Assert-Tool([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "$name is not on PATH. See deploy/k8s/dev/README.md for prerequisites."
  }
}

Write-Host "== prerequisites ==" -ForegroundColor Cyan
foreach ($t in @("docker", "kubectl", "k3d")) { Assert-Tool $t }
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker is not responding. Start Rancher Desktop and retry." }

# The commit is the image identity. A dirty tree would produce an image whose tag names a commit it
# does not actually contain, so refuse rather than mislabel it.
$gitSha = (git rev-parse --short HEAD).Trim()
if ((git status --porcelain --untracked-files=no)) {
  throw "Working tree has uncommitted tracked changes; images would be tagged '$gitSha' but not match it. Commit or stash first."
}
Write-Host "commit: $gitSha"

Write-Host "== registry ==" -ForegroundColor Cyan
if (-not (k3d registry list -o json | ConvertFrom-Json | Where-Object { $_.name -eq $registryHost })) {
  k3d registry create $RegistryName --port $RegistryPort | Out-Host
} else {
  Write-Host "registry $registryHost already exists"
}

Write-Host "== cluster ==" -ForegroundColor Cyan
if (-not (k3d cluster list -o json | ConvertFrom-Json | Where-Object { $_.name -eq $ClusterName })) {
  # -p publishes a host port to the Traefik load balancer, which is what makes the Ingress
  # reachable. Every Service stays ClusterIP; this one mapping is the only way in, and it can only
  # be set at creation time -- on an existing cluster use
  # `k3d cluster edit <name> --port-add "8080:80@loadbalancer"`.
  k3d cluster create $ClusterName --servers 1 --agents 1 --registry-use $registryRef `
    -p "${IngressPort}:80@loadbalancer" --wait --timeout 300s | Out-Host
} else {
  Write-Host "cluster $ClusterName already exists"
}

kubectl config use-context $context | Out-Null
kubectl wait --for=condition=Ready node --all --timeout=180s | Out-Host

# The registry repository for each image, written out rather than derived from a service name.
# Deriving it once produced `ramals-ramals-ai`, because the AI service is already called "ramals-ai"
# and the code prefixed it again -- the manifests asked for `ramals-ai`, nothing had pushed that, and
# the pod sat in ImagePullBackOff. An explicit name cannot drift from the manifests by construction.
$ramalsImages = @(
  @{ Repo = "ramals-postgres";          File = "infrastructure/docker/postgres-init/Dockerfile" },
  @{ Repo = "ramals-keycloak";          File = "infrastructure/docker/keycloak/Dockerfile" },
  @{ Repo = "ramals-ai";                File = "ramals-ai/Dockerfile" },
  @{ Repo = "ramals-web-ui";            File = "web-ui/Dockerfile" },
  @{ Repo = "ramals-learning-platform"; File = "learning-platform/Dockerfile" }
)

# Every expected tag must exist in the registry before Kubernetes is touched.
#
# This ordering is the whole point: a -SkipBuild run against a commit whose images were never built
# used to repoint healthy Deployments at tags that did not exist, turning a working environment into
# five ImagePullBackOffs. Verification first means the worst case is an early exit with nothing
# mutated.
function Assert-ImagesPresent([string]$sha) {
  $missing = @()
  foreach ($i in $ramalsImages) {
    $url = "http://localhost:$RegistryPort/v2/$($i.Repo)/manifests/$sha"
    try {
      $r = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 15 -UseBasicParsing `
             -Headers @{ Accept = "application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.index.v1+json" } `
             -ErrorAction Stop
      if ($r.StatusCode -ne 200) { $missing += "$($i.Repo):$sha" }
    } catch {
      $missing += "$($i.Repo):$sha"
    }
  }
  if ($missing.Count -gt 0) {
    throw @"
Required images are not in the registry, so Kubernetes has NOT been touched.

  missing : $($missing -join ", ")
  commit  : $sha

Re-run without -SkipBuild to build them. The running environment is unchanged.
"@
  }
  Write-Host "  all $($ramalsImages.Count) images present at :$sha" -ForegroundColor DarkGray
}

# Classify a failed `docker pull` from its output alone.
#
# Split out from the probe so it is a pure function of a string: the DNS branch can then be tested
# against the exact stderr this environment produced, without anyone breaking their own networking
# to reach it. A classifier that can only be exercised by wrecking the machine it runs on does not
# get exercised.
function Get-DockerPullDiagnosis([string]$output, [string]$dockerConfigPath) {
  $text = ($output | Out-String).Trim()

  if ($text -match "no such host|server misbehaving|Temporary failure in name resolution|dial tcp: lookup|context deadline exceeded|i/o timeout") {
    return [pscustomobject]@{
      Condition  = "DNS_FAILURE"
      Detail     = "The container runtime cannot resolve registry hostnames. On Rancher Desktop this is usually its host-switch gateway being unreachable, which leaves the runtime resolver pointing at a dead address."
      Remedy     = "Restart the runtime network: wsl --shutdown  then relaunch Rancher Desktop and wait for Running. If it recurs, read network-setup.log and host-switch.log under the Rancher Desktop logs directory."
      Repairable = $false
    }
  }
  if ($text -match "error getting credentials|credential helper|docker-credential") {
    return [pscustomobject]@{
      Condition  = "CREDENTIAL_HELPER_BROKEN"
      Detail     = "A registry pull failed in the credential path: $text"
      Remedy     = "Re-run with -RepairDockerCredentials, or remove the credsStore key from $dockerConfigPath by hand."
      Repairable = $true
    }
  }
  return [pscustomobject]@{
    Condition  = "REGISTRY_UNREACHABLE"
    Detail     = "A registry pull failed: $text"
    Remedy     = "Check network connectivity and any proxy or firewall between this machine and the registry."
    Repairable = $false
  }
}

# Diagnose the container environment BEFORE the builds, because the builds are the expensive part
# and the two failures seen on this project both surface inside them, minutes in, as errors that
# name Docker rather than their actual cause:
#
#   * a broken credential helper fails with "error getting credentials ... exit status 28", which
#     reads like a network timeout and is not
#   * a dead Rancher Desktop host-switch gateway leaves the runtime unable to resolve anything, so
#     every base-image pull times out with "context deadline exceeded"
#
# This function only ever reads. It classifies and returns; the caller decides what to do.
function Test-DockerEnvironment {
  # 1. Is the daemon there at all?
  docker info --format '{{.ServerVersion}}' 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) {
    return [pscustomobject]@{
      Condition = "DAEMON_UNAVAILABLE"
      Detail    = "docker info failed; no container runtime is answering."
      Remedy    = "Start Rancher Desktop and wait for its status to read Running, then re-run. If it hangs on 'Starting WSL environment', run: wsl --shutdown  and relaunch Rancher Desktop."
      Repairable = $false
    }
  }

  # 2. Is a credential helper configured, and does it actually work?
  #
  # Read only the developer's own ~/.docker/config.json. Rancher Desktop's in-distro config is
  # deliberately out of scope: it is an internal, and a bootstrap script has no business editing it.
  # DOCKER_CONFIG is honoured because Docker itself honours it: reading a different file than the
  # CLI would means diagnosing an environment nobody is running in. It also makes this function
  # testable without touching the developer's real config.
  $dockerConfigDir = if ($env:DOCKER_CONFIG) { $env:DOCKER_CONFIG } else { Join-Path $env:USERPROFILE ".docker" }
  $dockerConfigPath = Join-Path $dockerConfigDir "config.json"
  $credsStore = $null
  if (Test-Path $dockerConfigPath) {
    try { $credsStore = (Get-Content $dockerConfigPath -Raw | ConvertFrom-Json).credsStore } catch { $credsStore = $null }
  }
  if ($credsStore) {
    $helper = "docker-credential-$credsStore"
    $helperFault = $null

    if (-not (Get-Command $helper -ErrorAction SilentlyContinue)) {
      # A configured helper that is not installed is exactly as broken as one that fails, and Docker
      # reports both the same way. Skipping this case was a real hole: the preflight returned
      # HEALTHY for a config that cannot pull anything.
      $helperFault = "$helper is named by credsStore but is not on PATH"
    } else {
      # `list` is the cheapest call that still exercises the helper's backend.
      $null = "" | & $helper list 2>&1
      if ($LASTEXITCODE -ne 0) { $helperFault = "$helper exits $LASTEXITCODE" }
    }

    if ($helperFault) {
      return [pscustomobject]@{
        Condition = "CREDENTIAL_HELPER_BROKEN"
        Detail    = "credsStore '$credsStore' is configured in $dockerConfigPath but $helperFault. Image pulls will fail with 'error getting credentials'."
        Remedy    = "This environment pulls only public images, so the helper is not needed. Re-run with -RepairDockerCredentials to remove just the credsStore key (a backup is written alongside it), or remove that one key by hand."
        Repairable = $true
      }
    }
  }

  # 3. Can the runtime resolve and reach a registry?
  #
  # A pull of a tiny public image is the only honest test: it exercises DNS, egress and the
  # credential path together, which is exactly what the builds need.
  $pull = docker pull --quiet hello-world:latest 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0) { return (Get-DockerPullDiagnosis $pull $dockerConfigPath) }

  return [pscustomobject]@{ Condition = "HEALTHY"; Detail = "Docker responds and can pull from a registry."; Remedy = $null; Repairable = $false }
}

if (-not $SkipBuild) {
  Write-Host "== docker preflight ==" -ForegroundColor Cyan
  $docker = Test-DockerEnvironment
  Write-Host "  $($docker.Condition): $($docker.Detail)"

  if ($docker.Condition -eq "CREDENTIAL_HELPER_BROKEN" -and $RepairDockerCredentials) {
    # The single narrowest repair available: remove one key from the developer's own Docker config,
    # after backing the file up. Nothing else in the file is touched and nothing outside it is.
    $backup = "$dockerConfigPath.ramals-backup"
    Copy-Item $dockerConfigPath $backup -Force
    $cfg = Get-Content $dockerConfigPath -Raw | ConvertFrom-Json
    $cfg.PSObject.Properties.Remove("credsStore")
    ($cfg | ConvertTo-Json -Depth 20) | Set-Content $dockerConfigPath -Encoding utf8
    Write-Host "  REPAIRED: removed 'credsStore' from $dockerConfigPath" -ForegroundColor Yellow
    Write-Host "  backup:   $backup  (restore with: Copy-Item '$backup' '$dockerConfigPath' -Force)" -ForegroundColor Yellow
    $docker = Test-DockerEnvironment
    Write-Host "  re-checked -> $($docker.Condition)"
  }

  if ($docker.Condition -ne "HEALTHY") {
    throw @"
Container environment is not ready; stopping before the image builds.

  condition : $($docker.Condition)
  detail    : $($docker.Detail)
  remedy    : $($docker.Remedy)
"@
  }

  Write-Host "== images ==" -ForegroundColor Cyan
  foreach ($i in $ramalsImages) {
    # Push through localhost; the cluster pulls the same repository under the registry's in-network
    # name. Both names address one registry, so one push serves both.
    $push = "localhost:${RegistryPort}/$($i.Repo):$gitSha"
    Write-Host "building $($i.Repo)" -ForegroundColor DarkCyan

    # web-ui's VITE_* values are inlined at build time, so the OIDC issuer is a property of the
    # image. VITE_API_BASE_URL must be EMPTY: api.ts already prefixes every path with /api/v1, so a
    # non-empty base produces /api/api/v1/... -- a route Spring has no mapping for, and the only
    # symptom is a 404 the UI renders as "Not found".
    $buildArgs = @()
    if ($i.Repo -eq "ramals-web-ui") {
      $buildArgs = @(
        "--build-arg", "VITE_KEYCLOAK_URL=http://keycloak.localhost:${IngressPort}",
        "--build-arg", "VITE_API_BASE_URL="
      )
    }
    docker build @buildArgs -t $push -f $i.File . | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "build failed: $($i.Repo)" }
    docker push $push | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "push failed: $($i.Repo)" }
  }
}

Write-Host "== verify images ==" -ForegroundColor Cyan
# Runs on BOTH paths. After a build it confirms the pushes landed; with -SkipBuild it is the only
# thing standing between a developer and a healthy environment repointed at tags that do not exist.
Assert-ImagesPresent $gitSha

Write-Host "== cluster DNS ==" -ForegroundColor Cyan
# The browser and the platform must agree on ONE issuer URL. `keycloak.localhost` resolves to
# 127.0.0.1 in browsers for free (RFC 6761); this rewrite makes the same name resolve to the
# keycloak Service inside the cluster, so Keycloak stamps `iss` with a host the platform can also
# fetch JWKS from. Without it, login succeeds in the browser and every API call then 401s.
@"
apiVersion: v1
kind: ConfigMap
metadata:
  name: coredns-custom
  namespace: kube-system
data:
  ramals.override: |
    rewrite name keycloak.localhost keycloak.$Namespace.svc.cluster.local
"@ | kubectl apply -f - | Out-Host
kubectl -n kube-system rollout restart deployment/coredns | Out-Host
kubectl -n kube-system rollout status deployment/coredns --timeout=120s | Out-Host

Write-Host "== secrets ==" -ForegroundColor Cyan
kubectl apply -f deploy/k8s/dev/namespace.yaml | Out-Host

# Generated in memory, never written to disk and never echoed. Re-running keeps the existing Secret
# so a redeploy does not invalidate the passwords PostgreSQL already initialised its roles with.
function New-RandomSecret {
  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  [Convert]::ToBase64String($bytes)
}

if (-not (kubectl get secret ramals-dev-runtime -n $Namespace --ignore-not-found -o name)) {
  kubectl create secret generic ramals-dev-runtime -n $Namespace `
    --from-literal=db-admin-password=$(New-RandomSecret) `
    --from-literal=db-migration-password=$(New-RandomSecret) `
    --from-literal=db-runtime-password=$(New-RandomSecret) `
    --from-literal=keycloak-db-password=$(New-RandomSecret) `
    --from-literal=keycloak-admin-password=$(New-RandomSecret) `
    --dry-run=client -o yaml | kubectl apply -f - | Out-Host
} else {
  Write-Host "secret ramals-dev-runtime already exists (kept)"
}

Write-Host "== deploy ==" -ForegroundColor Cyan
# kustomization.yaml pins a tag so that `kubectl kustomize` alone renders something valid and
# reviewable. That committed tag is whatever commit last touched the package, which is almost never
# the commit you are deploying -- so the tag is re-pointed here, to the sha actually built above.
#
# The pattern matches the tag on this registry's images only; it cannot touch the digest-pinned
# upstream base images, which must not be rewritten.
$rendered = (kubectl kustomize deploy/k8s/dev) -join "`n"
$rendered = [regex]::Replace(
  $rendered,
  "(?<repo>${registryHost}:${RegistryPort}/[A-Za-z0-9._/-]+):[A-Za-z0-9._-]+",
  "`${repo}:$gitSha")
$rendered | kubectl apply -f - | Out-Host
# PowerShell does not stop on a native command's non-zero exit, so every mandatory one is checked.
# Without this, a failed apply would fall straight through to "Ready".
if ($LASTEXITCODE -ne 0) { throw "kubectl apply failed; the cluster may be partially updated." }

if ($EnableOpenAI) {
  Write-Host "== live provider execution (opt-in) ==" -ForegroundColor Yellow

  # Read from the User environment in the registry rather than $env:, because a long-lived shell
  # inherited its environment when it started and will happily hand back a key you rotated an hour
  # ago. That exact staleness produced a 401 against a key that was perfectly valid.
  $key = [Environment]::GetEnvironmentVariable("RAMALS_AI_PROVIDER_API_KEY", "User")
  if (-not $key) { $key = [Environment]::GetEnvironmentVariable("RAMALS_AI_PROVIDER_API_KEY", "Machine") }
  if (-not $key) {
    throw "-EnableOpenAI needs RAMALS_AI_PROVIDER_API_KEY in the User or Machine environment. Set it, then re-run."
  }

  # The value is piped straight into kubectl and never written to a file, a log, or the console.
  kubectl -n $Namespace create secret generic ramals-ai-provider `
    --from-literal=provider-api-key=$key --dry-run=client -o yaml | kubectl apply -f - | Out-Host

  # Applied to the live Deployment rather than to the manifests, so the committed default stays off
  # and a later `kubectl apply -k` does not silently re-enable billable calls for someone else.
  kubectl -n $Namespace set env deployment/ramals-ai `
    RAMALS_AI_AI_ENABLED=true RAMALS_AI_MODEL_ROUTE=diagnostic-default | Out-Host

  Write-Host "OpenAI enabled: routes pinned to gpt-4.1-2025-04-14. This makes real, billable calls." -ForegroundColor Yellow
}

Write-Host "== waiting for workloads ==" -ForegroundColor Cyan
foreach ($w in @(
  @{ Ref = "statefulset/postgres";        Timeout = "300s" },
  @{ Ref = "deployment/keycloak";         Timeout = "300s" },
  @{ Ref = "deployment/ramals-ai";        Timeout = "300s" },
  @{ Ref = "deployment/learning-platform"; Timeout = "420s" },
  @{ Ref = "deployment/web-ui";           Timeout = "300s" })) {
  kubectl rollout status $w.Ref -n $Namespace --timeout=$($w.Timeout) | Out-Host
  # A timed-out rollout is a failure. Printing "Ready" after one is how a broken environment gets
  # handed to somebody as a working one.
  if ($LASTEXITCODE -ne 0) { throw "$($w.Ref) did not become ready within $($w.Timeout)." }
}

Write-Host "== local test users ==" -ForegroundColor Cyan
#
# Two users that exist so a developer can log in immediately. They are NOT in the realm import and
# their passwords are NOT in this repository: a password committed to Git is a password that
# outlives the cluster it was for, gets copied into somewhere that matters, and cannot be rotated by
# deleting a namespace.
#
# Instead the passwords are generated here and kept in a Kubernetes Secret, which makes the whole
# thing idempotent in the way that matters: a second bootstrap finds the Secret, reuses the same
# passwords, and reconciles Keycloak to match. A developer who wrote the password down yesterday can
# still log in today. A teardown destroys the Secret with the cluster, and the next bootstrap mints
# fresh ones.
$testUsers = @(
  @{ Username = "ramals-admin";   Role = "ADMIN";   First = "Ramals"; Last = "Admin"   },
  @{ Username = "ramals-learner"; Role = "LEARNER"; First = "Ramals"; Last = "Learner" }
)

if (-not (kubectl get secret ramals-dev-test-users -n $Namespace --ignore-not-found -o name)) {
  # Not $args -- that is an automatic variable in PowerShell and assigning to it is a trap.
  $secretArgs = @("create", "secret", "generic", "ramals-dev-test-users", "-n", $Namespace)
  foreach ($u in $testUsers) { $secretArgs += "--from-literal=$($u.Username)=$(New-RandomSecret)" }
  kubectl @secretArgs --dry-run=client -o yaml | kubectl apply -f - | Out-Host
  Write-Host "  generated new passwords"
} else {
  Write-Host "  reusing passwords already in secret/ramals-dev-test-users"
}

$keycloakPod = kubectl get pod -n $Namespace -l app.kubernetes.io/name=keycloak -o jsonpath='{.items[0].metadata.name}'
foreach ($u in $testUsers) {
  $password = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
    (kubectl get secret ramals-dev-test-users -n $Namespace -o jsonpath="{.data.$($u.Username)}")))

  # The password reaches kcadm on stdin, never as a command-line argument: argv is visible to
  # anything that can read /proc in that container, and would also land in shell history.
  $script = @'
set -e
read -r PW
# PowerShell writes CRLF into this pipe on Windows. `read` consumes the newline but keeps the
# carriage return, so Keycloak would store the password with a trailing control character while the
# Secret holds it without one. The two never match, and every login then fails with "Invalid
# username or password" -- an error that points at the credential, the user, the realm, anything
# except the pipe that mangled it. Strip trailing control characters rather than trusting the pipe.
PW=$(printf %s "$PW" | sed -e "s/[[:cntrl:]]*$//")
K=/opt/keycloak/bin/kcadm.sh
A="--no-config --server http://localhost:8080 --realm master --user $KC_BOOTSTRAP_ADMIN_USERNAME --password $KC_BOOTSTRAP_ADMIN_PASSWORD"
if [ -z "$($K get users -r ramals -q username=__USER__ --fields id --format csv --noquotes $A 2>/dev/null | head -1)" ]; then
  $K create users -r ramals $A -s username=__USER__ -s enabled=true -s emailVerified=true \
    -s email=__USER__@ramals.local -s firstName=__FIRST__ -s lastName=__LAST__ >/dev/null
  echo "created"
else
  echo "exists"
fi
$K set-password -r ramals --username __USER__ --new-password "$PW" $A >/dev/null
$K add-roles -r ramals --uusername __USER__ --rolename __ROLE__ $A >/dev/null 2>&1 || true
'@ -replace '__USER__', $u.Username -replace '__ROLE__', $u.Role -replace '__FIRST__', $u.First -replace '__LAST__', $u.Last

  $raw = ($password | kubectl exec -i -n $Namespace $keycloakPod -- sh -c $script 2>&1 | Out-String)
  $outcome = ($raw -split "`n" | Where-Object { $_ -match '^(created|exists)\s*$' } | Select-Object -First 1)

  if (-not $outcome) {
    # Silence here used to look like success. It is not: if kcadm could not be reached the user does
    # not exist, and the developer only finds out at the login screen.
    throw "Failed to reconcile Keycloak user '$($u.Username)'. kcadm said:`n$raw"
  }
  Write-Host ("  {0,-16} role={1,-8} {2}" -f $u.Username, $u.Role, $outcome.Trim())
}

kubectl get pods -n $Namespace -o wide | Out-Host

Write-Host ""
Write-Host "== developer access ==" -ForegroundColor Green
Write-Host "  RAMALS UI  : http://localhost:$IngressPort"
Write-Host "  Keycloak   : http://keycloak.localhost:$IngressPort"
Write-Host ""
foreach ($u in $testUsers) {
  if ($ShowTestCredentials) {
    $password = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
      (kubectl get secret ramals-dev-test-users -n $Namespace -o jsonpath="{.data.$($u.Username)}")))
    Write-Host ("  {0,-16} ({1,-7}) {2}" -f $u.Username, $u.Role, $password)
  } else {
    # Not printed by default. This script's other generated passwords are never echoed, and a
    # credential in terminal scrollback outlives the moment it was useful -- it ends up in a
    # screenshot, a shared session, or a bug report. The retrieval command is one line away.
    Write-Host ("  {0,-16} ({1,-7}) kubectl get secret ramals-dev-test-users -n {2} -o jsonpath='{{.data.{0}}}' | base64 -d" -f $u.Username, $u.Role, $Namespace)
  }
}
if (-not $ShowTestCredentials) {
  Write-Host ""
  Write-Host "  (re-run with -ShowTestCredentials to print these inline)" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "Ready. Verify with: pwsh -File .\deploy\k8s\dev\smoke.ps1" -ForegroundColor Green
