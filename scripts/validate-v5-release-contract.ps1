param(
    [string] $RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

function Assert-Contract {
    param(
        [bool] $Condition,
        [string] $Message
    )
    if (-not $Condition) {
        throw "Provider release contract violation: $Message"
    }
}

function Match-RequiredValue {
    param(
        [string] $Content,
        [string] $Pattern,
        [string] $Description
    )
    $match = [regex]::Match($Content, $Pattern)
    Assert-Contract $match.Success "missing $Description"
    return $match.Groups[1].Value
}

function Read-KeyValueFile {
    param([string] $Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        Assert-Contract ($separator -gt 0) "invalid key/value line in $Path`: $trimmed"
        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        Assert-Contract (-not $values.ContainsKey($key)) "duplicate key in $Path`: $key"
        $values[$key] = $value
    }
    return $values
}

$contractPath = Join-Path $RepoRoot 'release\v5-provider-matrix.json'
$rootBuildPath = Join-Path $RepoRoot 'build.gradle.kts'
$settingsPath = Join-Path $RepoRoot 'settings.gradle.kts'
$versionCatalogPath = Join-Path $RepoRoot 'gradle\libs.versions.toml'

$contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json
$rootBuild = Get-Content -LiteralPath $rootBuildPath -Raw
$settings = Get-Content -LiteralPath $settingsPath -Raw
$versionCatalog = Get-Content -LiteralPath $versionCatalogPath -Raw
$providers = @($contract.providers)

Assert-Contract ($contract.schema -eq 2) 'unsupported schema'
Assert-Contract ($contract.suiteVersion -eq '4.1.0') 'unexpected suiteVersion'
Assert-Contract ($contract.sourceTag -eq 'providers-v1.1.0') 'unexpected sourceTag'
Assert-Contract ($providers.Count -eq 12) 'matrix must contain exactly 12 Providers'
Assert-Contract ($contract.minSdk -eq 27) 'matrix minSdk must be 27'
Assert-Contract ($contract.compileSdk -eq 37) 'matrix compileSdk must be 37'
Assert-Contract ($contract.targetSdk -eq 37) 'matrix targetSdk must be 37'
Assert-Contract ($contract.xposedMinApiVersion -eq 102) 'xposedMinApiVersion must be 102'
Assert-Contract ($contract.xposedTargetApiVersion -eq 102) 'xposedTargetApiVersion must be 102'
Assert-Contract ($contract.xposedStaticScope -eq $true) 'xposedStaticScope must be true'
Assert-Contract ($contract.xposedExceptionMode -eq 'protective') 'xposedExceptionMode must be protective'
Assert-Contract ($contract.xposedAutoHotReload -eq $false) 'xposedAutoHotReload must be false'
Assert-Contract ($contract.bundleAsset -eq "ColorOS-Live-Lyrics-Providers-v$($contract.suiteVersion).zip") 'bundle asset differs from suite version'
Assert-Contract ($versionCatalog -match 'libxposedApi\s*=\s*"102\.0\.0"') 'libxposed API dependency must be 102.0.0'
Assert-Contract ($versionCatalog -match 'libxposedService\s*=\s*"102\.0\.0"') 'libxposed service dependency must be 102.0.0'

$requiredDocumentation = @(
    'README.md',
    'README-English.md',
    'docs\4.0\README.md',
    'docs\4.0\PROVIDER-ADAPTATION-GUIDE.md',
    'docs\4.0\PROVIDER-ADAPTATION-GUIDE.zh-CN.md',
    'docs\4.1\PHASE-0-BASELINE.md'
)
foreach ($relativePath in $requiredDocumentation) {
    $documentationPath = Join-Path $RepoRoot $relativePath
    Assert-Contract (Test-Path -LiteralPath $documentationPath -PathType Leaf) "required documentation is missing: $relativePath"
    $content = Get-Content -LiteralPath $documentationPath -Raw
    Assert-Contract (-not [string]::IsNullOrWhiteSpace($content)) "required documentation is empty: $relativePath"
}
foreach ($relativePath in @('README.md', 'README-English.md', 'docs\4.0\PROVIDER-ADAPTATION-GUIDE.md', 'docs\4.0\PROVIDER-ADAPTATION-GUIDE.zh-CN.md')) {
    $content = Get-Content -LiteralPath (Join-Path $RepoRoot $relativePath) -Raw
    Assert-Contract ($content -notmatch '(?i)npatch|non-root') "public README/guide contains an internal abandoned-route term: $relativePath"
}
Assert-Contract ((Get-Content -LiteralPath (Join-Path $RepoRoot 'README.md') -Raw).Contains('PROVIDER-ADAPTATION-GUIDE.zh-CN.md')) 'Chinese README does not link the adaptation guide'
Assert-Contract ((Get-Content -LiteralPath (Join-Path $RepoRoot 'README-English.md') -Raw).Contains('PROVIDER-ADAPTATION-GUIDE.md')) 'English README does not link the adaptation guide'

$rootCompileSdk = [int](Match-RequiredValue $rootBuild 'extra\["compileSdkVersion"\]\s*=\s*(\d+)' 'root compileSdkVersion')
$rootTargetSdk = [int](Match-RequiredValue $rootBuild 'extra\["targetSdkVersion"\]\s*=\s*(\d+)' 'root targetSdkVersion')
Assert-Contract ($rootCompileSdk -eq $contract.compileSdk) 'root compileSdk differs from contract'
Assert-Contract ($rootTargetSdk -eq $contract.targetSdk) 'root targetSdk differs from contract'

$matrixBlock = Match-RequiredValue $rootBuild '(?s)val v5ProviderModules = listOf\((.*?)\)\s*\r?\n\r?\nval releaseSigningEnvironment' 'v5ProviderModules block'
$declaredModules = @(
    [regex]::Matches($matrixBlock, '"(:[^"\r\n]+)"') |
        ForEach-Object { $_.Groups[1].Value.TrimStart(':') }
)
$contractModules = @($providers | ForEach-Object { [string]$_.module })
Assert-Contract ($declaredModules.Count -eq $contractModules.Count) 'Gradle matrix count differs from contract'
Assert-Contract (-not (Compare-Object ($declaredModules | Sort-Object) ($contractModules | Sort-Object))) 'Gradle matrix modules differ from contract'

$applicationIds = @($providers | ForEach-Object { [string]$_.applicationId })
$assets = @($providers | ForEach-Object { [string]$_.asset })
$entries = @($providers | ForEach-Object { [string]$_.entryClass })
Assert-Contract (($applicationIds | Select-Object -Unique).Count -eq $providers.Count) 'duplicate applicationId'
Assert-Contract (($assets | Select-Object -Unique).Count -eq $providers.Count) 'duplicate asset name'
Assert-Contract (($entries | Select-Object -Unique).Count -eq $providers.Count) 'duplicate entryClass'

$expectedModuleProp = [ordered]@{
    minApiVersion = [string]$contract.xposedMinApiVersion
    targetApiVersion = [string]$contract.xposedTargetApiVersion
    staticScope = ([string]$contract.xposedStaticScope).ToLowerInvariant()
    exceptionMode = [string]$contract.xposedExceptionMode
    autoHotReload = ([string]$contract.xposedAutoHotReload).ToLowerInvariant()
}

foreach ($provider in $providers) {
    $module = [string]$provider.module
    $moduleDir = Join-Path $RepoRoot $module
    $buildFilePath = Join-Path $moduleDir 'build.gradle.kts'
    $manifestPath = Join-Path $moduleDir 'src\main\AndroidManifest.xml'
    $xposedResourceDir = Join-Path $moduleDir 'src\main\resources\META-INF\xposed'
    $modulePropPath = Join-Path $xposedResourceDir 'module.prop'
    $entryListPath = Join-Path $xposedResourceDir 'java_init.list'
    $scopeListPath = Join-Path $xposedResourceDir 'scope.list'
    $evidencePath = Join-Path $RepoRoot ([string]$provider.processPolicy.evidence)

    Assert-Contract (Test-Path -LiteralPath $moduleDir -PathType Container) "module directory missing: $module"
    foreach ($requiredPath in @($buildFilePath, $manifestPath, $modulePropPath, $entryListPath, $scopeListPath, $evidencePath)) {
        Assert-Contract (Test-Path -LiteralPath $requiredPath -PathType Leaf) "required module file missing: $requiredPath"
    }
    $settingsNeedle = 'include(":' + $module + '")'
    Assert-Contract ($settings.Contains($settingsNeedle)) "settings.gradle.kts does not include $module"

    $buildFile = Get-Content -LiteralPath $buildFilePath -Raw
    $applicationId = Match-RequiredValue $buildFile 'applicationId\s*=\s*"([^"]+)"' "$module applicationId"
    $versionName = Match-RequiredValue $buildFile 'versionName\s*=\s*"([^"]+)"' "$module versionName"
    $versionCode = [int](Match-RequiredValue $buildFile 'versionCode\s*=\s*(\d+)' "$module versionCode")
    $minSdk = [int](Match-RequiredValue $buildFile 'minSdk\s*=\s*(\d+)' "$module minSdk")

    Assert-Contract ($applicationId -eq $provider.applicationId) "$module applicationId differs"
    Assert-Contract ($versionName -eq $provider.versionName) "$module versionName differs"
    Assert-Contract ($versionCode -eq $provider.versionCode) "$module versionCode differs"
    Assert-Contract ($minSdk -eq $contract.minSdk) "$module minSdk differs"
    Assert-Contract ($provider.asset -eq "ColorOS-Live-Lyrics-Provider-$($provider.displayName)-v$($contract.suiteVersion).apk") "$module asset name differs"
    Assert-Contract ($buildFile.Contains('gradle/provider-app-convention.gradle.kts')) "$module does not apply the API 102 app convention"

    $actualModuleProp = Read-KeyValueFile $modulePropPath
    Assert-Contract ($actualModuleProp.Count -eq $expectedModuleProp.Count) "$module module.prop field count differs"
    foreach ($key in $expectedModuleProp.Keys) {
        Assert-Contract ($actualModuleProp.ContainsKey($key)) "$module module.prop is missing $key"
        Assert-Contract ($actualModuleProp[$key] -eq $expectedModuleProp[$key]) "$module module.prop differs for $key"
    }

    $entryClasses = @(
        Get-Content -LiteralPath $entryListPath |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    Assert-Contract ($entryClasses.Count -eq 1) "$module java_init.list must contain exactly one entry"
    Assert-Contract ($entryClasses[0] -eq [string]$provider.entryClass) "$module java_init.list differs from entryClass"
    $entryRelativePath = ([string]$provider.entryClass).Replace('.', [System.IO.Path]::DirectorySeparatorChar) + '.kt'
    $entrySourcePath = Join-Path $moduleDir (Join-Path 'src\main\kotlin' $entryRelativePath)
    Assert-Contract (Test-Path -LiteralPath $entrySourcePath -PathType Leaf) "$module entry source is missing: $entrySourcePath"

    $actualScopes = @(
        Get-Content -LiteralPath $scopeListPath |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    $expectedScopes = @($provider.scopes | ForEach-Object { [string]$_ })
    Assert-Contract ($actualScopes.Count -eq ($actualScopes | Select-Object -Unique).Count) "$module scope.list contains duplicates"
    Assert-Contract ($actualScopes.Count -eq $expectedScopes.Count) "$module scope count differs"
    Assert-Contract (-not (Compare-Object ($actualScopes | Sort-Object) ($expectedScopes | Sort-Object))) "$module scope.list differs"

    $manifest = Get-Content -LiteralPath $manifestPath -Raw
    foreach ($legacyMetadata in @('xposedmodule', 'xposeddescription', 'xposedminversion', 'xposedsharedprefs', 'xposedscope')) {
        Assert-Contract (-not $manifest.Contains('android:name="' + $legacyMetadata + '"')) "$module manifest contains legacy metadata: $legacyMetadata"
    }
    Assert-Contract ($manifest.Contains('ProviderModuleApplication')) "$module manifest does not use ProviderModuleApplication"
    Assert-Contract (-not (Test-Path -LiteralPath (Join-Path $moduleDir 'src\main\assets\xposed_init'))) "$module contains legacy assets/xposed_init"
    Assert-Contract (-not (Test-Path -LiteralPath (Join-Path $moduleDir 'src\main\resources\META-INF\yukihookapi_init'))) "$module contains legacy yukihookapi_init"

    foreach ($validatedHost in @($provider.validatedHosts)) {
        Assert-Contract ($expectedScopes -contains [string]$validatedHost.package) "$module validated host is outside scope: $($validatedHost.package)"
    }
}

$forbiddenRuntimeStrings = @(
    'de.robv.android.xposed',
    'XC_MethodHook',
    'XposedBridge',
    'XposedHelpers',
    'XSharedPreferences',
    'com.highcapable.yukihookapi',
    'IYukiHookXposedInit',
    'YukiBaseHooker',
    'InjectYukiHookWithXposed',
    'MODE_WORLD_READABLE',
    'xposedsharedprefs',
    'xposedminversion',
    'xposedmodule',
    'xposedscope',
    'assets/xposed_init'
)
$runtimeRoots = @(
    'provider-core', 'provider-hook-api102', 'provider-settings-api102', 'reflection-core',
    'parser-lrc', 'parser-qrc', 'parser-yrc', 'parser-krc', 'parser-ttml', 'share'
) + $contractModules
$runtimeFiles = @()
foreach ($relativeRoot in $runtimeRoots) {
    $sourceRoot = Join-Path $RepoRoot (Join-Path $relativeRoot 'src\main')
    if (Test-Path -LiteralPath $sourceRoot -PathType Container) {
        $runtimeFiles += Get-ChildItem -LiteralPath $sourceRoot -Recurse -File |
            Where-Object { $_.Extension -in @('.kt', '.java', '.xml', '.kts', '.toml', '.pro', '.properties', '.list') }
    }
    $moduleBuildFile = Join-Path $RepoRoot (Join-Path $relativeRoot 'build.gradle.kts')
    if (Test-Path -LiteralPath $moduleBuildFile -PathType Leaf) {
        $runtimeFiles += Get-Item -LiteralPath $moduleBuildFile
    }
}
$runtimeFiles += Get-Item -LiteralPath $rootBuildPath
$runtimeFiles += Get-Item -LiteralPath $settingsPath
$runtimeFiles += Get-Item -LiteralPath $versionCatalogPath
foreach ($file in @($runtimeFiles | Sort-Object FullName -Unique)) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($forbidden in $forbiddenRuntimeStrings) {
        Assert-Contract (-not $content.Contains($forbidden)) "legacy runtime string '$forbidden' found in $($file.FullName)"
    }
}

Write-Output "Provider release contract is valid: $($providers.Count) API-102 modules, suite=$($contract.suiteVersion), tag=$($contract.sourceTag)."
