-- KNOX TV - codigo compartilhado entre cliente e servidor.
--
-- Modelo: cada TV do mundo e uma "tela compartilhada". O servidor guarda o
-- estado de cada TV ligada no KNOX TV (link, tocando/pausado, posicao) e
-- avisa todo mundo; cada cliente toca o video no proprio navegador e se
-- mantem alinhado com esse estado (ZTV_Sync.lua).

ZTV = ZTV or {}

ZTV.MODULE = "KNOXTV"

-- Distancia maxima (em tiles) pra controlar uma TV pelo servidor
ZTV.MAX_CONTROL_DIST = 15

-- Sites que podem ser colocados numa TV. Importante: o link de uma TV e
-- aberto no PC de TODOS os jogadores perto dela -- sem essa lista, qualquer
-- jogador poderia fazer os outros abrirem um site qualquer (e esse site
-- veria o IP de cada um). O servidor pode trocar a lista editando
-- Zomboid/Lua/KNOXTV/allowed_domains.txt (criado na primeira execucao).
ZTV.DEFAULT_DOMAINS = {
    "youtube.com",
    "youtu.be",
    "youtube-nocookie.com",
    "twitch.tv",
    "crunchyroll.com",
    "vimeo.com",
    "dailymotion.com",
}

function ZTV.keyOf(x, y, z)
    return tostring(x) .. "," .. tostring(y) .. "," .. tostring(z)
end

function ZTV.hostOf(url)
    if type(url) ~= "string" then return nil end
    local host = url:match("^[Hh][Tt][Tt][Pp][Ss]?://([^/:%?#]+)")
    return host and host:lower() or nil
end

