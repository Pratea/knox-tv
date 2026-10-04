-- KNOX TV - sincronizacao no cliente.
--
-- O PC de cada jogador toca UMA TV por vez no navegador (a "sessao"):
--   * a TV aberta na janela "Assistir", se houver; senao
--   * a TV ligada mais perto, dentro de PASSIVE_RADIUS (so o som, com
--     volume caindo com a distancia -- a imagem na TV do mundo e a Fase 4).
--
-- Regra anti-eco: o que muda na pagina so vai pro servidor se foi o PROPRIO
-- jogador que mexeu (clique/tecla na pagina ou botao da janela nos ultimos
-- USER_WINDOW_MS). Mudancas que vem do servidor, do carregamento da pagina ou
-- de buffering nunca sao reenviadas -- o cliente so as segue.

if not ZTV_isAvailable then return end

require "KNOXTV/ZTV_Shared"

ZTVSync = ZTVSync or {}

local HOME_URL = "https://www.youtube.com"
local PASSIVE_RADIUS = 12      -- tiles: alcance maximo do som de uma TV
local FULL_VOLUME_DIST = 2     -- tiles: ate aqui, volume cheio
-- Paredes: mesmo comodo = normal; outro comodo da mesma casa = abafado;
-- outra casa ou na rua = quase nada (o som "vaza" pouco pela parede).
local OTHER_ROOM_FACTOR = 0.35
local OTHER_BUILDING_FACTOR = 0.06
local USER_WINDOW_MS = 3000    -- acao do jogador "conta" por esse tempo
-- Diferenca tolerada antes de corrigir a posicao. Pausado e mais apertado:
-- com o video parado, meio segundo ja e um quadro visivelmente diferente.
local DRIFT_SECONDS = 1.0
local DRIFT_PAUSED_SECONDS = 0.3
local SEEK_COOLDOWN_MS = 3000  -- entre duas correcoes de posicao
local RELOAD_COOLDOWN_MS = 8000
-- Depois de trocar de pagina, o video fica pausado/carregando um instante:
-- nesse intervalo o play/pause do jogador nao e publicado.
local PAGE_SETTLE_MS = 4000
local SYNC_INTERVAL_MS = 250
local CHOOSE_INTERVAL_MS = 1000

-- Estado conhecido de cada TV ativa: key -> {x,y,z,url,playing,pos,recvAt,by,seq}
ZTVSync.tvs = ZTVSync.tvs or {}
-- TV aberta na janela "Assistir" (key) ou nil
ZTVSync.watchKey = nil
-- Sessao atual do navegador
ZTVSync.session = nil

local lastLuaInput = 0
local lastSync, lastChoose = 0, 0

local function now() return getTimestampMs() end

--- Botoes da janela (Ir, voltar, recarregar) contam como acao do jogador.
function ZTVSync.markUserInput()
    lastLuaInput = now()
end

local function userActive()
    return ZTV_getMsSinceInput() < USER_WINDOW_MS or now() - lastLuaInput < USER_WINDOW_MS
end

local function myName()
    return ZTV.playerName(getPlayer())
end

--- Posicao em que o video deveria estar agora, segundo o servidor.
local function expectedPos(st)
    if st.playing then return st.pos + (now() - st.recvAt) / 1000 end
    return st.pos
end

-- ---------------------------------------------------------------------------
-- Mensagens do servidor
-- ---------------------------------------------------------------------------

function ZTVSync.onServerCommand(module, command, args)
    if module ~= ZTV.MODULE or type(args) ~= "table" then return end
    local key = ZTV.keyOf(args.x, args.y, args.z)
    if command == "state" then
        local old = ZTVSync.tvs[key]
        if old and old.seq and args.seq and args.seq < old.seq then return end -- chegou fora de ordem
        ZTVSync.tvs[key] = {
            x = args.x, y = args.y, z = args.z,
            url = args.url, playing = args.playing == true, pos = tonumber(args.pos) or 0,
            recvAt = now(), by = args.by, seq = args.seq,
        }
    elseif command == "off" then
        ZTVSync.tvs[key] = nil
    end
end

--- Manda pro servidor o novo estado da TV da sessao, e ja aplica localmente
--- (otimista) pra a logica de "seguir o servidor" nao desfazer a acao
--- enquanto a resposta nao chega.
local function publish(key, url, playing, pos)
    local st = ZTVSync.tvs[key] or {}
    local x, y, z = key:match("^(-?%d+),(-?%d+),(-?%d+)$")
    st.x, st.y, st.z = tonumber(x), tonumber(y), tonumber(z)
    st.url, st.playing, st.pos, st.recvAt, st.by = url, playing, pos, now(), myName()
    ZTVSync.tvs[key] = st
    ZTV.sendToServer("set", { x = st.x, y = st.y, z = st.z, url = url, playing = playing, pos = pos })
