# Compila e instala o mod em Zomboid\mods.
#   .\deploy.ps1             usa o motor empacotado dentro do mod (igual a Workshop)
#   .\deploy.ps1 -DevEngine  usa o Electron de helper\ direto (sem reempacotar a cada mudanca)
param(
    [string]$GameDir = "E:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$ZomboidDir = (Join-Path $env:USERPROFILE "Zomboid"),
    [switch]$DevEngine
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

& (Join-Path $root "build.ps1") -GameDir $GameDir

$engine = Join-Path $root "mod\KNOXTV\42\engine\win-x64\KNOXTVEngine.exe"
if (-not $DevEngine -and -not (Test-Path $engine)) {
    & (Join-Path $root "package-engine.ps1")
}

# Copia o mod (espelhando: arquivos removidos aqui somem la tambem).
# Se a pasta de envio da Workshop existir (workshop.ps1), ela tambem e
# atualizada: o jogo carrega o mod de la ANTES de Zomboid\mods, e uma copia
# velha ali faz o jogo rodar a versao anterior sem avisar.
# O mod se chamava ZomboidTV: as copias instaladas com o nome antigo tem o ID
# antigo e apareceriam como um 2o mod na lista do jogo. Sao so copias geradas
# por este script/workshop.ps1 (o original fica no projeto; o ID da Workshop
# fica em workshop\published_id.txt) -- remove uma vez.
foreach ($old in @((Join-Path $ZomboidDir "mods\ZomboidTV"), (Join-Path $ZomboidDir "Workshop\ZomboidTV"))) {
    if (Test-Path $old) {
        Remove-Item -Recurse -Force $old
        Write-Host "Removida instalacao antiga (nome ZomboidTV): $old"
    }
}

$src = Join-Path $root "mod\KNOXTV"
$targets = @(Join-Path $ZomboidDir "mods\KNOXTV")
$staged = Join-Path $ZomboidDir "Workshop\KNOXTV\Contents\mods\KNOXTV"
if (Test-Path $staged) { $targets += $staged }
# Com o jogo aberto, o motor do navegador (e o .jar) ficam em uso e nao podem
# ser substituidos. /R:2 /W:1: tenta 2x e segue (o padrao do robocopy e tentar
# 1 milhao de vezes esperando 30 s -- o deploy "trava").
$gameRunning = $null -ne (Get-Process ProjectZomboid64 -ErrorAction SilentlyContinue)
if ($gameRunning) { Write-Warning "O jogo esta aberto: arquivos em uso nao serao atualizados. Feche o jogo e rode o deploy de novo." }
foreach ($dst in $targets) {
    robocopy $src $dst /MIR /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) {
        if ($gameRunning) { Write-Warning "Copia incompleta em $dst (arquivos em uso pelo jogo)"; continue }
        throw "robocopy falhou ($LASTEXITCODE)"
    }
    Write-Host "Mod instalado em $dst"
}

$cfgDir = Join-Path $ZomboidDir "KNOXTV"
$cfg = Join-Path $cfgDir "config.properties"
# Nao cria a pasta de dados a toa: se ela ja existir, o mod nao migra a pasta
# antiga (Zomboid\ZomboidTV, com os logins do navegador) pro nome novo.
if ($DevEngine) {
    New-Item -ItemType Directory -Force $cfgDir | Out-Null
    $helper = Join-Path $root "helper"
    $electron = Join-Path $helper "node_modules\electron\dist\electron.exe"
    if (-not (Test-Path $electron)) { throw "Electron nao instalado -- rode 'npm install' em $helper" }
    @"
# Gerado pelo deploy.ps1 -DevEngine (apague pra voltar ao motor do mod)
helper.exe=$($electron -replace '\\','/')
helper.app=$($helper -replace '\\','/')
"@ | Set-Content -Encoding ascii $cfg
    Write-Host "Motor: Electron de desenvolvimento ($helper)"
} else {
    if (Test-Path $cfg) { Remove-Item $cfg }
    Write-Host "Motor: empacotado no mod"
}

exit 0  # robocopy deixa codigo 1 ("copiou arquivos"), que nao e erro
