param([string]$JdkPath = $env:JAVA_HOME, [switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not $JdkPath -or -not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin\java.exe'))) {
    throw 'Укажите путь к JDK 21+ через -JdkPath или JAVA_HOME. Обычного Java 8 недостаточно.'
}
$env:JAVA_HOME = $JdkPath
# Avoid Gradle worker classpath encoding issues in Windows directories with Cyrillic.
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'banketika-gradle'
$buildPath = Join-Path $env:TEMP 'banketika-build'
$task = if ($Test) { 'test' } else { 'run' }
& .\gradlew.bat $task "-PbanketikaBuildDir=$buildPath" --console=plain
exit $LASTEXITCODE