end

function ZTVSync.stopTV(x, y, z)
    ZTV.sendToServer("off", { x = x, y = y, z = z })
end

-- ---------------------------------------------------------------------------
-- Escolha da TV da sessao
-- ---------------------------------------------------------------------------

local function tvIsOn(st)
    return ZTV.isScreenOn(st.x, st.y, st.z)
end

--- Retorna key, distancia da TV que este PC deve tocar agora (ou nil).
local function chooseSession()
    local player = getPlayer()
    if not player then return nil end
    local px, py, pz = player:getX(), player:getY(), player:getZ()

    if ZTVSync.watchKey then
        local x, y, z = ZTVSync.watchKey:match("^(-?%d+),(-?%d+),(-?%d+)$")
        if ZTV.isScreenOn(tonumber(x), tonumber(y), tonumber(z)) then return ZTVSync.watchKey, 0 end
        return nil
    end

    local bestKey, bestDist = nil, nil
    for key, st in pairs(ZTVSync.tvs) do
        local def = ZTV.screenDef(key)
        local sameLevel = ZTV.onScreenLevel(key, pz)
        if st.url and sameLevel then
            local d = ZTV.distanceTo(key, px, py)
            local radius = def and def.radius or PASSIVE_RADIUS
            if d <= radius and (not bestDist or d < bestDist) and tvIsOn(st) then
                bestKey, bestDist = key, d
            end
        end
    end
    return bestKey, bestDist
end

--- Tile de referencia da tela pra saber em que comodo ela esta: o da TV,
--- ou o canto do telao (o tile da parede da tela fica dentro da sala).
local function screenSquare(key)
    local def = ZTV.screenDef(key)
    if def then return getCell():getGridSquare(def.x0, def.y0, def.z0) end
    local x, y, z = key:match("^(-?%d+),(-?%d+),(-?%d+)$")
    local tv = ZTV.findTV(tonumber(x), tonumber(y), tonumber(z))
    return tv and tv:getSquare()
end

--- Quanto as paredes abafam o som entre o jogador e a TV (ou telao fechado).
local function wallFactor(key)
    local player = getPlayer()
    local tvSq = screenSquare(key)
    local mySq = player and player:getCurrentSquare()
    if not tvSq or not mySq then return 1 end
    local tvBuilding, myBuilding = tvSq:getBuilding(), mySq:getBuilding()
    if tvBuilding ~= myBuilding then return OTHER_BUILDING_FACTOR end
    if tvSq:getRoom() ~= mySq:getRoom() then return OTHER_ROOM_FACTOR end
    return 1
end

--- Volume (0..1) de uma TV ouvida de longe. Curva ao quadrado: o ouvido
--- percebe volume de forma logaritmica, entao uma queda linear soaria alta
--- ate bem longe (20% de volume ainda e bem audivel).
local function volumeFor(key, dist)
    local def = ZTV.screenDef(key)
    local full = def and def.full or FULL_VOLUME_DIST
    local radius = def and def.radius or PASSIVE_RADIUS
    local falloff = 1
    if dist > full then
        falloff = math.max(0, 1 - (dist - full) / (radius - full))
    end
    -- Drive-in e ao ar livre: sem abafamento. TV e cinema (indoor): com paredes.
    local walls = (def and not def.indoor) and 1 or wallFactor(key)
    return falloff * falloff * walls
end

local function startSession(key)
    if not ZTV_start() then return end
    ZTVSync.session = { key = key, loadedUrl = nil, loadAt = 0, lastPage = nil, pageChangedAt = 0, lastSeekAt = 0 }
    local st = ZTVSync.tvs[key]
    if st and st.url then
        ZTV_load(st.url)
        ZTVSync.session.loadedUrl = ZTV.canonicalUrl(st.url)
        ZTVSync.session.loadAt = now()
    elseif ZTVSync.watchKey == key then
        ZTV_load(HOME_URL) -- TV sem nada passando: abre a pagina inicial (nao e enviada)
    end
end

local function endSession()
    if ZTVSync.session then
        ZTVSync.session = nil
        ZTV_detachScreen()
        ZTV_setKeyboardFocus(false)
        ZTV_load("about:blank")
    end
end

--- O video aparece na tela da TV (ou no telao) da sessao, so neste PC. O
--- objeto pode trocar quando o pedaco do mapa recarrega (a tela some e o id
--- deixa de bater), entao e conferido sempre.
local function updateScreen()
    local s = ZTVSync.session
    if not s or ZTV_getScreenId() == s.key then return end
    local def = ZTV.screenDef(s.key)
    if def then
        ZTV_attachWall(def.x0, def.x1, def.y0, def.y1, def.z0, def.z1, def.sprite, def.axis)
        return
    end
    local x, y, z = s.key:match("^(-?%d+),(-?%d+),(-?%d+)$")
    local tv = ZTV.findTV(tonumber(x), tonumber(y), tonumber(z))
    if tv then ZTV_attachScreen(tv) end
