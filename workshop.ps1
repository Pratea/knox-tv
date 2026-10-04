# Monta a pasta que o uploader da Workshop do proprio jogo espera:
#   Zomboid\Workshop\KNOXTV\{workshop.txt, preview.png, Contents\mods\KNOXTV\...}
# Depois e so abrir o jogo > Workshop > KNOX TV > enviar.
#   .\workshop.ps1
param(
    [string]$GameDir = "E:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ZomboidDir = (Join-Path $env:USERPROFILE "Zomboid")
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

& (Join-Path $root "build.ps1") -GameDir $GameDir
& (Join-Path $root "package-engine.ps1")

$item = Join-Path $ZomboidDir "Workshop\KNOXTV"
$contents = Join-Path $item "Contents\mods\KNOXTV"
New-Item -ItemType Directory -Force $contents | Out-Null
robocopy (Join-Path $root "mod\KNOXTV") $contents /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy falhou ($LASTEXITCODE)" }

# workshop.txt: o uploader acrescenta id=... depois do 1o envio -- preserva.
$txt = Join-Path $item "workshop.txt"
$id = $null
if (Test-Path $txt) { $id = Select-String -Path $txt -Pattern '^id=' | Select-Object -First 1 }
Copy-Item (Join-Path $root "workshop\workshop.txt") $txt -Force
if ($id) { Add-Content -Path $txt -Value $id.Line }
Copy-Item (Join-Path $root "workshop\preview.png") (Join-Path $item "preview.png") -Force

$size = (Get-ChildItem -Recurse $item | Measure-Object -Sum Length).Sum / 1MB
Write-Host ("Pronto pra envio: {0} ({1:N0} MB)" -f $item, $size)
exit 0
