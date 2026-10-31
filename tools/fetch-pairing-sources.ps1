$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'app\src\main\assets\sources'
$origins = Get-Content (Join-Path $assets 'spake2-source-origins.json') -Raw | ConvertFrom-Json
$stage = Join-Path $root '.gradle\spake2-source-bundle'
New-Item -ItemType Directory -Force $stage | Out-Null
foreach ($component in @('java', 'native')) {
    $origin = $origins.$component
    $archive = Join-Path $stage "$component.zip"
    Invoke-WebRequest -UseBasicParsing "$($origin.repository)/archive/$($origin.revision).zip" -OutFile $archive
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $origin.archiveSha256) {
        throw "Pinned $component source checksum mismatch"
    }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $stage $component) -Force
}
$javaRoot = Join-Path $stage "java\spake2-java-$($origins.java.revision)"
$nativeRoot = Join-Path $stage "native\spake2-c-$($origins.native.revision)"
$destination = Join-Path $javaRoot ($origins.native.submodulePath.Replace('/', '\'))
New-Item -ItemType Directory -Force $destination | Out-Null
Get-ChildItem -Force $nativeRoot | Copy-Item -Destination $destination -Recurse -Force
Copy-Item (Join-Path $assets 'spake2-source-origins.json') (Join-Path $javaRoot 'SOURCE_ORIGINS.json') -Force
$output = Join-Path $assets 'spake2-2.2.1-source.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($output, [IO.FileMode]::Create)
$zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    [string[]]$files = Get-ChildItem $javaRoot -Recurse -File -Force | ForEach-Object { $_.FullName }
    [Array]::Sort($files, [StringComparer]::Ordinal)
    foreach ($file in $files) {
        $relative = $file.Substring($javaRoot.Length + 1).Replace('\', '/')
        $entry = $zip.CreateEntry("spake2-java-$($origins.java.revision)/$relative", [IO.Compression.CompressionLevel]::Optimal)
        $entry.LastWriteTime = New-Object DateTimeOffset(2000, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
        $entry.ExternalAttributes = 0
        $input = [IO.File]::OpenRead($file)
        $target = $entry.Open()
        try { $input.CopyTo($target) } finally { $target.Dispose(); $input.Dispose() }
    }
} finally { $zip.Dispose(); $stream.Dispose() }
Get-FileHash $output -Algorithm SHA256
