$ErrorActionPreference = "Stop"

$mavenVersion = "3.9.16"
$distributionUrl = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
$expectedSha512 = "ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3"
$wrapperRoot = Join-Path $env:USERPROFILE ".m2\wrapper\dists\cyfuture-maven-$mavenVersion"
$mavenHome = Join-Path $wrapperRoot "apache-maven-$mavenVersion"
$mavenCommand = Join-Path $mavenHome "bin\mvn.cmd"

if (Test-Path $mavenCommand) {
    exit 0
}

New-Item -ItemType Directory -Path $wrapperRoot -Force | Out-Null
$zipPath = Join-Path $wrapperRoot "apache-maven-$mavenVersion-bin.zip"

Write-Host "Maven is not installed globally. Downloading Apache Maven $mavenVersion once..."
Invoke-WebRequest -Uri $distributionUrl -OutFile $zipPath -UseBasicParsing

$actualSha512 = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA512).Hash.ToLowerInvariant()
if ($actualSha512 -ne $expectedSha512) {
    Remove-Item -LiteralPath $zipPath -Force
    throw "Maven download checksum verification failed"
}

Expand-Archive -LiteralPath $zipPath -DestinationPath $wrapperRoot -Force
Remove-Item -LiteralPath $zipPath -Force

if (-not (Test-Path $mavenCommand)) {
    throw "Maven Wrapper could not prepare $mavenCommand"
}

Write-Host "Apache Maven $mavenVersion is ready."
