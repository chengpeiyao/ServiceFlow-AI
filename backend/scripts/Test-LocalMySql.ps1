param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MySqlBin,
    [ValidateRange(1024,65535)][int]$Port = 33319,
    [string[]]$MavenArguments = @()
)
$ErrorActionPreference = 'Stop'
$backendPath = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw '请通过 -JavaHome 指定 JDK 21 目录' }
# --version 写入标准输出，避免 PowerShell 5.1 将正常版本信息当作终止错误。
$javaVersion = & (Join-Path $JavaHome 'bin\java.exe') --version | Out-String
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(openjdk|java) 21\.') { throw '本项目要求 JDK 21，请勿使用默认 JDK 17' }
if (-not $MySqlBin) { $MySqlBin = Split-Path -Parent (Get-Command mysql.exe -ErrorAction Stop).Source }
$mysqld = Join-Path $MySqlBin 'mysqld.exe'
$mysql = Join-Path $MySqlBin 'mysql.exe'
$mysqladmin = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($binary in @($mysqld,$mysql,$mysqladmin)) { if (-not (Test-Path -LiteralPath $binary)) { throw ('缺少 MySQL 程序：' + $binary) } }
$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,$Port)
try { $listener.Start() } finally { $listener.Stop() }
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$scratch = Join-Path $tempRoot ('ServiceFlowS1-' + [guid]::NewGuid().ToString('N'))
$dataDir = Join-Path $scratch 'data'
New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
$oldEnvironment = @{}
foreach ($name in @('JAVA_HOME','PATH','SERVICEFLOW_TEST_DB_URL','SERVICEFLOW_TEST_DB_USERNAME','SERVICEFLOW_TEST_DB_PASSWORD')) { $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$server = $null
$ready = $false
$originalLocation = Get-Location
try {
    & $mysqld --no-defaults --initialize-insecure "--datadir=$dataDir" "--log-error=$scratch\init.log" --innodb-buffer-pool-size=64M
    if ($LASTEXITCODE -ne 0) { throw '初始化独立 MySQL 失败，请查看临时 init.log' }
    $arguments = @('--no-defaults','--standalone',"--datadir=`"$dataDir`"","--port=$Port",'--bind-address=127.0.0.1','--mysqlx=OFF','--skip-log-bin','--innodb-buffer-pool-size=64M',"--log-error=`"$scratch\server.log`"","--pid-file=`"$scratch\mysqld.pid`"")
    $server = Start-Process -FilePath $mysqld -ArgumentList $arguments -WindowStyle Hidden -PassThru
    $clientArguments = @('--no-defaults','--protocol=TCP','--host=127.0.0.1',"--port=$Port",'--user=root','--skip-password','--connect-timeout=2','--batch','--raw','--skip-column-names')
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        $server.Refresh()
        if ($server.HasExited) { Get-Content -LiteralPath (Join-Path $scratch 'server.log') -Tail 15; throw '独立 MySQL 启动失败' }
        # 服务启动期间连接失败属于预期探测结果，仍用退出码和目录共同确认实例。
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $actualData = (& $mysql @clientArguments '--execute=SELECT @@datadir;' 2>$null | Out-String).Trim()
        } finally { $ErrorActionPreference = $previousErrorAction }
        if ($LASTEXITCODE -eq 0 -and [System.IO.Path]::GetFullPath($actualData.TrimEnd([char]92,[char]47)).Equals([System.IO.Path]::GetFullPath($dataDir),[StringComparison]::OrdinalIgnoreCase)) { $ready=$true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw '未能确认独立 MySQL 的数据目录，停止执行测试' }
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $mysql @clientArguments '--execute=CREATE DATABASE serviceflow_s1_test CHARACTER SET utf8mb4;'
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($LASTEXITCODE -ne 0) { throw '创建独立测试库失败' }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $oldEnvironment['PATH']
    $env:SERVICEFLOW_TEST_DB_URL = "jdbc:mysql://127.0.0.1:$Port/serviceflow_s1_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
    $env:SERVICEFLOW_TEST_DB_USERNAME = 'root'
    $env:SERVICEFLOW_TEST_DB_PASSWORD = ''
    Set-Location -LiteralPath $backendPath
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $maven -B -ntp @MavenArguments clean verify
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($LASTEXITCODE -ne 0) { throw 'S1 构建或集成测试失败，请查看 target/surefire-reports' }
} finally {
    Set-Location -LiteralPath $originalLocation.Path
    foreach ($name in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$oldEnvironment[$name],'Process') }
    $stopped = $true
    if ($ready) {
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $mysqladmin --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root --skip-password shutdown
        } finally { $ErrorActionPreference = $previousErrorAction }
        $stopped = $LASTEXITCODE -eq 0
    }
    if ($server -and -not $server.HasExited) { $stopped = $server.WaitForExit(15000) }
    $targetDir = Join-Path $backendPath 'target'
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    foreach ($logName in @('init.log','server.log')) { $logPath=Join-Path $scratch $logName; if (Test-Path -LiteralPath $logPath) { Copy-Item -LiteralPath $logPath -Destination (Join-Path $targetDir ('local-mysql-' + $logName)) } }
    # 仅清理本次创建的独立临时目录，禁止根据连接地址删除既有数据库目录。
    $resolvedScratch = [System.IO.Path]::GetFullPath($scratch)
    $expectedParent = [System.IO.Path]::GetDirectoryName($resolvedScratch).TrimEnd([char]92,[char]47)
    if ($stopped -and $expectedParent -eq $tempRoot.TrimEnd([char]92,[char]47) -and [System.IO.Path]::GetFileName($resolvedScratch).StartsWith('ServiceFlowS1-')) {
        Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
    } else { Write-Warning ('临时实例未确认退出或目录校验未通过，保留现场：' + $scratch) }
}
