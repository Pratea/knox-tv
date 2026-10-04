-- KNOX TV - janela "Assistir" de uma TV (navegador com teclado, mouse e
-- tela cheia). Quem decide o que a TV esta passando e a sincronizacao
-- (ZTV_Sync.lua); esta janela so mostra e repassa a interacao do jogador.
--
-- As funcoes ZTV_* vem do .jar (ZombieBuddy); sem ele, este arquivo nao faz nada.

require "ISUI/ISCollapsableWindow"
require "ISUI/ISPanel"
require "ISUI/ISTextEntryBox"
require "ISUI/ISButton"
require "KNOXTV/ZTV_Shared"

if not ZTV_isAvailable then
    print("[KNOX TV] parte Java nao carregada (ZombieBuddy ausente ou mod Java recusado)")
    return
end

-- Patch de packs 3D do Viewpoint (ZTV_ViewpointPatch.lua): se a API do
-- Viewpoint ainda nao existia no shared/, tenta de novo aqui.
if ZTVViewpointPatchInstall then ZTVViewpointPatchInstall() end

local BAR_H = 26
local PAD = 6
local BTN_W = 32
local VIDEO_ASPECT = 16 / 9

-- ---------------------------------------------------------------------------
-- Area do video: desenha a textura e repassa mouse/teclado pro navegador
-- ---------------------------------------------------------------------------

ZTVVideoView = ISPanel:derive("ZTVVideoView")

function ZTVVideoView:new(x, y, w, h)
    local o = ISPanel.new(self, x, y, w, h)
    o.background = true
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 1 }
    o.borderColor = { r = 0.4, g = 0.4, b = 0.4, a = 1 }
    o.pressing = false
    return o
end

--- Converte coordenadas do painel pra pixels da pagina.
function ZTVVideoView:toBrowser(x, y)
    return x * ZTV_getWidth() / self.width, y * ZTV_getHeight() / self.height
end

function ZTVVideoView:sendMouse(kind, arg)
    local bx, by = self:toBrowser(self:getMouseX(), self:getMouseY())
    ZTV_mouse(kind, bx, by, arg or 0)
end

function ZTVVideoView:render()
    ISPanel.render(self)
    local tex = ZTV_getTexture()
    if tex and ZTV_getFramesRead() > 0 then
        self:drawTextureScaled(tex, 0, 0, self.width, self.height, 1, 1, 1, 1)
    else
        local msg = ZTV_getError()
        if msg == "" then msg = "Carregando..." end
        self:drawTextCentre(msg, self.width / 2, self.height / 2 - 8, 1, 1, 1, 1, UIFont.Medium)
    end
    -- Moldura azul = o teclado esta indo pro navegador
    if ZTV_hasKeyboardFocus() then
        self:drawRectBorder(0, 0, self.width, self.height, 1, 0.3, 0.7, 1)
        self:drawRectBorder(1, 1, self.width - 2, self.height - 2, 1, 0.3, 0.7, 1)
    end
end

function ZTVVideoView:onMouseDown(x, y)
    ZTV_setKeyboardFocus(true)
    self.pressing = true
    self:sendMouse("down", 0)
    return true
end

function ZTVVideoView:onMouseUp(x, y)
    self.pressing = false
    self:sendMouse("up", 0)
    return true
end

function ZTVVideoView:onMouseUpOutside(x, y)
    if self.pressing then
        self.pressing = false
        self:sendMouse("up", 0)
    end
    return false
end

function ZTVVideoView:onRightMouseDown(x, y) self:sendMouse("down", 1); return true end
function ZTVVideoView:onRightMouseUp(x, y) self:sendMouse("up", 1); return true end
function ZTVVideoView:onMouseMove(dx, dy) self:sendMouse("move"); return true end

-- Arrastando (ex: barra de progresso) e saindo da area do video
function ZTVVideoView:onMouseMoveOutside(dx, dy)
    if self.pressing then self:sendMouse("move") end
    return false
end

function ZTVVideoView:onMouseWheel(del)
    self:sendMouse("wheel", del)
    return true
end

