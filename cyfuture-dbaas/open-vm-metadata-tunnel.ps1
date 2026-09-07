[CmdletBinding()]
param(
    [string]$VmHost = $env:METADATA_TUNNEL_VM_HOST,
    [int]$SshPort = 0,
    [int]$LocalPort = 0,
    [string]$LocalBindHost = $env:METADATA_TUNNEL_BIND_HOST,
    [string]$MetadataDbHost = $env:METADATA_DB_HOST,
    [int]$MetadataDbPort = 0
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $projectRoot ".env"

if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $parts = $line.Split("=", 2)
            if ($parts.Count -eq 2) {
                $value = $parts[1].Trim()
                if ($value.Length -ge 2 -and
                    (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                     ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                [Environment]::SetEnvironmentVariable($parts[0], $value, "Process")
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($VmHost)) {
    $VmHost = $env:METADATA_TUNNEL_VM_HOST
}
if ([string]::IsNullOrWhiteSpace($LocalBindHost)) {
    $LocalBindHost = $env:METADATA_TUNNEL_BIND_HOST
}
if ([string]::IsNullOrWhiteSpace($MetadataDbHost)) {
    $MetadataDbHost = $env:METADATA_DB_HOST
}

function Resolve-Port {
    param(
        [int]$ArgumentValue,
        [string]$EnvironmentValue,
        [int]$DefaultValue,
        [string]$Name
    )

    $resolved = $ArgumentValue
    if ($resolved -eq 0 -and -not [string]::IsNullOrWhiteSpace($EnvironmentValue)) {
        if (-not [int]::TryParse($EnvironmentValue, [ref] $resolved)) {
            throw "$Name must be a number between 1 and 65535."
        }
    }
    if ($resolved -eq 0) {
        $resolved = $DefaultValue
    }
    if ($resolved -lt 1 -or $resolved -gt 65535) {
        throw "$Name must be a number between 1 and 65535."
    }
    return $resolved
}

if ([string]::IsNullOrWhiteSpace($VmHost)) {
    throw "Set METADATA_TUNNEL_VM_HOST in .env or pass -VmHost."
}
if ([string]::IsNullOrWhiteSpace($MetadataDbHost)) {
    throw "Set METADATA_DB_HOST in .env or pass -MetadataDbHost."
}
if ([string]::IsNullOrWhiteSpace($LocalBindHost)) {
    $LocalBindHost = "localhost"
}
$SshPort = Resolve-Port $SshPort $env:METADATA_TUNNEL_SSH_PORT 22 "METADATA_TUNNEL_SSH_PORT"
$LocalPort = Resolve-Port $LocalPort $env:METADATA_DB_TUNNEL_PORT 3307 "METADATA_DB_TUNNEL_PORT"
$MetadataDbPort = Resolve-Port $MetadataDbPort $env:METADATA_DB_PORT 3306 "METADATA_DB_PORT"

$listener = Get-NetTCPConnection -State Listen -LocalPort $LocalPort -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -ne $listener) {
    throw "Local port $LocalPort is already in use. Stop its owner or choose another port."
}

$ssh = Get-Command ssh.exe -ErrorAction Stop
Write-Host "Opening central metadata tunnel on $($LocalBindHost):$LocalPort to $($MetadataDbHost):$MetadataDbPort. Keep this window open while running the app."

$forward = "$($LocalBindHost):$($LocalPort):$($MetadataDbHost):$($MetadataDbPort)"
& $ssh.Source -N -L $forward -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -p $SshPort "root@$VmHost"

exit $LASTEXITCODE
