# KNOX TV

Mod de Project Zomboid (Build 42.21) que coloca um navegador de verdade nas
TVs do jogo: YouTube e outros sites passando na TV, sincronizado entre os
jogadores no multiplayer, compatível com o Viewpoint (1ª pessoa).

## Arquitetura

```
 Jogo (JVM, cliente)                         Motor (processo separado)
 ┌──────────────────────────────┐            ┌─────────────────────────┐
 │ Lua: janela, menu da TV      │            │ Electron (Chromium)     │
 │   │ ZTV_* (funções globais)  │  socket    │ janela fora da tela     │
 │ Java (ZombieBuddy):          │◄──────────►│ comandos/eventos JSON   │
 │   BrowserHelper ─ processo   │ 127.0.0.1  │                         │
 │   FrameSource  ─ lê quadros  │◄───────────│ escreve cada quadro     │
 │   GlBridge     ─ OpenGL      │  arquivo   │ (BGRA) no arquivo       │
 └──────────────────────────────┘  mapeado   └─────────────────────────┘
```

- **Por que um processo separado?** O jogo roda o Java em modo `headless`
  (sem AWT), o que quebra o Chromium embutido (JCEF). E se o navegador
  travar, o jogo não cai junto. O Electron também traz codecs H.264 (JCEF
  não traz) e existe uma versão com Widevine (DRM), que é a única chance de
  Crunchyroll funcionar.
- **Quadros**: o motor escreve num arquivo de tamanho fixo que o Java mapeia
  na memória (seqlock no cabeçalho, layout em `helper/main.js`). O Java
  envia pra uma textura OpenGL na thread de renderização do jogo.
- **Viewpoint**: pra a TV mostrar o vídeo também em 1ª pessoa, o quadro é
  copiado na GPU direto pro pedaço do atlas do sprite da TV. Qualquer
  renderizador que desenhe aquele sprite mostra o vídeo, sem tocar no
  código do Viewpoint (que é proprietário).

## Estrutura

```
helper/        motor de navegador (Electron)
java/src/      parte Java do mod (pacote zomboidtv)
mod/KNOXTV/ mod pronto (42/mod.info, 42/media/lua, 42/media/java)
build.ps1      compila o .jar (precisa do JDK 25, o jogo foi compilado com Java 25)
deploy.ps1     build + instala em %USERPROFILE%\Zomboid\mods + escreve a config
```

## Desenvolvimento

```powershell
cd helper; npm install; cd ..
.\deploy.ps1             # instala com o motor empacotado (igual a Workshop)
.\deploy.ps1 -DevEngine  # instala usando o Electron de helper\ (sem reempacotar)
```

## Distribuição (Workshop)

O motor vai **dentro do mod** (`42/engine/win-x64/KNOXTVEngine.exe`,
~280 MB, quase tudo Chromium): quem assina recebe tudo pela Steam, sem
Node.js, download extra ou configuração. O Java acha o motor a partir da
localização do próprio `.jar`; `Zomboid/KNOXTV/config.properties` só
existe pra desenvolvimento (`-DevEngine`). Logins e cookies do navegador
ficam em `Zomboid/KNOXTV/browser-data`.

```powershell
.\package-engine.ps1     # empacota helper\ -> mod\KNOXTV\42\engine\win-x64
.\workshop.ps1           # monta Zomboid\Workshop\KNOXTV pro uploader do jogo
```

Imagens (`poster.png`, `preview.png`): `python tools/make_images.py`.

No jogo: ative **ZombieBuddy** e **KNOX TV** nos mods, aprove o mod Java
quando o ZombieBuddy perguntar e entre num save.

## Fases

1. ✅ **Prova de conceito**: janela de navegador no jogo + teste de vídeo no
   sprite da TV. Confirmado: o Viewpoint mostra o vídeo animado em 1ª pessoa.
   Descoberta pra Fase 4: `IsoTelevision` tem camadas de tela próprias
   (`addTvScreenSprite`/`clearTvScreenSprites`), que o Viewpoint também
   desenha -- o vídeo vai numa camada nossa, por TV, só na área da tela.
2. ✅ **Interação completa**: teclado via `UITextEntryInterface` (o mesmo
   mecanismo do chat do jogo: acentos funcionam e os atalhos do jogo ficam
   desligados enquanto digita), duplo clique, arrastar, tela cheia (da
   página e da janela), janela redimensionável, mudo.
