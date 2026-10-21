[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Write", "Verify")]
    [string]$Mode,
    [string]$Root
)
$ErrorActionPreference = "Stop"
$python = Get-Command python3 -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python -ErrorAction Stop }
$argsList = @((Join-Path $PSScriptRoot "server_asset_provenance.py"), $Mode.ToLowerInvariant())
if ($Root) { $argsList += @("--root", $Root) }
& $python.Source @argsList
if ($LASTEXITCODE -ne 0) { throw "Server provenance verification failed" }
