param(
    [string]$RuntimeDir = 'F:\intern2026\.local-runtime',
    [int]$DbPort = 3307
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$zip = Join-Path $RuntimeDir 'seata-server-1.5.2.zip'
$seataHome = Join-Path $RuntimeDir 'seata-server-1.5.2\seata'
if (-not (Test-Path -LiteralPath (Join-Path $seataHome 'target\seata-server.jar'))) {
    if (-not (Test-Path -LiteralPath $zip)) {
        throw "Download the official Seata 1.5.2 zip to $zip before starting this stack."
    }
    Expand-Archive -LiteralPath $zip -DestinationPath (Split-Path $seataHome) -Force
}

$mysql = if ($env:MYSQL_CLIENT) { $env:MYSQL_CLIENT } else {
    Join-Path ${env:ProgramFiles} 'MySQL\MySQL Server 8.0\bin\mysql.exe'
}
if (-not (Test-Path -LiteralPath $mysql)) { throw "MySQL client not found: $mysql" }
if ($env:DB_PASSWORD) { $env:MYSQL_PWD = $env:DB_PASSWORD }
$sql = Join-Path $root 'deploy\sql\seata-undo-log.sql'
foreach ($database in @('hm-trade', 'hm-item', 'hm-cart', 'hm-user', 'hm-pay')) {
    Get-Content -LiteralPath $sql -Raw | & $mysql -h 127.0.0.1 -P $DbPort -u `
        $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' }) $database
    if ($LASTEXITCODE -ne 0) { throw "Seata undo_log setup failed for $database" }
}

if (-not (Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue)) {
    $logs = Join-Path $RuntimeDir 'service-logs'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $env:SERVER_ADDRESS = '127.0.0.1'
    $process = Start-Process -FilePath $java -ArgumentList @(
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.util=ALL-UNNAMED',
        '--add-opens=java.base/java.io=ALL-UNNAMED',
        '-Xms128m', '-Xmx512m', '-XX:MaxMetaspaceSize=256m',
        '-Dloader.path=lib', '-jar', 'target/seata-server.jar',
        '-h', '127.0.0.1', '-p', '8091', '-m', 'file'
    ) -WorkingDirectory $seataHome -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'seata.out.log') `
        -RedirectStandardError (Join-Path $logs 'seata.err.log')
    Write-Output "seata-server pid=$($process.Id)"
}
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if (Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue) {
        Write-Output 'seata-server port 8091 ready'
        return
    }
    Start-Sleep -Seconds 2
}
throw "Seata did not open port 8091; inspect $RuntimeDir\service-logs\seata.err.log"
