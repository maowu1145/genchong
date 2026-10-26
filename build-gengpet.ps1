param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [switch]$TcpPipeFallback
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JDK 17 path is required.' }
$env:JAVA_HOME = $JavaHome
if ($SdkRoot) {
    if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms/android-36/android.jar'))) { throw 'Android SDK platform 36 is required.' }
    $env:ANDROID_HOME = $SdkRoot
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), 'sdk.dir=' + $SdkRoot.Replace('\','/').Replace(':','\:'))
}
if ($TcpPipeFallback) {
    $markerDir = Join-Path $PSScriptRoot 'work/build-tools'
    New-Item -ItemType Directory -Force -Path $markerDir | Out-Null
    $marker = Join-Path $markerDir 'uds-disabled'
    if (-not (Test-Path -LiteralPath $marker)) { [IO.File]::WriteAllText($marker, 'Use OpenJDK PipeImpl TCP fallback.') }
    if ((Get-Item -LiteralPath $marker).PSIsContainer) { throw 'TCP fallback marker must be a regular file.' }
    $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' "-Djdk.net.unixdomain.tmpdir=' + $marker + '"').Trim()
}
& ./gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed.' }