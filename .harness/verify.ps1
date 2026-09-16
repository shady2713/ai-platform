[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $Gate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$TrivyImage = 'ghcr.io/aquasecurity/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
$CycloneDxGoal = 'org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom'

function Show-Usage {
    @'
Usage: & .\.harness\verify.ps1 <gate>

Gates:
  contracts    field/data contracts, engineering ratchets, exception expiry
  lockfile     frozen frontend dependency graph
  backend      backend compile, unit tests, formatting, coverage, architecture
  frontend     dependency/type/spelling checks, lint, and coverage tests
  dependencies resolved backend SBOM and frontend lockfile vulnerability scan
  integration  Docker-backed MySQL/Redis/Flyway and packaged-jar boot smoke
  verify       contracts + backend + frontend
  all          lockfile + verify + dependencies + integration
'@ | Write-Output
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,
        [Parameter()]
        [string[]] $Arguments = @()
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "harness: '$FilePath' exited with code $LASTEXITCODE"
    }
}

function Invoke-InDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [scriptblock] $Action
    )

    Push-Location -LiteralPath $Path
    try {
        & $Action
    } finally {
        Pop-Location
    }
}

function Invoke-ContractsGate {
    Invoke-InDirectory $RepoRoot {
        Invoke-External 'node' @('--test', 'scripts/check-coverage-ratchet.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-harness-topology.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-container-images.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-database-driver-boundary.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-removed-codegen-boundary.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-management-route-naming.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/run-frontend-build.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-field-injection.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-source-quality.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-starter-documentation.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-sensitive-tostring.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-sensitive-diff-log.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-safe-exception-handling.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-controller-validation.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/secret-scan.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-data-lifecycle.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-data-permission.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-permission-catalog.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-gate-wiring.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-security-signals.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-exceptions.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-field-catalog.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/gate-utils.test.mjs')
        Invoke-External 'node' @('--test', 'scripts/check-gate-rejection-tests.test.mjs')
        Invoke-External 'node' @('scripts/check-field-injection.mjs')
        Invoke-External 'node' @('scripts/check-source-quality.mjs')
        Invoke-External 'node' @('scripts/check-starter-documentation.mjs')
        Invoke-External 'node' @('scripts/check-sensitive-tostring.mjs')
        Invoke-External 'node' @('scripts/check-sensitive-diff-log.mjs')
        Invoke-External 'node' @('scripts/check-safe-exception-handling.mjs')
        Invoke-External 'node' @('scripts/check-controller-validation.mjs')
        Invoke-External 'node' @('scripts/check-field-catalog.mjs')
        Invoke-External 'node' @('scripts/check-data-lifecycle.mjs')
        Invoke-External 'node' @('scripts/check-data-permission.mjs')
        Invoke-External 'node' @('scripts/check-permission-catalog.mjs')
        Invoke-External 'node' @('scripts/check-gate-wiring.mjs')
        Invoke-External 'node' @('scripts/check-security-signals.mjs')
        Invoke-External 'node' @('scripts/check-gate-rejection-tests.mjs')
        Invoke-External 'node' @('scripts/check-exceptions.mjs')
    }
}

function Invoke-LockfileGate {
    Invoke-InDirectory (Join-Path $RepoRoot '前端代码/basic-framework-admin') {
        Invoke-External 'pnpm.cmd' @('install', '--frozen-lockfile', '--ignore-scripts')
    }
}

function Invoke-BackendGate {
    Invoke-InDirectory (Join-Path $RepoRoot '后端代码/basic-framework-boot') {
        Invoke-External '.\mvnw.cmd' @('-q', 'clean', 'verify')
    }
}

function Invoke-FrontendGate {
    Invoke-InDirectory (Join-Path $RepoRoot '前端代码/basic-framework-admin') {
        Invoke-External 'pnpm.cmd' @('check')
        Invoke-External 'pnpm.cmd' @('lint')
        Invoke-External 'pnpm.cmd' @('test:coverage')
        Invoke-External 'node' @('../../scripts/run-frontend-build.mjs')
    }
    Invoke-InDirectory $RepoRoot {
        Invoke-External 'node' @('scripts/check-coverage-ratchet.mjs', 'frontend')
    }
}

