param(
    [string]$JavaHome,
    [string]$MySqlBin,
    [ValidateRange(1024,65535)][int]$Port = 33319,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) { $JavaHome = Join-Path $projectRoot 'tools\java\jdk-21.0.12.1+1' }
$repository = Join-Path $projectRoot 'tools\maven\repository'
$settings = Join-Path $projectRoot 'tools\maven\settings.xml'
foreach ($path in @((Join-Path $JavaHome 'bin\java.exe'),$settings)) { if (-not (Test-Path -LiteralPath $path)) { throw ('本地测试工具不存在：' + $path) } }
$temporaryRoot = Join-Path $projectRoot 'backend\.local\tmp'
New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
try {
    # 原脚本和测试 JVM 的临时产物统一放进项目，避免继续占用 C 盘工作目录。
    $env:TEMP = $temporaryRoot
    $env:TMP = $temporaryRoot
    $arguments = @('-s',$settings,"-Dmaven.repo.local=$repository","-Djava.io.tmpdir=$temporaryRoot")
    if ($Offline) { $arguments += '-o' }
    & (Join-Path $projectRoot 'backend\scripts\Test-LocalMySql.ps1') -JavaHome $JavaHome -MySqlBin $MySqlBin -Port $Port -MavenArguments $arguments
} finally {
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
}
