# Monta um .zip do mod pra mandar pra alguem testar fora da Steam.
#   .\package-zip.ps1
# Resultado: dist\KNOXTV-<versao>.zip (pasta KNOX TV + LEIA-ME.txt)
param(
    [string]$GameDir = "E:\SteamLibrary\steamapps\common\ProjectZomboid"
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

& (Join-Path $root "build.ps1") -GameDir $GameDir
& (Join-Path $root "package-engine.ps1")

$version = (Select-String -Path (Join-Path $root "mod\KNOXTV\42\mod.info") -Pattern '^modversion=(.+)$').Matches[0].Groups[1].Value
$stage = Join-Path $root "dist\stage"
$zip = Join-Path $root "dist\KNOXTV-$version.zip"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
robocopy (Join-Path $root "mod\KNOXTV") (Join-Path $stage "KNOXTV") /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy falhou ($LASTEXITCODE)" }
Copy-Item (Join-Path $root "LEIA-ME.txt") (Join-Path $stage "LEIA-ME.txt")

if (Test-Path $zip) { Remove-Item $zip }
# tar (do Windows) e bem mais rapido que Compress-Archive com 280 MB
tar -a -c -f $zip -C $stage KNOXTV LEIA-ME.txt
if ($LASTEXITCODE -ne 0) { throw "tar falhou" }
Remove-Item -Recurse -Force $stage

$size = (Get-Item $zip).Length / 1MB
Write-Host ("Pronto: {0} ({1:N0} MB)" -f $zip, $size)
exit 0
