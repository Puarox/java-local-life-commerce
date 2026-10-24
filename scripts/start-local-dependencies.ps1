param([int]$DbPort = 3307)

$ErrorActionPreference = 'Stop'
$runtime = 'F:\intern2026\.local-runtime'
$logs = Join-Path $runtime 'service-logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

$java = (Get-Command java.exe -ErrorAction Stop).Source
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
$mysql = Join-Path ${env:ProgramFiles} 'MySQL\MySQL Server 8.0\bin\mysqld.exe'
$mysqlConfig = Join-Path $runtime 'mysql-test\my.ini'
if (-not (Get-NetTCPConnection -State Listen -LocalPort $DbPort -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath $mysql -ArgumentList @("--defaults-file=$($mysqlConfig -replace '\\','/')", '--console') `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'mysql.out.log') `
        -RedirectStandardError (Join-Path $logs 'mysql.err.log')
}

$redisHome = 'F:\Java\Project\takeout\redis'
if (-not (Get-NetTCPConnection -State Listen -LocalPort 6379 -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath (Join-Path $redisHome 'redis-server.exe') `
        -ArgumentList (Join-Path $redisHome 'redis.windows.conf') -WorkingDirectory $redisHome `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'redis.out.log') `
        -RedirectStandardError (Join-Path $logs 'redis.err.log')
}

$nacosBin = Join-Path $runtime 'nacos-server-2.2.3\nacos\bin'
if (-not (Get-NetTCPConnection -State Listen -LocalPort 8848 -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath (Join-Path $nacosBin 'startup.cmd') -ArgumentList @('-m', 'standalone') `
        -WorkingDirectory $nacosBin -WindowStyle Hidden
}

$env:ERLANG_HOME = Join-Path $runtime 'erlang-26'
$env:RABBITMQ_BASE = Join-Path $runtime 'rabbitmq-data'
$rabbit = Join-Path $runtime 'rabbitmq-3.13.7\rabbitmq_server-3.13.7\sbin\rabbitmq-server.bat'
if (-not (Get-NetTCPConnection -State Listen -LocalPort 5672 -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath $env:ComSpec -ArgumentList @('/c', "`"$rabbit`"") `
        -WorkingDirectory (Split-Path $rabbit) -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'rabbitmq.out.log') `
        -RedirectStandardError (Join-Path $logs 'rabbitmq.err.log')
}

$ports = @($DbPort, 6379, 8848, 5672)
foreach ($port in $ports) {
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw "Dependency did not open port $port. Check logs in $logs." }
    Write-Output "dependency port $port ready"
}

& (Join-Path $PSScriptRoot 'start-seata.ps1') -RuntimeDir $runtime -DbPort $DbPort
