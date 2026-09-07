[CmdletBinding()]
param(
    [string]$VmHost = "111.118.185.239",
    [int]$SshPort = 2232,
    [int]$LocalPort = 3307
)

$ErrorActionPreference = "Stop"

if ($LocalPort -lt 1 -or $LocalPort -gt 65535) {
    throw "LocalPort must be between 1 and 65535."
}

$listener = Get-NetTCPConnection -State Listen -LocalPort $LocalPort -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -ne $listener) {
    throw "Local port $LocalPort is already in use. Stop its owner or choose another port."
}

$ssh = Get-Command ssh.exe -ErrorAction Stop
Write-Host "Opening VM MySQL tunnel on 127.0.0.1:$LocalPort. Keep this window open while running the app."

& $ssh.Source -N `
    -L "127.0.0.1:${LocalPort}:127.0.0.1:3306" `
    -o ExitOnForwardFailure=yes `
    -o ServerAliveInterval=30 `
    -o ServerAliveCountMax=3 `
    -p $SshPort "root@$VmHost"

exit $LASTEXITCODE
