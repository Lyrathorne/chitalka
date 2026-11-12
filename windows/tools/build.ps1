param([string]$Dotnet = "dotnet", [string]$Iscc = "", [switch]$SkipInstaller)
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $root
try {
    python windows/tools/prepare_translator.py
    if ($LASTEXITCODE -ne 0) { throw "Translator runtime failed" }
    & $Dotnet test windows/Chitalka.Tests -c Release --nologo --logger "trx;LogFileName=windows-core.trx" --results-directory outputs/windows/tests
    if ($LASTEXITCODE -ne 0) { throw "Tests failed" }
    & $Dotnet publish windows/Chitalka.Windows -c Release -r win-x64 --self-contained true -p:DebugType=None -p:DebugSymbols=false -o outputs/windows/publish --nologo
    if ($LASTEXITCODE -ne 0) { throw "Publish failed" }
    $publish = Join-Path $root "outputs/windows/publish"
    New-Item -ItemType Directory -Force "$publish/Translator/Runtime" | Out-Null
    Copy-Item outputs/windows/translator-runtime/* "$publish/Translator/Runtime/" -Recurse -Force
    Copy-Item windows/README.md "$publish/README.md" -Force
    Copy-Item windows/LICENSES.md "$publish/LICENSES.md" -Force
    Compress-Archive -Path "$publish/*" -DestinationPath outputs/windows/chitalka-windows-1.3.0-x64.zip -Force
    if (!$SkipInstaller) {
        if (!$Iscc) { $Iscc = "${env:ProgramFiles(x86)}/Inno Setup 6/ISCC.exe" }
        $deps = Join-Path $root "outputs/windows/dependencies"
        New-Item -ItemType Directory -Force $deps | Out-Null
        if (!(Test-Path "$deps/MicrosoftEdgeWebview2Setup.exe")) {
            curl.exe -4 -fL --retry 3 --retry-all-errors "https://go.microsoft.com/fwlink/p/?LinkId=2124703" -o "$deps/MicrosoftEdgeWebview2Setup.exe"
            if ($LASTEXITCODE -ne 0) { throw "WebView2 bootstrapper download failed" }
        }
        Import-Module "$PSHOME/Modules/Microsoft.PowerShell.Security" -ErrorAction Stop
        $signature = Get-AuthenticodeSignature "$deps/MicrosoftEdgeWebview2Setup.exe"
        if ($signature.Status -ne "Valid" -or $signature.SignerCertificate.Subject -notmatch "Microsoft Corporation") { throw "Invalid Microsoft WebView2 signature" }
        & $Iscc windows/installer.iss
        if ($LASTEXITCODE -ne 0) { throw "Installer failed" }
    }
    $files = Get-ChildItem outputs/windows/chitalka-windows-1.3.0* -File
    $lines = $files | ForEach-Object { $stream = [IO.File]::OpenRead($_.FullName); $sha = [Security.Cryptography.SHA256]::Create(); try { ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace("-", "").ToLowerInvariant() + "  " + $_.Name } finally { $stream.Dispose(); $sha.Dispose() } }
    $lines | Set-Content outputs/windows/SHA256SUMS.txt -Encoding ASCII
} finally { Pop-Location }
