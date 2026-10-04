# Publica/atualiza o KNOX TV na Workshop pelo SteamCMD (ferramenta oficial
# da Valve). O uploader de dentro do jogo recusa .exe/.dll, e o motor do
# navegador e um .exe com .dlls -- a Steam em si aceita.
#
#   .\publish-steamcmd.ps1                  monta o .vdf e mostra o comando
#   .\publish-steamcmd.ps1 -SteamUser nome  ja roda o SteamCMD (ele pede a senha/Steam Guard)
#
# Antes: rode .\workshop.ps1 (com o jogo fechado) pra atualizar a pasta de envio.
param(
    [string]$SteamCmd = "C:\steamcmd\steamcmd.exe",
    [string]$SteamUser = "",
    [string]$ChangeNote = "Atualizacao",
    [string]$ZomboidDir = (Join-Path $env:USERPROFILE "Zomboid")
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$item = Join-Path $ZomboidDir "Workshop\KNOXTV"
$contents = Join-Path $item "Contents"
$preview = Join-Path $item "preview.png"
if (-not (Test-Path (Join-Path $contents "mods\KNOXTV\42\mod.info"))) {
    throw "Pasta de envio incompleta -- rode .\workshop.ps1 antes"
}

# Titulo, descricao e visibilidade vem do workshop.txt (o mesmo do uploader do jogo)
$title = "KNOX TV"; $desc = @(); $visibility = "unlisted"
foreach ($line in Get-Content -Encoding UTF8 (Join-Path $root "workshop\workshop.txt")) {
    if ($line -match '^title=(.*)$') { $title = $Matches[1] }
    elseif ($line -match '^description=(.*)$') { $desc += $Matches[1] }
    elseif ($line -match '^visibility=(.*)$') { $visibility = $Matches[1] }
}
# SteamCMD: 0 publico, 1 so amigos, 2 privado, 3 nao listado
$vis = @{ public = 0; friendsOnly = 1; private = 2; unlisted = 3 }[$visibility]
if ($null -eq $vis) { $vis = 3 }

# ID do item: guardado depois do 1o envio (0 = criar um item novo)
$idFile = Join-Path $root "workshop\published_id.txt"
$publishedId = if (Test-Path $idFile) { (Get-Content $idFile -Raw).Trim() } else { "0" }

# O leitor de .vdf do SteamCMD nao entende escapes (\" e \\) nem textos longos
# de varias linhas: caminhos vao com "/", textos curtos sem aspas, e a
# descricao NAO vai no .vdf -- fica em workshop\descricao_steam.txt pra colar
# uma vez na pagina do item (a Steam mantem ela nas proximas atualizacoes).
function Clean-Vdf([string]$s) { return $s.Replace('"', "'").Replace("`r", " ").Replace("`n", " ") }
$descFile = Join-Path $root "workshop\descricao_steam.txt"
[System.IO.File]::WriteAllText($descFile, ($desc -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
$vdf = @"
"workshopitem"
{
    "appid"            "108600"
    "publishedfileid"  "$publishedId"
    "contentfolder"    "$($contents.Replace('\', '/'))"
    "previewfile"      "$($preview.Replace('\', '/'))"
    "visibility"       "$vis"
    "title"            "$(Clean-Vdf $title)"
    "changenote"       "$(Clean-Vdf $ChangeNote)"
}
"@
$vdfPath = Join-Path $root "workshop\steamcmd_item.vdf"
[System.IO.File]::WriteAllText($vdfPath, $vdf, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Arquivo de publicacao: $vdfPath"
Write-Host "Descricao pra colar na pagina do item: $descFile"
Write-Host ("Item: {0} (visibilidade {1}, {2})" -f $title, $visibility, $(if ($publishedId -eq "0") { "NOVO" } else { "atualizando $publishedId" }))

if (-not (Test-Path $SteamCmd)) {
    Write-Warning "SteamCMD nao encontrado em $SteamCmd -- baixe da Valve e extraia la (ver instrucoes)."
}
if ($SteamUser -eq "") {
    Write-Host ""
    Write-Host "Rode (vai pedir sua senha e o codigo do Steam Guard):"
    Write-Host "  `"$SteamCmd`" +login SEU_USUARIO +workshop_build_item `"$vdfPath`" +quit"
    Write-Host "Depois do 1o envio, salve o numero do item em workshop\published_id.txt"
    exit 0
}

& $SteamCmd +login $SteamUser +workshop_build_item $vdfPath +quit

# O resultado de verdade fica no log do SteamCMD ("Upload finished for workshop
# item N : <resultado>") -- a saida na tela pode ter ERROR! no meio de tudo.
$log = Join-Path (Split-Path $SteamCmd) "logs\workshop_log.txt"
$result = $null
if (Test-Path $log) {
    $last = Select-String -Path $log -Pattern 'Upload finished for workshop item (\d+) : (.+)$' | Select-Object -Last 1
    if ($last) { $result = $last.Matches[0].Groups[2].Value.Trim(); $itemId = $last.Matches[0].Groups[1].Value }
}
# O SteamCMD grava o publishedfileid de volta no .vdf apos criar o item
$m = Select-String -Path $vdfPath -Pattern '"publishedfileid"\s+"(\d+)"'
if ($m -and $m.Matches[0].Groups[1].Value -ne "0") { $itemId = $m.Matches[0].Groups[1].Value }

if ($result -eq "OK") {
    if ($itemId) { Set-Content -Path $idFile -Value $itemId }
    Write-Host ("PUBLICADO: https://steamcommunity.com/sharedfiles/filedetails/?id={0}" -f $itemId) -ForegroundColor Green
} else {
    Write-Host ("FALHOU: resultado da Steam = '{0}'. O item NAO foi atualizado." -f $result) -ForegroundColor Red
    if ($result -eq "Access Denied") {
        Write-Host "Abra a pagina do item logado e veja se pede pra aceitar o Acordo da Workshop ou se o item esta em revisao."
    }
    exit 1
}