end

local function updateSessionChoice()
    local key, dist = chooseSession()
    local cur = ZTVSync.session and ZTVSync.session.key
    if key ~= cur then
        endSession()
        if key then startSession(key) end
    end
    if ZTVSync.session then
        updateScreen()
        -- Com a janela "Assistir" aberta, volume cheio (voce esta vendo a TV)
        local vol = ZTVSync.watchKey and 1 or volumeFor(ZTVSync.session.key, dist or 0)
        ZTV_setVolume(vol)
    end
end

-- ---------------------------------------------------------------------------
-- Sincronizacao da sessao
-- ---------------------------------------------------------------------------

local function syncSession()
    local s = ZTVSync.session
    if not s or not ZTV_isRunning() then return end
    local st = ZTVSync.tvs[s.key]
    local pageUrl = ZTV_getUrl()
    local page = ZTV.canonicalUrl(pageUrl)
    local user = userActive()
    local leader = st ~= nil and st.by == myName()

    -- 1) A pagina navegou (clicou noutro video, autoplay do proximo...)
    if page ~= s.lastPage then
        s.lastPage = page
        s.pageChangedAt = now()
        local remote = st and ZTV.canonicalUrl(st.url) or ""
        local shareable = ZTV.isShareableUrl(pageUrl)
        -- Pagina "privada" (login, conta, link com codigo de acesso) aberta
        -- pelo proprio jogador: nao vai pros outros, e a sincronizacao nao o
        -- puxa de volta pro video da TV enquanto ele estiver nela.
        s.private = user and not shareable and pageUrl ~= "" and pageUrl ~= "about:blank"
        -- So o jogador que mexeu agora (ou quem colocou o video atual, pro
        -- autoplay do proximo video valer pra todos) publica a navegacao.
        if (user or leader) and page ~= remote and shareable then
            publish(s.key, ZTV.shareableUrl(pageUrl), true, 0)
            s.loadedUrl = page
            return
        end
    end
    if s.private then return end

    if not st or not st.url then return end
    local remote = ZTV.canonicalUrl(st.url)

    -- 2) O servidor manda outro link: carrega (sem atropelar o jogador que esta mexendo)
    if page ~= remote then
        if not user and (s.loadedUrl ~= remote or now() - s.loadAt > RELOAD_COOLDOWN_MS) then
            ZTV_load(st.url)
            s.loadedUrl = remote
            s.loadAt = now()
        end
        return
    end

    -- 3) Mesmo video: alinha play/pause e posicao (anuncio = espera acabar)
    if not ZTV_hasMedia() or ZTV_isMediaAd() then return end
    local t = ZTV_getMediaTime()
    local paused = ZTV_isMediaPaused()
    local expected = expectedPos(st)
    local duration = ZTV_getMediaDuration()

    if user then
        if now() - (s.pageChangedAt or 0) < PAGE_SETTLE_MS then return end
        -- O jogador pausou/despausou ou arrastou a barra: publica
        if paused == st.playing or math.abs(t - expected) > DRIFT_SECONDS + 2 then
            publish(s.key, st.url, not paused, t)
        end
        return
    end

    local pastEnd = duration > 0 and expected >= duration - 1
    if st.playing and paused and not ZTV_isMediaEnded() and not pastEnd then
        ZTV_media("play", 0)
    elseif not st.playing and not paused then
        ZTV_media("pause", 0)
    end
    local drift = st.playing and DRIFT_SECONDS or DRIFT_PAUSED_SECONDS
    if not pastEnd and math.abs(t - expected) > drift and now() - s.lastSeekAt > SEEK_COOLDOWN_MS then
        ZTV_media("seek", expected)
        s.lastSeekAt = now()
    end
end

local function onTick()
    local t = now()
    if t - lastChoose >= CHOOSE_INTERVAL_MS then
        lastChoose = t
        updateSessionChoice()
    end
    if t - lastSync >= SYNC_INTERVAL_MS then
        lastSync = t
        syncSession()
    end
end

-- ---------------------------------------------------------------------------
-- Janela "Assistir"
-- ---------------------------------------------------------------------------

function ZTVSync.watch(x, y, z)
    ZTVSync.watchKey = ZTV.keyOf(x, y, z)
    lastChoose = 0 -- escolhe a sessao ja no proximo tick
    ZTVBrowserWindow.open(ZTVSync.watchKey)
end

