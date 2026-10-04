# Empacota o motor de navegador (helper/) como um programa independente e
# coloca dentro do mod, em KNOXTV/42/engine/win-x64/. Assim quem assina o
# mod na Workshop ja recebe tudo -- sem Node.js, sem download extra.
#   .\package-engine.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$helper = Join-Path $root "helper"
$dist = Join-Path $helper "dist"
$target = Join-Path $root "mod\KNOXTV\42\engine\win-x64"
$env:Path = "C:\Program Files\nodejs;" + $env:Path

Push-Location $helper
try {
    # --asar empacota o codigo num arquivo so; devDependencies ficam de fora.
    npx --no-install electron-packager . KNOXTVEngine `
        --platform=win32 --arch=x64 --out=dist --overwrite --asar `
        --ignore="^/dist" --ignore="\.bin$" --ignore="^/test"
    if ($LASTEXITCODE -ne 0) { throw "electron-packager falhou" }
} finally {
    Pop-Location
}

$built = Join-Path $dist "KNOXTVEngine-win32-x64"

# Traducoes da interface do Chromium: so precisamos das usadas pelos jogadores
# (o resto sao ~40 MB a mais na Workshop pra nada).
Get-ChildItem (Join-Path $built "locales") -Filter *.pak |
    Where-Object { $_.Name -notin @("en-US.pak", "pt-BR.pak", "es.pak") } |
    Remove-Item -Force

if (Test-Path $target) { Remove-Item -Recurse -Force $target }
New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
Copy-Item -Recurse $built $target

$size = (Get-ChildItem -Recurse $target | Measure-Object -Sum Length).Sum / 1MB
Write-Host ("Motor empacotado em {0} ({1:N0} MB)" -f $target, $size)
