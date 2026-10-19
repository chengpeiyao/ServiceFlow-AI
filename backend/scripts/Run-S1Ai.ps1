param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('dev','fixed')][string]$Split = 'dev',
    [ValidatePattern('^A[012](,A[012])*$')][string]$Arms = 'A0',
    [string]$Config,
    [switch]$ConfirmFixed
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jarPath = Join-Path $projectPath 'backend\target\serviceflow-backend-0.1.0-SNAPSHOT.jar'
$javaPath = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaPath)) { throw '请通过 -JavaHome 指定 JDK 21' }
if ((& $javaPath -version 2>&1 | Out-String) -notmatch 'version "21\.') { throw '本项目要求 JDK 21' }
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先在 backend 执行 mvn verify 生成可运行 JAR' }
# 防止修改源码后误用旧 JAR 生成无法复现的评测。
$jarTime = (Get-Item -LiteralPath $jarPath).LastWriteTimeUtc
$newerSource = Get-ChildItem -LiteralPath (Join-Path $projectPath 'backend\src') -File -Recurse | Where-Object LastWriteTimeUtc -GT $jarTime | Select-Object -First 1
if ($newerSource -or (Get-Item -LiteralPath (Join-Path $projectPath 'backend\pom.xml')).LastWriteTimeUtc -gt $jarTime) { throw '源码或构建配置已更新，请先重新执行 mvn verify' }
if (-not $Config) { $candidate = Join-Path $projectPath 'config_al\model.local.properties'; if (Test-Path -LiteralPath $candidate) { $Config = $candidate } }
$arguments = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Dloader.main=com.serviceflow.experiment.ExperimentCli','-cp',$jarPath,'org.springframework.boot.loader.launch.PropertiesLauncher',
    '--root',(Join-Path $projectPath 'experiments\s1-ai'),'--split',$Split,'--arms',$Arms)
if ($Config) { $arguments += @('--config',$Config) }
if ($ConfirmFixed) { $arguments += @('--confirm-fixed','yes') }
& $javaPath @arguments
if ($LASTEXITCODE -ne 0) { throw '实验未完整结束，请检查错误及 runs 内已刷盘的记录' }
