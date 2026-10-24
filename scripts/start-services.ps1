param(
    [int]$DbPort = 3307,
    [string]$RuntimeDir = (Join-Path $PSScriptRoot '..\.runtime')
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime = [System.IO.Path]::GetFullPath($RuntimeDir)
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$env:DB_PORT = [string]$DbPort
if (-not $env:LOCAL_LIFE_ADMIN_USER_ID) { $env:LOCAL_LIFE_ADMIN_USER_ID = '1' }
if (-not $env:JWT_KEYSTORE_LOCATION) {
    $env:JWT_KEYSTORE_LOCATION = 'file:' + ((Join-Path $root 'dev-secrets\hmall.jks') -replace '\\', '/')
}
if (-not $env:JWT_KEYSTORE_PASSWORD) {
    throw 'Set JWT_KEYSTORE_PASSWORD in this PowerShell session before starting services.'
}
$java = (Get-Command java -ErrorAction Stop).Source
$services = @(
    @{ Name='user-service'; Port=8082 },
    @{ Name='item-service'; Port=8081 },
    @{ Name='cart-service'; Port=8083 },
    @{ Name='trade-service'; Port=8086 },
    @{ Name='pay-service'; Port=8087 },
    @{ Name='local-life-service'; Port=8088 },
    @{ Name='hm-gateway'; Port=8080 }
)
foreach ($service in $services) {
    $name = $service.Name
    $port = $service.Port
    $listening = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($listening) { Write-Output "$name port $port already listening"; continue }
    $jar = Join-Path $root "$name\target\$name-1.0.0.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar; run .\mvnw.cmd -DskipTests package first." }
    $args = @('--add-opens=java.base/java.lang=ALL-UNNAMED',
        '-Xms64m','-Xmx192m','-XX:MaxMetaspaceSize=180m','-jar',$jar,
        '--server.address=127.0.0.1','--spring.cloud.nacos.discovery.ip=127.0.0.1')
    if ($name -eq 'local-life-service') { $args += '--server.port=8088' }
    $process = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runtime "$name.out.log") `
        -RedirectStandardError (Join-Path $runtime "$name.err.log")
    Set-Content -LiteralPath (Join-Path $runtime "$name.pid") -Value $process.Id
    Write-Output "$name pid=$($process.Id) port=$port"
}
