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

$contractPath = Join-Path $RepoRoot 'release\v5-provider-matrix.json'
$rootBuildPath = Join-Path $RepoRoot 'build.gradle.kts'
$settingsPath = Join-Path $RepoRoot 'settings.gradle.kts'

$contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json
$rootBuild = Get-Content -LiteralPath $rootBuildPath -Raw
$settings = Get-Content -LiteralPath $settingsPath -Raw
$providers = @($contract.providers)

Assert-Contract ($contract.schema -eq 1) 'unsupported schema'
Assert-Contract ($contract.suiteVersion -eq '4.0.0') 'unexpected suiteVersion'
Assert-Contract ($contract.sourceTag -eq 'providers-v1.0.0') 'unexpected sourceTag'
Assert-Contract ($providers.Count -eq 12) 'matrix must contain exactly 12 Providers'
Assert-Contract ($contract.minSdk -eq 27) 'matrix minSdk must be 27'
Assert-Contract ($contract.compileSdk -eq 37) 'matrix compileSdk must be 37'
Assert-Contract ($contract.targetSdk -eq 37) 'matrix targetSdk must be 37'
Assert-Contract ($contract.xposedMinVersion -eq 93) 'xposedMinVersion must be 93'
Assert-Contract ($contract.bundleAsset -eq "ColorOS-Live-Lyrics-Providers-v$($contract.suiteVersion).zip") 'bundle asset differs from suite version'

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
Assert-Contract (($applicationIds | Select-Object -Unique).Count -eq $providers.Count) 'duplicate applicationId'
Assert-Contract (($assets | Select-Object -Unique).Count -eq $providers.Count) 'duplicate asset name'

foreach ($provider in $providers) {
    $module = [string]$provider.module
    $moduleDir = Join-Path $RepoRoot $module
    $buildFilePath = Join-Path $moduleDir 'build.gradle.kts'
    $manifestPath = Join-Path $moduleDir 'src\main\AndroidManifest.xml'
    $scopePath = Join-Path $moduleDir 'src\main\res\values\arrays.xml'
    $evidencePath = Join-Path $RepoRoot ([string]$provider.processPolicy.evidence)

    Assert-Contract (Test-Path -LiteralPath $moduleDir -PathType Container) "module directory missing: $module"
    Assert-Contract (Test-Path -LiteralPath $buildFilePath -PathType Leaf) "build file missing: $module"
    Assert-Contract (Test-Path -LiteralPath $manifestPath -PathType Leaf) "manifest missing: $module"
    Assert-Contract (Test-Path -LiteralPath $scopePath -PathType Leaf) "scope array missing: $module"
    Assert-Contract (Test-Path -LiteralPath $evidencePath -PathType Leaf) "process evidence missing: $module"
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

    [xml]$scopeXml = Get-Content -LiteralPath $scopePath -Raw
    $scopeArray = @($scopeXml.resources.'string-array' | Where-Object { $_.name -eq 'xposed_scope' })
    Assert-Contract ($scopeArray.Count -eq 1) "$module must define one xposed_scope array"
    $actualScopes = @($scopeArray[0].item | ForEach-Object { [string]$_ })
    $expectedScopes = @($provider.scopes)
    Assert-Contract ($actualScopes.Count -eq $expectedScopes.Count) "$module scope count differs"
    for ($index = 0; $index -lt $expectedScopes.Count; $index++) {
        Assert-Contract ($actualScopes[$index] -eq $expectedScopes[$index]) "$module scope differs at index $index"
    }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw
    $xposedMinVersion = [int](Match-RequiredValue $manifest 'android:name="xposedminversion"\s+android:value="(\d+)"' "$module xposedminversion")
    Assert-Contract ($xposedMinVersion -eq $contract.xposedMinVersion) "$module xposedminversion differs"
    Assert-Contract ($manifest -match 'android:name="xposedmodule"\s+android:value="true"') "$module is not marked as Xposed module"
    Assert-Contract ($manifest -match 'android:name="xposedscope"\s+android:resource="@array/xposed_scope"') "$module does not reference xposed_scope"

    foreach ($validatedHost in @($provider.validatedHosts)) {
        Assert-Contract ($expectedScopes -contains [string]$validatedHost.package) "$module validated host is outside scope: $($validatedHost.package)"
    }
}

Write-Output "Provider release contract is valid: $($providers.Count) modules, suite=$($contract.suiteVersion), tag=$($contract.sourceTag)."
