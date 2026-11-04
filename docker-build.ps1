[CmdletBinding()]
param(
    [ValidateSet('8.1', '8.3')][string]$IgnitionTarget = '8.3',
    [string]$Branch,
    [string]$ModuleVersion = '0.1.0',
    [string]$ProjectSource = 'perspective-project',
    [string]$KeystorePath = (Join-Path $PSScriptRoot 'signing/ign-project-module.keystore'),
    [string]$SigningProperties = (Join-Path $PSScriptRoot 'signing/signing.properties'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist')
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if ([string]::IsNullOrWhiteSpace($Branch)) {
        $Branch = & git symbolic-ref --quiet --short HEAD
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Branch)) {
            throw 'Cannot determine branch. Supply -Branch explicitly for detached HEAD builds.'
        }
    }
    $commit = & git rev-parse --verify --quiet HEAD
    if ($LASTEXITCODE -ne 0) { $commit = 'uncommitted' }
    if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
        throw 'External signing keystore not found. Supply -KeystorePath.'
    }
    if (-not (Test-Path -LiteralPath $SigningProperties -PathType Leaf)) {
        throw 'External signing properties not found. Supply -SigningProperties; never put passwords on the command line.'
    }
    $keystore = (Resolve-Path -LiteralPath $KeystorePath).Path
    $properties = (Resolve-Path -LiteralPath $SigningProperties).Path
    $output = [System.IO.Path]::GetFullPath($OutputDirectory)
    $arguments = @(
        'buildx', 'build', '--progress=plain', '--target=output', '--no-cache-filter=signed',
        '--output', "type=local,dest=$output",
        '--build-arg', "PROJECT_BRANCH=$Branch",
        '--build-arg', "IGNITION_TARGET=$IgnitionTarget",
        '--build-arg', "MODULE_VERSION=$ModuleVersion",
        '--build-arg', "GIT_COMMIT=$commit",
        '--build-arg', "PROJECT_SOURCE=$ProjectSource",
        '--secret', "id=keystore,src=$keystore",
        '--secret', "id=signing_properties,src=$properties", '.'
    )
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker module build failed (exit $LASTEXITCODE)." }
    Write-Output "Signed Ignition $IgnitionTarget artifacts: $output"
} finally {
    Pop-Location
}