--- Fechou a janela: a TV continua tocando pra quem esta perto (inclusive
--- voce, so o som, pela regra da TV mais proxima).
function ZTVSync.onWindowClosed()
    ZTVSync.watchKey = nil
    lastChoose = 0
end

--- Tela ligada mais proxima ao alcance (telao: o alcance dele; TV: PASSIVE_RADIUS),
--- mesmo sem nada passando. Retorna a key ou nil.
local function nearestScreenKey()
    local player = getPlayer()
    if not player then return nil end
    local px, py, pz = player:getX(), player:getY(), math.floor(player:getZ())
    local bestKey, bestDist = nil, nil
    local function consider(key, d)
        if not bestDist or d < bestDist then bestKey, bestDist = key, d end
    end

    for _, def in ipairs(ZTV.SCREENS) do
        local d = ZTV.distanceTo(def.key, px, py)
        if d <= def.radius and ZTV.onScreenLevel(def.key, pz) and ZTV.isScreenOn(def.x0, def.y0, def.z0) then
            consider(def.key, d)
        end
    end

    local cell = getCell()
    local r = PASSIVE_RADIUS
    for y = math.floor(py) - r, math.floor(py) + r do
        for x = math.floor(px) - r, math.floor(px) + r do
            local tv = ZTV.findTV(x, y, pz)
            if tv and ZTV.isTurnedOn(tv) then
                local key = ZTV.keyOf(x, y, pz)
                local d = ZTV.distanceTo(key, px, py)
                if d <= r then consider(key, d) end
            end
        end
    end
    return bestKey
end

--- "Controle remoto" (botao da barra lateral / tecla): abre a janela da tela
--- que voce esta ouvindo, ou da tela ligada mais proxima -- sem precisar ir
--- ate ela. Se a janela ja esta aberta, fecha.
function ZTVSync.toggleRemote()
    if ZTVBrowserWindow.instance then
        ZTVBrowserWindow.instance:close()
        return
    end
    local key = (ZTVSync.session and ZTVSync.session.key) or nearestScreenKey()
    if not key then
        local player = getPlayer()
        if player then player:Say("Nenhuma TV ligada ou telao por perto") end
        return
    end
    local x, y, z = key:match("^(-?%d+),(-?%d+),(-?%d+)$")
    ZTVSync.watch(tonumber(x), tonumber(y), tonumber(z))
end

-- ---------------------------------------------------------------------------
-- Menu de contexto da TV
-- ---------------------------------------------------------------------------

local function onFillContext(playerNum, context, worldObjects, test)
    if test then return end

    -- Telao de drive-in (paredes nas coordenadas exatas do telao)
    for _, obj in ipairs(worldObjects) do
        local def = ZTV.screenDefForObject(obj)
        if def then
            context:addOption("KNOX TV: Assistir - " .. def.name, nil, function() ZTVSync.watch(def.x0, def.y0, def.z0) end)
            if ZTVSync.tvs[def.key] then
                context:addOption("KNOX TV: Parar o video - " .. def.name, nil, function() ZTVSync.stopTV(def.x0, def.y0, def.z0) end)
            end
            return
        end
    end

    local tv
    for _, obj in ipairs(worldObjects) do
        if instanceof(obj, "IsoTelevision") then tv = obj; break end
    end
    if not tv then return end
    local sq = tv:getSquare()
    local x, y, z = sq:getX(), sq:getY(), sq:getZ()

    local watch = context:addOption("KNOX TV: Assistir", nil, function() ZTVSync.watch(x, y, z) end)
    if not ZTV.isTurnedOn(tv) then
        watch.notAvailable = true
        watch.name = "KNOX TV: Assistir (ligue a TV primeiro)"
    end
    if ZTVSync.tvs[ZTV.keyOf(x, y, z)] then
        context:addOption("KNOX TV: Parar o video desta TV", nil, function() ZTVSync.stopTV(x, y, z) end)
    end
end

-- O Java (e o motor do navegador) sobrevive a volta pro menu principal --
-- so o Lua e recarregado. Sem isto, o video continua tocando no menu.
local function stopEngine()
    if ZTV_isRunning() then
        print("[KNOX TV] saindo do jogo: desligando o navegador")
        ZTV_stop()
    end
end
-- Este arquivo carrega de novo a cada volta pro menu e a cada entrada num
-- save: qualquer motor ainda rodando aqui e de uma partida anterior.
stopEngine()
Events.OnMainMenuEnter.Add(stopEngine)

Events.OnFillWorldObjectContextMenu.Add(onFillContext)
Events.OnServerCommand.Add(ZTVSync.onServerCommand)
Events.OnTick.Add(onTick)
-- Ao entrar no jogo, pede o estado de todas as TVs que ja estao passando algo
Events.OnGameStart.Add(function() ZTV.sendToServer("list", {}) end)

print("[KNOX TV] sincronizacao carregada")
