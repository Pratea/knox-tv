# Compila o .jar do KNOX TV e coloca dentro da pasta do mod.
#   .\build.ps1
#   .\build.ps1 -GameDir "D:\Steam\steamapps\common\ProjectZomboid"
param(
    [string]$GameDir = "E:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$Jdk = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$classes = Join-Path $root "java\build\classes"
$jarOut = Join-Path $root "mod\KNOXTV\42\media\java\client\KNOXTV.jar"

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
New-Item -ItemType Directory -Force (Split-Path $jarOut) | Out-Null

# O jogo (42.21) foi compilado com Java 25 -- precisa do JDK 25 pra compilar contra ele.
$cp = "$GameDir\projectzomboid.jar;$GameDir\ZombieBuddy.jar"
$sources = (Get-ChildItem (Join-Path $root "java\src") -Recurse -Filter *.java).FullName
& "$Jdk\bin\javac.exe" --release 25 -encoding UTF-8 -Xlint:-options -cp $cp -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac falhou" }

& "$Jdk\bin\jar.exe" cf $jarOut -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar falhou" }
Write-Host "OK: $jarOut"