-- ---------------------------------------------------------------------------
-- Janela do navegador
-- ---------------------------------------------------------------------------

ZTVBrowserWindow = ISCollapsableWindow:derive("ZTVBrowserWindow")
ZTVBrowserWindow.instance = nil

function ZTVBrowserWindow:new(x, y, w, h)
    local o = ISCollapsableWindow.new(self, x, y, w, h)
    o.title = "KNOX TV"
    o.resizable = true
    o.minimumWidth = 420
    o.minimumHeight = 300
    o.muted = false
    o.fullscreen = false
    o.autoFullscreen = false -- entrou em tela cheia porque a pagina pediu
    o.savedRect = nil
    o.lastUrl = ""
    return o
end

local function addButton(parent, title, onClick)
    local b = ISButton:new(0, 0, BTN_W, BAR_H, title, parent, onClick)
    b:initialise()
    parent:addChild(b)
    return b
end

function ZTVBrowserWindow:createChildren()
    ISCollapsableWindow.createChildren(self)

    self.backBtn = addButton(self, "<", ZTVBrowserWindow.onBack)
    self.fwdBtn = addButton(self, ">", ZTVBrowserWindow.onForward)
    self.reloadBtn = addButton(self, "R", ZTVBrowserWindow.onReload)

    self.urlBox = ISTextEntryBox:new("", 0, 0, 100, BAR_H)
    self.urlBox:initialise()
    self.urlBox:instantiate()
    self.urlBox.onCommandEntered = function() self:onGo() end
    self:addChild(self.urlBox)

    self.goBtn = addButton(self, "Ir", ZTVBrowserWindow.onGo)
    self.muteBtn = addButton(self, "Mudo", ZTVBrowserWindow.onMute)
    self.fullBtn = addButton(self, "Tela cheia", ZTVBrowserWindow.onFullscreen)
    self.goBtn:setWidth(40)
    self.muteBtn:setWidth(56)
    self.fullBtn:setWidth(80)

    self.video = ZTVVideoView:new(0, 0, 100, 100)
    self.video:initialise()
    self:addChild(self.video)

    self:layout()
end

--- Posiciona barra de ferramentas e video conforme o tamanho atual da janela.
function ZTVBrowserWindow:layout()
    local top = self:titleBarHeight() + PAD
    local x = PAD
    for _, b in ipairs({ self.backBtn, self.fwdBtn, self.reloadBtn }) do
        b:setX(x); b:setY(top)
        x = x + b.width + 4
    end
    local right = self.width - PAD
    for _, b in ipairs({ self.fullBtn, self.muteBtn, self.goBtn }) do
        right = right - b.width
        b:setX(right); b:setY(top)
        right = right - 4
    end
    self.urlBox:setX(x); self.urlBox:setY(top)
    self.urlBox:setWidth(math.max(60, right - x))

    -- Video em 16:9, centralizado no espaco que sobrar
    local areaY = top + BAR_H + PAD
    local bottomPad = PAD + (self.resizable and self:resizeWidgetHeight() or 0)
    local areaW = self.width - PAD * 2
    local areaH = self.height - areaY - bottomPad
    local vw = math.min(areaW, areaH * VIDEO_ASPECT)
    local vh = vw / VIDEO_ASPECT
    self.video:setX(PAD + (areaW - vw) / 2)
    self.video:setY(areaY + (areaH - vh) / 2)
    self.video:setWidth(vw)
    self.video:setHeight(vh)

    self.layoutW, self.layoutH = self.width, self.height
end

