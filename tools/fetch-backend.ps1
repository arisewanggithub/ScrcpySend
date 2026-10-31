$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'app\src\main\assets'
New-Item -ItemType Directory -Force $assets | Out-Null
$file = Join-Path $assets 'scrcpy-server-v3.3.4'
Invoke-WebRequest -UseBasicParsing 'https://github.com/Genymobile/scrcpy/releases/download/v3.3.4/scrcpy-server-v3.3.4' -OutFile "$file.download"
if ((Get-FileHash "$file.download" -Algorithm SHA256).Hash.ToLowerInvariant() -ne '8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e') {
    Remove-Item -LiteralPath "$file.download"
    throw 'Official scrcpy artifact checksum mismatch'
}
Move-Item -Force "$file.download" $file