3. ✅ **TV + multiplayer**: cada TV é uma tela compartilhada.
   - `lua/server/ZTV_Server.lua`: estado de cada TV num ModData global
     (salvo com o mundo), posição adiantada pro "agora" ao enviar (ninguém
     precisa de relógio sincronizado), validação de distância/TV existente,
     limite de comandos por jogador e lista de sites permitidos
     (`Zomboid/Lua/KNOXTV/allowed_domains.txt` no servidor).
   - `lua/client/ZTV_Sync.lua`: cada PC toca uma TV (a da janela "Assistir"
     ou a ligada mais perto, só som, volume pela distância). Só publica o
     que o PRÓPRIO jogador fez (clique/tecla nos últimos 3s) -- o resto ele
     só segue, então não existe eco entre clientes.
   - Single-player: `sendServerCommand` não faz nada fora do MP, então
     `ZTV.sendToAll` chama o cliente direto (`ZTV_Shared.lua`).
   - Som: volume pela distância (curva ao quadrado) e abafado por paredes
     (outro cômodo / outra casa). É um multiplicador sobre o volume da
     própria página -- a barra de volume do YouTube continua funcionando.
4. **Vídeo na tela da TV** (atual) -- `ScreenRenderer.java`: a camada de
   tela da TV (`overlaySprite`, sprite da TV com índice +16) é trocada, só
   neste PC, por um sprite com textura própria. O alfa da tela vanilla é
   lido uma vez do atlas e vira um mapeamento por coluna (bordas de cima e
   de baixo ajustadas por mínimos quadrados), que acompanha a inclinação
   isométrica. A cada quadro (até 24/s) a imagem é montada pela média de
   3x3 amostras, usando só o retângulo do `<video>` da página, e o cache de
   desenho do pedaço do mapa é invalidado (o mesmo que a TV vanilla faz).
   O Viewpoint desenha essa mesma camada em 1ª pessoa.
   - Resolução em 1ª pessoa limitada a 1x: o Viewpoint lê a textura pelo
     tamanho em pixels declarado, não pelas UVs (`screen.scale`/`screen.crop`
     no config.properties existem pra testar; pedido aos autores em
     `docs/viewpoint_request.md`).
   - Packs de modelos 3D do Viewpoint (ex: Voxel Studio): quando um sprite tem
     modelo 3D, o Viewpoint não desenha a camada de tela. `ZTV_ViewpointPatch.lua`
     intercepta `Viewpoint.ModelPacks.register` e `ModelPackPatch.java` gera,
     no PC do jogador, uma cópia do manifesto sem as linhas `bind.` de TVs
     (`ztv-notv-*.properties`, ao lado do original). TVs ficam com o visual
     padrão do Viewpoint (vídeo funciona); o resto continua em 3D.
     Testado e descartado: escrever o vídeo na textura do modelo -- o
     Viewpoint carrega as imagens dos packs fora do sistema de texturas do
     jogo, num lugar que não dá pra achar/ler de fora.
5. **Telão de drive-in** (`WallScreenRenderer.java`, `ZTV.SCREENS` em
   `ZTV_Shared.lua`): paredes do mapa que viram uma "TV" grande, identificadas
   pelas coordenadas exatas + sprite (a mesma parede em casas não é afetada).
   Rosewood: `walls_interior_house_03_20`, x=8388, y=12229..12247, andares
   1..4 (19 x 4 pedaços). Cada pedaço ganha uma camada com a sua parte do
   vídeo; todas são pedaços de um único atlas em meia resolução, atualizado
   a 15/s. Som ao ar livre: alcance 70, volume cheio até 35, sem paredes.
   Funciona sem energia. Pra adicionar outro telão: nova entrada em `ZTV.SCREENS`
   (mapear com Opções > Mods > KNOX TV > "Modo de mapeamento").
   Cadastrados: drive-in de Rosewood; cinema de March Ridge, salas 1 e 2
   (x=10166, y=12635..12641 e 12646..12652, andar 0 -- o andar 1 da parede fica
   acima do teto da sala --, `indoor`: som
   abafado por paredes, como TV). Nos telões o vídeo mantém a proporção
   (faixas pretas), calculada pela face da parede x tiles x andares.
6. Depois: bloqueador de anúncios, Smart TV própria.
