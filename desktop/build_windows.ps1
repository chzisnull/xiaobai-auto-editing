$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PythonBin = if ($env:PYTHON_BIN) { $env:PYTHON_BIN } else { Join-Path $ProjectRoot ".venv\Scripts\python.exe" }

& $PythonBin (Join-Path $ProjectRoot "desktop\assets\generate_icon.py")
Push-Location $ProjectRoot
try {
  & $PythonBin -m PyInstaller --noconfirm --clean desktop\xiaobai_auto_editing.spec
} finally {
  Pop-Location
}

Write-Host "Built application: $ProjectRoot\dist\小白自动剪辑\小白自动剪辑.exe"
