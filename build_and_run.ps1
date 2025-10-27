#!/usr/bin/env pwsh
<#
    Windows helper for building and launching the Guess The Author server.
    Mirrors the behaviour of build_and_run.sh: ensures Maven is available,
    builds the shaded JAR, terminates existing instances, and runs the server.
#>

[CmdletBinding()]
param(
    [Alias('Database','DbPath')]
    [string] $DiscordDb,
    [string] $RoomName,
    [int]    $Port = 8080,
    [switch] $Help
)

if ($Help) {
    Write-Host @"
Usage:
  ./build_and_run.ps1 [-DiscordDb PATH] [-RoomName NAME] [-Port PORT]

Examples:
  ./build_and_run.ps1
  ./build_and_run.ps1 -DiscordDb C:\exports\discord_messages.db -RoomName "Friday Night" -Port 8080

Flags:
  -DiscordDb         Path to a Discord SQLite export (optional).
  -RoomName   Friendly display name for the seeded room (optional).
  -Port       HTTP port (default: 8080).
  -Help       Show this information.
"@
    exit 0
}

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

function Resolve-OptionalPath {
    param([string] $PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $null
    }
    if (Test-Path $PathValue) {
        return (Resolve-Path $PathValue).Path
    }
    throw "File not found: $PathValue"
}

$RoomsDir = Join-Path $ScriptDir 'rooms'
$WebRoot  = Join-Path $ScriptDir 'public'

New-Item -ItemType Directory -Path $RoomsDir -Force | Out-Null

$dbPath = Resolve-OptionalPath -PathValue $DiscordDb

function Ensure-Java {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "Java runtime not found. Install Temurin/Microsoft OpenJDK 21+, ensure java.exe is on PATH."
    }
}

function Ensure-Maven {
    param([string] $Version = '3.9.6')

    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) {
        Write-Host "Using system Maven at $($mvn.Source)"
        return $mvn.Source
    }

    $mavenBase = Join-Path $ScriptDir '.maven'
    $mavenDir  = Join-Path $mavenBase "apache-maven-$Version"
    $mvnCmd    = Join-Path $mavenDir 'bin\mvn.cmd'

    if (Test-Path $mvnCmd) {
        Write-Host "Using bundled Maven at $mvnCmd"
        return $mvnCmd
    }

    Write-Host "Maven not found. Downloading Apache Maven $Version..."
    New-Item -ItemType Directory -Path $mavenBase -Force | Out-Null

    $archive = Join-Path $mavenBase "apache-maven-$Version-bin.zip"
    $url     = "https://dlcdn.apache.org/maven/maven-3/$Version/binaries/apache-maven-$Version-bin.zip"

    Invoke-WebRequest -Uri $url -OutFile $archive
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($archive, $mavenBase)
    Remove-Item $archive

    if (-not (Test-Path $mvnCmd)) {
        throw "Failed to download Maven to $mvnCmd"
    }

    Write-Host "Using bundled Maven at $mvnCmd"
    return $mvnCmd
}

function Stop-ExistingInstances {
    param(
        [int]    $PortValue,
        [string] $JarNamePattern = 'jeopardy-server-'
    )

    $pids = @()

    if ($PortValue -gt 0) {
        try {
            $connections = Get-NetTCPConnection -LocalPort $PortValue -ErrorAction SilentlyContinue
            if ($connections) {
                $pids += $connections.OwningProcess
            }
        } catch {
            # Get-NetTCPConnection requires newer Windows; ignore failures.
        }
    }

    try {
        $javaProcs = Get-CimInstance Win32_Process -Filter "Name='java.exe'"
        foreach ($proc in $javaProcs) {
            $cmd = $proc.CommandLine
            if ($cmd -and $cmd -like "*$JarNamePattern*") {
                $pids += $proc.ProcessId
            }
        }
    } catch {
        # Non-fatal if WMI is unavailable
    }

    $pids = $pids | Sort-Object -Unique | Where-Object { $_ -gt 0 }

    foreach ($pid in $pids) {
        try {
            Write-Host "Stopping existing server process (PID: $pid)..."
            Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
        } catch {
            # Ignore failures
        }
    }
}

Ensure-Java
$mvnCmd = Ensure-Maven

Write-Host 'Building application with Maven...'
& $mvnCmd -B clean package

$targetDir = Join-Path $ScriptDir 'target'
if (-not (Test-Path $targetDir)) {
    throw "Maven did not produce a target/ directory. Check the build output for errors."
}

$jar = Get-ChildItem -Path $targetDir -Filter 'jeopardy-server-*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike 'original-*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    $jar = Get-ChildItem -Path $targetDir -Filter 'jeopardy-server-*-shaded.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

if (-not $jar) {
    Write-Host "Shaded JAR not found. Contents of $targetDir:"
    Get-ChildItem -Path $targetDir
    throw "Shaded JAR not found in target/. Check Maven output for errors."
}

Stop-ExistingInstances -PortValue $Port -JarNamePattern $jar.Name

$javaArgs = @('-jar', $jar.FullName, '--port', $Port, '--rooms-dir', $RoomsDir, '--web-root', $WebRoot)

if ($dbPath) {
    $javaArgs += @('--db', $dbPath)
}

if ($RoomName) {
    $javaArgs += @('--room-name', $RoomName)
}

Write-Host "Launching GuessTheAuthor on port $Port..."
Write-Host 'Press Ctrl+C to stop the server.'

& java @javaArgs