--- O link e https e o dominio (ou um subdominio dele) esta na lista?
function ZTV.isAllowedUrl(url, domains)
    if type(url) ~= "string" or #url > 2000 then return false end
    if not url:match("^[Hh][Tt][Tt][Pp][Ss]://") then return false end
    local host = ZTV.hostOf(url)
    if not host then return false end
    for _, d in ipairs(domains or ZTV.DEFAULT_DOMAINS) do
        if host == d or host:sub(-(#d + 1)) == "." .. d then return true end
    end
    return false
end

-- Paginas que nunca sao compartilhadas com os outros jogadores, mesmo num
-- site permitido: login, conta e retorno de login. Depois de logar, alguns
-- sites passam por um link com um codigo de acesso temporario (code=,
-- token=...) -- esse link iria pro servidor e pra todo mundo perto da TV.
local SENSITIVE_HOST_PREFIXES = { "sso.", "login.", "accounts.", "account.", "auth.", "id.", "signin.", "secure." }
local SENSITIVE_PATHS = {
    "login", "logout", "signin", "sign-in", "signup", "sign-up", "register", "account", "accounts",
    "auth", "oauth", "oauth2", "callback", "password", "reset", "verify", "billing", "payment", "checkout",
}
local SENSITIVE_PARAMS = {
    "code", "token", "access_token", "id_token", "refresh_token", "auth", "authorization",
    "session", "sessionid", "password", "pass", "ticket", "state", "nonce", "otp", "key",
}

local function lower(s) return string.lower(s or "") end

--- Pode ir pra TV dos outros? Site permitido E nao e pagina de login/conta
--- nem leva codigo de acesso no link.
function ZTV.isShareableUrl(url, domains)
    if not ZTV.isAllowedUrl(url, domains) then return false end
    local host = ZTV.hostOf(url)
    for _, p in ipairs(SENSITIVE_HOST_PREFIXES) do
        if host:sub(1, #p) == p then return false end
    end
    local path = lower(url:match("^%a+://[^/]+(/[^?#]*)") or "/")
    for segment in path:gmatch("[^/]+") do
        for _, s in ipairs(SENSITIVE_PATHS) do
            if segment == s then return false end
        end
    end
    local query = lower(url:match("%?([^#]*)") or "")
    for key in query:gmatch("([^&=]+)=?[^&]*") do
        for _, s in ipairs(SENSITIVE_PARAMS) do
            if key == s then return false end
        end
    end
    -- Fragmento (#...) nunca vai junto (ver ZTV.shareableUrl), mas um token no
    -- fragmento indica fluxo de login: nao compartilha a pagina.
    local fragment = lower(url:match("#(.*)$") or "")
    if fragment:find("token=", 1, true) or fragment:find("code=", 1, true) then return false end
    return true
end

--- Link como ele vai pros outros: sem o fragmento (#...), que pode levar token.
function ZTV.shareableUrl(url)
    return (url:gsub("#.*$", ""))
end

--- Forma "comparavel" de um link: dois links que mostram o mesmo video
--- devem dar o mesmo resultado (o YouTube acrescenta &t=, &list=, etc.).
function ZTV.canonicalUrl(url)
    if type(url) ~= "string" or url == "" then return "" end
    local host = ZTV.hostOf(url) or ""
    if host:find("youtube", 1, true) then
        local v = url:match("[?&]v=([%w_%-]+)")
        if v then return "yt:" .. v end
    elseif host == "youtu.be" then
        local v = url:match("youtu%.be/([%w_%-]+)")
        if v then return "yt:" .. v end
    end
    url = url:gsub("#.*$", "")
    return (url:gsub("/$", ""))
end

--- Acha a TV (IsoTelevision) num tile; nil se o tile nao estiver carregado ou nao tiver TV.
function ZTV.findTV(x, y, z)
    local cell = getCell()
    local sq = cell and cell:getGridSquare(x, y, z)
    if not sq then return nil end
    local objects = sq:getObjects()
    for i = 0, objects:size() - 1 do
        local obj = objects:get(i)
        if instanceof(obj, "IsoTelevision") then return obj end
    end
    return nil
end

function ZTV.isTurnedOn(tv)
    local data = tv and tv:getDeviceData()
    return data ~= nil and data:getIsTurnedOn()
end

-- ---------------------------------------------------------------------------
-- Teloes de drive-in: paredes do mapa que funcionam como uma "TV" grande.
-- Identificados pelas COORDENADAS exatas + sprite -- a mesma parede usada em
-- casas, fora dessas coordenadas, nao e afetada. A chave (key) de um telao e
-- a do seu canto (x0, y0, z0), igual a de uma TV.
--   axis   "y" = parede corre ao longo de y (x fixo); "x" = ao longo de x
--   radius alcance (tiles) do video e do som, medido do centro do telao --
--          precisa cobrir o estacionamento inteiro; full = ate aqui, volume cheio
--   indoor true = sala fechada (cinema): o som e abafado por paredes como o
--          de uma TV (outra sala/saguao ouvem pouco) e so vale do andar da
--          tela pra cima; sem indoor = ao ar livre (drive-in)
-- ---------------------------------------------------------------------------

ZTV.SCREENS = {
    {
        name = "Telao do drive-in (Rosewood)",
        sprite = "walls_interior_house_03_20",
        x0 = 8388, x1 = 8388, y0 = 12229, y1 = 12247, z0 = 1, z1 = 4,
        axis = "y",
        radius = 70, full = 35,
    },
    -- Cinema: a parede da tela continua no andar 1, mas acima do teto da sala
    -- (nao aparece de dentro) -- a tela e so o andar 0.
    {
        name = "Cinema de March Ridge (sala 1)",
        sprite = "walls_interior_house_03_20",
        x0 = 10166, x1 = 10166, y0 = 12635, y1 = 12641, z0 = 0, z1 = 0,
        axis = "y",
        radius = 20, full = 12, indoor = true,
    },
    {
        name = "Cinema de March Ridge (sala 2)",
        sprite = "walls_interior_house_03_20",
        x0 = 10166, x1 = 10166, y0 = 12646, y1 = 12652, z0 = 0, z1 = 0,
        axis = "y",
        radius = 20, full = 12, indoor = true,
    },
}

for _, def in ipairs(ZTV.SCREENS) do
    def.key = ZTV.keyOf(def.x0, def.y0, def.z0)
    def.cx = (def.x0 + def.x1) / 2 + 0.5
    def.cy = (def.y0 + def.y1) / 2 + 0.5
end

function ZTV.screenDef(key)
    for _, def in ipairs(ZTV.SCREENS) do
        if def.key == key then return def end
    end
    return nil
end

--- Telao ao qual este objeto pertence (sprite certo E dentro das coordenadas), ou nil.
function ZTV.screenDefForObject(obj)
    local sprite = obj and obj.getSprite and obj:getSprite()
    local sq = obj and obj.getSquare and obj:getSquare()
    if not sprite or not sq then return nil end
    local name, x, y, z = sprite:getName(), sq:getX(), sq:getY(), sq:getZ()
    for _, def in ipairs(ZTV.SCREENS) do
        if name == def.sprite and x >= def.x0 and x <= def.x1 and y >= def.y0 and y <= def.y1
                and z >= def.z0 and z <= def.z1 then
            return def
        end
    end
    return nil
end

--- O telao existe no mundo (pelo menos um pedaco carregado)?
local function screenLoaded(def)
    local cell = getCell()
    if not cell then return false end
    for z = def.z0, def.z1 do
        for y = def.y0, def.y1, math.max(1, def.y1 - def.y0) do
            for x = def.x0, def.x1, math.max(1, def.x1 - def.x0) do
                local sq = cell:getGridSquare(x, y, z)
                if sq then
                    local objects = sq:getObjects()
                    for i = 0, objects:size() - 1 do
                        local s = objects:get(i):getSprite()
                        if s and s:getName() == def.sprite then return true end
                    end
                end
            end
        end
    end
    return false
end

--- Existe uma TV ou um telao nesta chave? (o servidor so aceita comandos pra telas que existem)
function ZTV.screenExists(x, y, z)
    local def = ZTV.screenDef(ZTV.keyOf(x, y, z))
    if def then return screenLoaded(def) end
    return ZTV.findTV(x, y, z) ~= nil
end

--- A tela esta ligada? Telao: sempre (o projetor tem gerador proprio). TV: se estiver ligada.
function ZTV.isScreenOn(x, y, z)
    local def = ZTV.screenDef(ZTV.keyOf(x, y, z))
    if def then return screenLoaded(def) end
    return ZTV.isTurnedOn(ZTV.findTV(x, y, z))
end

--- O jogador no andar `pz` "esta" na tela? TV: mesmo andar. Drive-in (no
--- alto): do chao ate o topo. Cinema (indoor): entre o andar da tela e o topo.
function ZTV.onScreenLevel(key, pz)
    local def = ZTV.screenDef(key)
    if not def then
        local z = tonumber(key:match(",(-?%d+)$"))
        return z ~= nil and math.abs(pz - z) < 1
    end
    if pz > def.z1 then return false end
    return not def.indoor or pz >= def.z0
end

--- Distancia (tiles) do jogador ate a tela: centro do telao ou o tile da TV.
function ZTV.distanceTo(key, px, py)
    local def = ZTV.screenDef(key)
    local x, y
    if def then
        x, y = def.cx, def.cy
    else
        local kx, ky = key:match("^(-?%d+),(-?%d+)")
        x, y = tonumber(kx) + 0.5, tonumber(ky) + 0.5
    end
    local dx, dy = px - x, py - y
    return math.sqrt(dx * dx + dy * dy)
end

function ZTV.playerName(player)
    local name = player and player:getUsername()
    if not name or name == "" then name = "player" end
    return name
end

-- ---------------------------------------------------------------------------
-- Rede: no single-player nao existe servidor de verdade, e sendServerCommand
-- simplesmente nao faz nada -- entao o "servidor" chama o cliente direto.
-- (sendClientCommand ja funciona nos dois modos.)
-- ---------------------------------------------------------------------------

function ZTV.sendToServer(command, args)
    sendClientCommand(ZTV.MODULE, command, args or {})
end

function ZTV.sendToAll(command, args)
    if isServer() then
        sendServerCommand(ZTV.MODULE, command, args)
    elseif ZTVSync then
        ZTVSync.onServerCommand(ZTV.MODULE, command, args)
    end
end

function ZTV.sendToPlayer(player, command, args)
    if isServer() then
        sendServerCommand(player, ZTV.MODULE, command, args)
    elseif ZTVSync then
        ZTVSync.onServerCommand(ZTV.MODULE, command, args)
    end
end