function Invoke-DependenciesGate {
    if ($null -eq (Get-Command 'docker' -ErrorAction SilentlyContinue)) {
        throw 'harness: dependencies requires the Docker CLI and daemon'
    }
    Invoke-External 'docker' @('info', '--format', 'Docker server {{.ServerVersion}}')

    Invoke-InDirectory (Join-Path $RepoRoot '后端代码/basic-framework-boot') {
        Invoke-External '.\mvnw.cmd' @('-q', '-pl', 'basic-framework-server', '-am', 'package', '-DskipTests')
        Invoke-External '.\mvnw.cmd' @(
            '-q',
            $CycloneDxGoal,
            '-DskipTests',
            '-DoutputFormat=json',
            '-DincludeTestScope=true',
            '-DskipAttach=true'
        )
    }

    $repositoryMount = "type=bind,source=$RepoRoot,target=/workspace,readonly"
    $cacheMount = 'type=volume,source=basic-framework-trivy-cache,target=/root/.cache/trivy'
    $backendTarget = Join-Path $RepoRoot '后端代码/basic-framework-boot/target'
    $imageArchive = Join-Path $backendTarget 'basic-framework-server-image.tar'
    $imageReport = Join-Path $backendTarget 'trivy-image-report.txt'
    Invoke-External 'docker' @(
        'buildx', 'build',
        '--pull=false',
        '--load',
        '-t', 'basic-framework-server:dependency-scan',
        (Join-Path $RepoRoot '后端代码/basic-framework-boot/basic-framework-server')
    )
    Invoke-External 'docker' @(
        'save',
        'basic-framework-server:dependency-scan',
        '-o', $imageArchive
    )

    $containerArguments = @(
        'run',
        '--rm',
        '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true',
        '--mount', $repositoryMount,
        '--mount', $cacheMount,
        $TrivyImage
    )
    $backendScanArguments = $containerArguments + @(
        '--quiet',
        'sbom',
        '--scanners', 'vuln',
        '--severity', 'HIGH,CRITICAL',
        '--exit-code', '1',
        '--no-progress',
        '/workspace/后端代码/basic-framework-boot/target/bom.json'
    )
    $frontendScanArguments = $containerArguments + @(
        '--quiet',
        'fs',
        '--scanners', 'vuln',
        '--include-dev-deps',
        '--severity', 'HIGH,CRITICAL',
        '--exit-code', '1',
        '--no-progress',
        '/workspace/前端代码/basic-framework-admin/pnpm-lock.yaml'
    )
    $configScanArguments = $containerArguments + @(
        '--quiet',
        'config',
        '--severity', 'HIGH,CRITICAL',
        '--exit-code', '1',
        '/workspace/后端代码/basic-framework-boot'
    )
    $imageScanArguments = @(
        'run',
        '--rm',
        '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true',
        '--mount', "type=bind,source=$backendTarget,target=/scan-output",
        '--mount', $cacheMount,
        $TrivyImage,
        '--quiet',
        'image',
        '--input', '/scan-output/basic-framework-server-image.tar',
        '--scanners', 'vuln',
        '--severity', 'HIGH,CRITICAL',
        '--exit-code', '1',
        '--no-progress',
        '--output', '/scan-output/trivy-image-report.txt'
    )
    Invoke-External 'docker' $backendScanArguments
    Invoke-External 'docker' $frontendScanArguments
    Invoke-External 'docker' $configScanArguments
    try {
        Invoke-External 'docker' $imageScanArguments
    } catch {
        if (Test-Path -LiteralPath $imageReport) {
            Get-Content -LiteralPath $imageReport | Write-Output
        }
        throw
    }
    Write-Output 'harness: application image scan passed'
}

function Invoke-IntegrationGate {
    $docker = Get-Command 'docker' -ErrorAction SilentlyContinue
    if ($null -ne $docker) {
        Invoke-External 'docker' @('info', '--format', 'Docker server {{.ServerVersion}}')
    } elseif ([string]::IsNullOrWhiteSpace($env:DOCKER_HOST)) {
        throw 'harness: integration requires a Docker daemon'
    } else {
        Write-Output "harness: Docker CLI unavailable; Testcontainers will verify $env:DOCKER_HOST"
    }

    Invoke-InDirectory (Join-Path $RepoRoot '后端代码/basic-framework-boot') {
        Invoke-External '.\mvnw.cmd' @('-q', '-Pintegration', 'clean', 'verify')
    }
    Invoke-InDirectory $RepoRoot {
        # 部分文件的覆盖率由 Testcontainers 集成测试产生，必须在完整报告生成后检查。
        Invoke-External 'node' @('scripts/check-coverage-ratchet.mjs', 'backend')
    }
}

function Invoke-HarnessGate {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    Write-Output "== harness:$Name =="
    switch ($Name) {
        'contracts' { Invoke-ContractsGate }
        'lockfile' { Invoke-LockfileGate }
        'backend' { Invoke-BackendGate }
        'frontend' { Invoke-FrontendGate }
        'dependencies' { Invoke-DependenciesGate }
        'integration' { Invoke-IntegrationGate }
        'verify' {
            Invoke-HarnessGate 'contracts'
            Invoke-HarnessGate 'backend'
            Invoke-HarnessGate 'frontend'
        }
        'all' {
            Invoke-HarnessGate 'lockfile'
            Invoke-HarnessGate 'verify'
            Invoke-HarnessGate 'dependencies'
            Invoke-HarnessGate 'integration'
        }
        { $_ -in @('--list', '-h', '--help') } { Show-Usage }
        default {
            Show-Usage
            throw "harness: unknown gate '$Name'"
        }
    }
}

Invoke-HarnessGate $Gate
