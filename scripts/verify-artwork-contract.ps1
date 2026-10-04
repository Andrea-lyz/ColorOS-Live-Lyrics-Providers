param(
    [string] $RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string] $BridgeRepoRoot = '',
    [switch] $Required
)

# The artwork protocol v1 contract is canonical in the Bridge repository. This repository keeps a
# mirror so the Dynamic Artwork Provider builds standalone. Whenever a Bridge checkout is
# available, every file under artwork-contract/src must match byte for byte, so a protocol change
# cannot silently land on one side only. Bridge preview and release workflows run this with
# -Required, where both checkouts exist.

$ErrorActionPreference = 'Stop'

function Assert-Mirror {
    param(
        [bool] $Condition,
        [string] $Message
    )
    if (-not $Condition) {
        throw "Artwork contract mirror violation: $Message"
    }
}

if ([string]::IsNullOrWhiteSpace($BridgeRepoRoot)) {
    if ($env:BRIDGE_REPO_ROOT) {
        $BridgeRepoRoot = $env:BRIDGE_REPO_ROOT
    } else {
        # A substituted drive (R:\ mapping the repository root) has no usable parent path.
        $parent = Split-Path -Parent $RepoRoot
        $BridgeRepoRoot = if ([string]::IsNullOrWhiteSpace($parent)) { '' } else { Join-Path $parent 'ColorOS-Live-Lyrics-Bridge' }
    }
}

$mirrorRoot = Join-Path $RepoRoot 'artwork-contract/src'
Assert-Mirror (Test-Path -LiteralPath $mirrorRoot -PathType Container) "missing mirror sources: $mirrorRoot"
$canonicalRoot = if ([string]::IsNullOrWhiteSpace($BridgeRepoRoot)) { '' } else { Join-Path $BridgeRepoRoot 'artwork-contract/src' }
if ([string]::IsNullOrWhiteSpace($canonicalRoot) -or -not (Test-Path -LiteralPath $canonicalRoot -PathType Container)) {
    if ($Required) {
        throw "Artwork contract mirror violation: no Bridge checkout (pass -BridgeRepoRoot or set BRIDGE_REPO_ROOT)"
    }
    Write-Output "Artwork contract mirror not verified: no Bridge checkout at $BridgeRepoRoot (pass -BridgeRepoRoot or set BRIDGE_REPO_ROOT)."
    exit 0
}

function Get-RelativeFileMap {
    param([string] $Root)
    # Line endings are normalized before hashing: the two repositories check out with different
    # git eol settings, and a CRLF/LF difference does not change Java, AIDL, or XML semantics.
    $map = @{}
    foreach ($file in Get-ChildItem -LiteralPath $Root -Recurse -File) {
        $relative = $file.FullName.Substring($Root.Length).TrimStart([System.IO.Path]::DirectorySeparatorChar).Replace('\', '/')
        $text = [System.IO.File]::ReadAllText($file.FullName)
        $normalized = $text.Replace("`r`n", "`n").Replace("`r", "`n")
        $digest = [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($normalized))
        $map[$relative] = -join ($digest | ForEach-Object { $_.ToString('x2') })
    }
    return $map
}

$mirror = Get-RelativeFileMap $mirrorRoot
$canonical = Get-RelativeFileMap $canonicalRoot

$missing = @($canonical.Keys | Where-Object { -not $mirror.ContainsKey($_) } | Sort-Object)
$extra = @($mirror.Keys | Where-Object { -not $canonical.ContainsKey($_) } | Sort-Object)
Assert-Mirror ($missing.Count -eq 0) "mirror is missing: $($missing -join ', ')"
Assert-Mirror ($extra.Count -eq 0) "mirror has files absent from the canonical module: $($extra -join ', ')"

$differing = @(
    $canonical.Keys |
        Where-Object { $mirror.ContainsKey($_) -and $mirror[$_] -ne $canonical[$_] } |
        Sort-Object
)
Assert-Mirror ($differing.Count -eq 0) "mirror differs from $canonicalRoot for: $($differing -join ', ')"

Write-Output "Artwork contract mirror matches the Bridge module: $($canonical.Count) files verified against $canonicalRoot."
