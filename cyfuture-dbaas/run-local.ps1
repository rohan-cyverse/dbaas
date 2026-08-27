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

& ".\mvnw.cmd" spring-boot:run
exit $LASTEXITCODE
