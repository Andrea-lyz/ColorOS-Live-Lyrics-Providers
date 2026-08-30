param(
    [string] $RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [Parameter(Mandatory = $true)]
    [string] $OutputDir
)

$ErrorActionPreference = 'Stop'

$contractPath = Join-Path $RepoRoot 'release\v5-provider-matrix.json'
$validatorPath = Join-Path $RepoRoot 'scripts\validate-v5-release-contract.ps1'

& $validatorPath -RepoRoot $RepoRoot

$contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json
$resolvedOutputDir = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    [System.IO.Path]::GetFullPath($OutputDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $OutputDir))
}

New-Item -ItemType Directory -Path $resolvedOutputDir -Force | Out-Null
$existingApks = @(Get-ChildItem -LiteralPath $resolvedOutputDir -File -Filter '*.apk')
if ($existingApks.Count -ne 0) {
    throw "Provider output directory must not already contain APKs: $resolvedOutputDir"
}

$collected = @()
foreach ($provider in @($contract.providers)) {
    $releaseDir = Join-Path $RepoRoot "$($provider.module)\build\outputs\apk\release"
    if (-not (Test-Path -LiteralPath $releaseDir -PathType Container)) {
        throw "Missing release output directory for $($provider.module): $releaseDir"
    }

    $sourceApks = @(
        Get-ChildItem -LiteralPath $releaseDir -Recurse -File -Filter '*.apk' |
            Where-Object { $_.Name -notmatch '(?i)debug|unsigned' }
    )
    if ($sourceApks.Count -ne 1) {
        throw "Expected exactly one signed release APK for $($provider.module), found $($sourceApks.Count)."
    }

    $targetPath = Join-Path $resolvedOutputDir ([string]$provider.asset)
    Copy-Item -LiteralPath $sourceApks[0].FullName -Destination $targetPath
    $collected += Get-Item -LiteralPath $targetPath
}

if ($collected.Count -ne 12) {
    throw "Expected 12 Provider APKs, collected $($collected.Count)."
}

$duplicateNames = @($collected | Group-Object Name | Where-Object { $_.Count -ne 1 })
if ($duplicateNames.Count -ne 0) {
    throw 'Duplicate Provider asset names were collected.'
}

Copy-Item -LiteralPath $contractPath -Destination (Join-Path $resolvedOutputDir 'v5-provider-matrix.json')
Write-Output "Collected $($collected.Count) signed Provider release APKs into $resolvedOutputDir."
