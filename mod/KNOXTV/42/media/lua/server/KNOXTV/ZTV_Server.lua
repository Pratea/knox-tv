-- KNOX TV - servidor (tambem roda no single-player).
--
-- Guarda o estado de cada TV num ModData global (salvo junto com o mundo):
--   { x, y, z, url, playing, pos, at, by, seq }
-- `pos` e a posicao do video (segundos) no instante `at` (relogio do
-- servidor, ms). Quem chega depois recebe a posicao ja adiantada pro "agora",
-- entao ninguem precisa ter o relogio sincronizado com o servidor.
--
-- Comandos dos clientes (sendClientCommand "KNOX TV"):
--   set  {x,y,z,url,playing,pos}  -- troca o que a TV esta passando
--   off  {x,y,z}                  -- para o video da TV
--   list {}                       -- pede o estado de todas as TVs

if isClient() then return end

require "KNOXTV/ZTV_Shared"

local DOMAINS_FILE = "KNOXTV/allowed_domains.txt"
-- O mod se chamava ZomboidTV: se o servidor ja tinha uma lista la, ela vale
local LEGACY_DOMAINS_FILE = "ZomboidTV/allowed_domains.txt"
local RATE_LIMIT = 8 -- comandos por segundo por jogador

local allowedDomains = nil
local recentCommands = {}

local function registry()
    -- Nome antigo de proposito: e o que esta gravado no save do mundo
    return ModData.getOrCreate("ZomboidTV_TVs")
end

local function readDomains(file)
    local domains = {}
    local reader = getFileReader(file, false)
    if reader then
        local line = reader:readLine()
        while line do
            line = line:gsub("#.*$", ""):match("^%s*(.-)%s*$"):lower()
            if line ~= "" then table.insert(domains, line) end
            line = reader:readLine()
        end
        reader:close()
    end
    return domains
end

local function writeDomains(domains)
    local writer = getFileWriter(DOMAINS_FILE, true, false)
    if not writer then return end
    writer:write("# KNOX TV: sites que podem ser colocados nas TVs (um por linha).\n")
    writer:write("# Subdominios entram junto (youtube.com libera www.youtube.com e m.youtube.com).\n")
    for _, d in ipairs(domains) do writer:write(d .. "\n") end
    writer:close()
end

--- Le a lista de sites permitidos (cria o arquivo com a lista padrao na 1a vez).
local function loadDomains()
    local domains = readDomains(DOMAINS_FILE)
    if #domains == 0 then
        domains = readDomains(LEGACY_DOMAINS_FILE)
        if #domains == 0 then domains = ZTV.DEFAULT_DOMAINS end
        writeDomains(domains)
    end
    print("[KNOX TV] sites permitidos: " .. table.concat(domains, ", "))
    return domains
end

--- Copia do estado com a posicao adiantada pro instante atual.
local function stateNow(s)
    local pos = s.pos
    if s.playing then pos = pos + (getTimestampMs() - s.at) / 1000 end
    return { x = s.x, y = s.y, z = s.z, url = s.url, playing = s.playing, pos = pos, by = s.by, seq = s.seq }
end

local function rateLimited(player)
    local name = ZTV.playerName(player)
    local now = getTimestampMs()
    local list = recentCommands[name] or {}
    local fresh = {}
    for _, t in ipairs(list) do
        if now - t < 1000 then table.insert(fresh, t) end
    end
    if #fresh >= RATE_LIMIT then
        recentCommands[name] = fresh
        return true
    end
    table.insert(fresh, now)
    recentCommands[name] = fresh
    return false
end

--- O jogador esta perto o bastante de uma TV (ou telao) que existe de verdade?
local function validTarget(player, args)
    local x, y, z = tonumber(args.x), tonumber(args.y), tonumber(args.z)
    if not (x and y and z) then return nil end
    x, y, z = math.floor(x), math.floor(y), math.floor(z)
    local key = ZTV.keyOf(x, y, z)
    local def = ZTV.screenDef(key)
    local maxDist = def and def.radius or ZTV.MAX_CONTROL_DIST
    if ZTV.distanceTo(key, player:getX(), player:getY()) > maxDist then return nil end
    -- Telao fica no alto (andares acima do chao): vale do chao ate o topo dele
    if def then
        if player:getZ() > def.z1 then return nil end
    elseif math.abs(player:getZ() - z) > 1 then
        return nil
    end
    if not ZTV.screenExists(x, y, z) then return nil end
    return x, y, z
end

local Commands = {}

function Commands.set(player, args)
    local x, y, z = validTarget(player, args)
    if not x then return end
    local url = tostring(args.url or "")
    if not ZTV.isShareableUrl(url, allowedDomains) then
        -- So o site no log: o link recusado pode levar um codigo de acesso
        print("[KNOX TV] link recusado de " .. ZTV.playerName(player) .. ": " .. tostring(ZTV.hostOf(url)))
        return
    end
    url = ZTV.shareableUrl(url)
    local key = ZTV.keyOf(x, y, z)
    local reg = registry()
    local old = reg[key]
    local s = {
        x = x, y = y, z = z,
        url = url,
        playing = args.playing == true,
        pos = math.max(0, tonumber(args.pos) or 0),
        at = getTimestampMs(),
        by = ZTV.playerName(player),
        seq = (old and old.seq or 0) + 1,
    }
    reg[key] = s
    ZTV.sendToAll("state", stateNow(s))
end

function Commands.off(player, args)
    local x, y, z = validTarget(player, args)
    if not x then return end
    local key = ZTV.keyOf(x, y, z)
    registry()[key] = nil
    ZTV.sendToAll("off", { x = x, y = y, z = z })
end

function Commands.list(player, args)
    for _, s in pairs(registry()) do
        ZTV.sendToPlayer(player, "state", stateNow(s))
    end
end

local function onClientCommand(module, command, player, args)
    if module ~= ZTV.MODULE or not player then return end
    local handler = Commands[command]
    if not handler or rateLimited(player) then return end
    if not allowedDomains then allowedDomains = loadDomains() end
    handler(player, args or {})
end

Events.OnClientCommand.Add(onClientCommand)
