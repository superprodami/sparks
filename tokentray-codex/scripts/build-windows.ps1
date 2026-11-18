$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    uv sync --python 3.13 --extra build
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
    uv run --python 3.13 --extra build pyinstaller --noconfirm packaging/windows.spec
    if ($LASTEXITCODE -ne 0) { throw 'Executable build failed.' }

    Copy-Item -LiteralPath 'README.md', 'LICENSE' -Destination 'dist/tokentray'
    $archive = Join-Path $projectRoot 'dist/tokentray-windows-x64.zip'
    Compress-Archive -LiteralPath 'dist/tokentray' -DestinationPath $archive -Force
    $digest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  tokentray-windows-x64.zip" | Set-Content -LiteralPath "$archive.sha256" -Encoding ascii
    Write-Output "Built $archive"
}
finally {
    Pop-Location
}
