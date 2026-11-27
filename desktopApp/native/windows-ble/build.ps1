# Builds meshsurvey-ble.exe (Windows Bluetooth LE helper) into desktopApp/resources/windows/,
# where the MSI packaging picks it up. Needs Visual Studio (or Build Tools) with the C++ workload
# and a Windows 10/11 SDK; GitHub's windows-latest runners have both.
# Static C runtime (/MT): end users need no Visual C++ redistributable.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $here '..\..\resources\windows'
New-Item -ItemType Directory -Force $outDir | Out-Null
$out = (Resolve-Path $outDir).Path + '\meshsurvey-ble.exe'
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual Studio C++ build tools not found' }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
Push-Location $here
try {
    cmd /c "`"$vcvars`" >nul && cl /nologo /std:c++17 /EHsc /O2 /MT /W3 /permissive- /DNOMINMAX /DWIN32_LEAN_AND_MEAN meshsurvey-ble.cpp /link windowsapp.lib /SUBSYSTEM:WINDOWS /ENTRY:mainCRTStartup /OUT:`"$out`""
    if ($LASTEXITCODE -ne 0) { throw "cl failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
Get-Item $out | Select-Object FullName, Length