function ZTVBrowserWindow:prerender()
    -- Redimensionou (pelo canto ou pela tela cheia): refaz o layout
    if self.width ~= self.layoutW or self.height ~= self.layoutH then self:layout() end

    -- Botao de tela cheia da propria pagina amplia a janela junto
    local pageFull = ZTV_isPageFullscreen()
    if pageFull and not self.fullscreen then
        self:setFullscreen(true)
        self.autoFullscreen = true
    elseif not pageFull and self.fullscreen and self.autoFullscreen then
        self:setFullscreen(false)
    end

    -- Barra de endereco acompanha a pagina (menos quando o usuario esta digitando nela)
    local url = ZTV_getUrl()
    if url ~= self.lastUrl and not self.urlBox:isFocused() then
        self.urlBox:setText(url)
        self.lastUrl = url
    end

    local t = ZTV_getTitle()
    if not ZTVSync.session or ZTVSync.session.key ~= self.tvKey then
        t = "TV desligada"
    end
    self.title = t ~= "" and ("KNOX TV - " .. t) or "KNOX TV"
    if ZTV_hasKeyboardFocus() then
        self.title = self.title .. "   [teclado no navegador - Esc devolve ao jogo]"
    end

    ISCollapsableWindow.prerender(self)
end

function ZTVBrowserWindow:setFullscreen(on)
    if on == self.fullscreen then return end
    if on then
        self.savedRect = { x = self:getX(), y = self:getY(), w = self.width, h = self.height }
        self:setX(0); self:setY(0)
        self:setWidth(getCore():getScreenWidth())
        self:setHeight(getCore():getScreenHeight())
        self.fullBtn:setTitle("Janela")
    else
        local r = self.savedRect
        if r then
            self:setX(r.x); self:setY(r.y)
            self:setWidth(r.w); self:setHeight(r.h)
        end
        self.fullBtn:setTitle("Tela cheia")
        self.autoFullscreen = false
    end
    self.fullscreen = on
    self:setResizable(not on)
    self:layout()
end

-- Botoes da janela contam como acao do jogador: a navegacao resultante e
-- publicada pra todos (ver ZTV_Sync.lua).
function ZTVBrowserWindow:onGo()
    ZTVSync.markUserInput()
    local url = self.urlBox:getText():match("^%s*(.-)%s*$")
    if url ~= "" and not url:match("^%a+://") then url = "https://" .. url end
    if not ZTV.isAllowedUrl(url) then
        self.urlBox:setText("Site nao permitido: " .. (ZTV.hostOf(url) or url))
        self.lastUrl = ""
        return
    end
    ZTV_load(url)
    self.urlBox:unfocus()
end

function ZTVBrowserWindow:onBack() ZTVSync.markUserInput(); ZTV_command("back") end
function ZTVBrowserWindow:onForward() ZTVSync.markUserInput(); ZTV_command("forward") end
function ZTVBrowserWindow:onReload() ZTVSync.markUserInput(); ZTV_command("reload") end

function ZTVBrowserWindow:onMute()
    self.muted = not self.muted
    ZTV_setMuted(self.muted)
    self.muteBtn:setTitle(self.muted and "Som" or "Mudo")
end

function ZTVBrowserWindow:onFullscreen()
    self:setFullscreen(not self.fullscreen)
end

--- Fechar a janela NAO para o video: a TV continua passando pra quem esta perto.
function ZTVBrowserWindow:close()
    ZTV_setKeyboardFocus(false)
    ISCollapsableWindow.close(self)
    self:removeFromUIManager()
    ZTVBrowserWindow.instance = nil
    ZTVSync.onWindowClosed()
end

function ZTVBrowserWindow.open(tvKey)
    if not ZTV_start() then
        print("[KNOX TV] nao foi possivel iniciar o motor: " .. tostring(ZTV_getError()))
    end
    local existing = ZTVBrowserWindow.instance
    if existing then
        existing.tvKey = tvKey
        existing:bringToTop()
        return
    end
    local w, h = 972, 610
    local x = (getCore():getScreenWidth() - w) / 2
    local y = (getCore():getScreenHeight() - h) / 2
    local win = ZTVBrowserWindow:new(x, y, w, h)
    win.tvKey = tvKey
    win:initialise()
    win:addToUIManager()
    ZTVBrowserWindow.instance = win
end

-- ---------------------------------------------------------------------------
-- Envio dos quadros: uma vez por quadro (a fila do Java ignora repeticoes)
-- ---------------------------------------------------------------------------

local function tick() ZTV_update() end
Events.OnPreUIDraw.Add(tick)
Events.OnTick.Add(tick)


print("[KNOX TV] janela do navegador carregada")
