-- KNOX TV - "controle remoto": botao na barra lateral esquerda (embaixo
-- dos icones do jogo) e tecla configuravel que abrem/fecham a janela do
-- navegador da tela que voce esta assistindo, sem precisar ir ate ela
-- (ex: estacionado no fundo do drive-in). A escolha da tela fica em
-- ZTVSync.toggleRemote.
--
-- Tecla e "mostrar botao" ficam em Opcoes > Mods > KNOX TV.

if not ZTV_isAvailable then return end

require "ISUI/ISButton"
require "KNOXTV/ZTV_Sync"

local ICON = "media/textures/KNOXTV_icon.png"
local GAP = 8

-- ---------------------------------------------------------------------------
-- Opcoes do mod (menu nativo do Build 42)
-- ---------------------------------------------------------------------------

local options = nil
if PZAPI and PZAPI.ModOptions then
    options = PZAPI.ModOptions:create("KNOXTV", "KNOX TV")
    options:addKeyBind("remoteKey", "Abrir/fechar o navegador", Keyboard.KEY_INSERT,
        "Abre a janela da TV ou telao que voce esta assistindo (ou o mais proximo).")
    options:addTickBox("showButton", "Mostrar botao na barra lateral", true,
        "Icone de TV embaixo dos icones da esquerda da tela.")
    options:addTickBox("mappingMode", "Modo de mapeamento (cadastrar teloes)", false,
        "Adiciona ao botao direito uma opcao que lista no console.txt os objetos em volta, pra cadastrar novos teloes (cinemas, drive-ins).")
end

local function optionValue(id, default)
    local opt = options and options:getOption(id)
    if not opt then return default end
    return opt:getValue()
end

-- ---------------------------------------------------------------------------
-- Tecla
-- ---------------------------------------------------------------------------

local function onKeyPressed(key)
    if key == optionValue("remoteKey", Keyboard.KEY_INSERT) and getPlayer() then
        ZTVSync.toggleRemote()
    end
end

Events.OnKeyPressed.Add(onKeyPressed)

-- ---------------------------------------------------------------------------
-- Botao da barra lateral
-- ---------------------------------------------------------------------------

ZTVRemoteButton = ISButton:derive("ZTVRemoteButton")

function ZTVRemoteButton:new(size)
    local o = ISButton.new(self, 0, 0, size, size, "", nil, function() ZTVSync.toggleRemote() end)
    o:setImage(getTexture(ICON))
    o:forceImageSize(size, size)
    o:setDisplayBackground(false)
    o.borderColor = { r = 1, g = 1, b = 1, a = 0 }
    o.tooltip = "KNOX TV: abrir/fechar o navegador"
    return o
end

--- Fica logo abaixo da barra de icones do jogo, que muda de altura (botoes
--- de admin etc.) e de tamanho (opcao de tamanho da barra) -- confere sempre.
function ZTVRemoteButton:prerender()
    local bar = ISEquippedItem and ISEquippedItem.instance
    if bar then
        self:setX(bar:getX() + (bar:getWidth() - self.width) / 2)
        self:setY(bar:getY() + bar:getHeight() + GAP)
    else
        self:setX(10)
        self:setY(getCore():getScreenHeight() / 2)
    end
    self:setVisible(optionValue("showButton", true) == true)
    ISButton.prerender(self)
end

local button = nil

local function createButton()
    if button then return end
    local bar = ISEquippedItem and ISEquippedItem.instance
    -- Mesmo tamanho dos icones vanilla (a barra tem opcao de tamanho)
    local size = bar and math.min(64, math.max(32, bar:getWidth() - 10)) or 40
    button = ZTVRemoteButton:new(size)
    button:initialise()
    button:addToUIManager()
end

Events.OnGameStart.Add(createButton)

-- ---------------------------------------------------------------------------
-- Modo de mapeamento: lista os objetos em volta de um tile no console.txt,
-- pra cadastrar novos teloes em ZTV.SCREENS (ZTV_Shared.lua). Pisos e
-- vegetacao ficam de fora. So aparece com a opcao ligada.
-- ---------------------------------------------------------------------------

local MAP_RADIUS = 16
local SKIP_PREFIXES = { "floors_", "blends_", "e_newgrass", "vegetation_", "f_", "d_plants" }

local function skipSprite(name)
    for _, p in ipairs(SKIP_PREFIXES) do
        if name:sub(1, #p) == p then return true end
    end
    return false
end

local function dumpAround(square)
    local cell = getCell()
    local cx, cy, cz = square:getX(), square:getY(), square:getZ()
    print("[KNOX TV] DUMP em " .. cx .. "," .. cy .. "," .. cz)
    for z = math.max(0, cz - 1), cz + 4 do
        for y = cy - MAP_RADIUS, cy + MAP_RADIUS do
            for x = cx - MAP_RADIUS, cx + MAP_RADIUS do
                local sq = cell:getGridSquare(x, y, z)
                if sq then
                    local objects = sq:getObjects()
                    for i = 0, objects:size() - 1 do
                        local obj = objects:get(i)
                        local sprite = obj:getSprite()
                        local name = sprite and sprite:getName() or "?"
                        if not skipSprite(name) then
                            print("[KNOX TV] DUMP " .. x .. "," .. y .. "," .. z .. " " .. name)
                        end
                    end
                end
            end
        end
    end
    print("[KNOX TV] DUMP fim")
    local player = getPlayer()
    if player then player:Say("KNOX TV: objetos listados no console.txt") end
end

local function onFillMappingContext(playerNum, context, worldObjects, test)
    if test or optionValue("mappingMode", false) ~= true then return end
    local obj = worldObjects[1]
    local sq = obj and obj:getSquare()
    if sq then
        context:addOption("KNOX TV (mapeamento): listar objetos em volta", nil, function() dumpAround(sq) end)
    end
end

Events.OnFillWorldObjectContextMenu.Add(onFillMappingContext)
