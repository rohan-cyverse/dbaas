$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

if (-not (Test-Path ".env")) {
    throw "Create .env first: Copy-Item .env.example .env"
}

Get-Content ".env" | ForEach-Object {
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

$canonicalMetadataUrl = "jdbc:mysql://127.0.0.1:3307/dbaas_metadata_current_0972"
if ($env:METADATA_DB_URL -like "$canonicalMetadataUrl*") {
    $metadataTunnel = Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $metadataTunnel) {
        throw "VM metadata tunnel is not running. In another PowerShell window run .\open-vm-metadata-tunnel.ps1, then retry."
    }
}

$serverPort = 8080
if (-not [string]::IsNullOrWhiteSpace($env:SERVER_PORT)) {
    if (-not [int]::TryParse($env:SERVER_PORT, [ref] $serverPort) -or $serverPort -lt 1 -or $serverPort -gt 65535) {
        throw "SERVER_PORT must be a number between 1 and 65535."
    }
}

$listener = Get-NetTCPConnection -State Listen -LocalPort $serverPort -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -ne $listener) {
    $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    $ownerDescription = if ($null -ne $owner) {
        "$($owner.ProcessName) (PID $($listener.OwningProcess))"
    } else {
        "PID $($listener.OwningProcess)"
    }
    throw "Port $serverPort is already in use by $ownerDescription. Stop that process, or set SERVER_PORT=8081 in .env and run again."
}

& ".\mvnw.cmd" spring-boot:run
exit $LASTEXITCODE
