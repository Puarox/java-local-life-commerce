param([string]$RuntimeDir = (Join-Path $PSScriptRoot '..\.runtime'))

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime = [System.IO.Path]::GetFullPath($RuntimeDir)
if (-not (Test-Path -LiteralPath $runtime)) { return }
foreach ($file in Get-ChildItem -LiteralPath $runtime -Filter '*.pid') {
    $processId = [int](Get-Content -LiteralPath $file.FullName -Raw).Trim()
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if ($process -and $process.Name -eq 'java.exe' -and $process.CommandLine.Contains($root)) {
        Stop-Process -Id $processId
        Write-Output "stopped $($file.BaseName) pid=$processId"
    }
    Remove-Item -LiteralPath $file.FullName -Force
}
