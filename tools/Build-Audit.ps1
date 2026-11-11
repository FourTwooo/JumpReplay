param([switch]$Quick)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$candidates = @($env:JAVA_HOME)
$jdkDirectory = Join-Path $env:USERPROFILE '.jdks'
if (Test-Path -LiteralPath $jdkDirectory) {
    $candidates += @(Get-ChildItem -LiteralPath $jdkDirectory -Directory | ForEach-Object FullName)
}
$candidates += @('C:\Program Files\Android\Android Studio\jbr', 'D:\Android\Android Studio\jbr')
$selectedJdk = $null
foreach ($candidate in $candidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $compiler = Join-Path $candidate 'bin\javac.exe'
    if (!(Test-Path -LiteralPath $compiler)) { continue }
    $versionText = (& $compiler -version 2>&1 | Out-String)
    if ($versionText -match 'javac 17\.') { $selectedJdk = $candidate; break }
}
if (!$selectedJdk) { throw 'JDK 17 is required. Set JAVA_HOME to a JDK 17 installation.' }
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $repository
try {
    $env:JAVA_HOME = $selectedJdk
    if ($Quick) {
        & .\gradlew.bat :app:assembleDebug --console=plain
    } else {
        & .\gradlew.bat :app:assembleDebug :app:assembleRelease :blocker_template:assembleDebug `
            :app:testDebugUnitTest :blocker_template:testDebugUnitTest :app:lintDebug `
            :blocker_template:lintDebug :app:assembleDebugAndroidTest `
            :blocker_template:assembleDebugAndroidTest --console=plain
    }
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed. Read the Gradle output above.' }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
