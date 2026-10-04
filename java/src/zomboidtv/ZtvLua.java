package zomboidtv;

import java.io.File;

import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.core.textures.Texture;
import zombie.iso.IsoObject;

/**
 * Funcoes globais expostas ao Lua (o ZombieBuddy registra sozinho todo metodo
 * estatico com @LuaMethod(global = true) dentro do pacote do mod).
 *
 * Fase 1: um unico navegador. Nas proximas fases isso vira um navegador por TV.
 */
public final class ZtvLua {
    private static Config config;
    private static BrowserHelper helper;
    private static FrameSource frames;
    private static GlBridge gl;
    private static BrowserKeyboard keyboard;
    /** Tela de TV e telao de drive-in; so um fica ativo (`activeScreen`). */
    private static ScreenRenderer tvScreen;
    private static WallScreenRenderer wallScreen;
    private static ScreenTarget activeScreen;

    private ZtvLua() {}

    @LuaMethod(name = "ZTV_isAvailable", global = true)
    public static boolean isAvailable() {
        return true;
    }

    /** Inicia o motor (se ainda nao estiver rodando). Pode ser chamado varias vezes. */
    @LuaMethod(name = "ZTV_start", global = true)
    public static synchronized boolean start() {
        try {
            if (config == null) config = Config.load();
            if (frames == null) {
                frames = new FrameSource(new File(config.dataDir, "frames-0.bin"));
                frames.open();
            }
            if (gl == null) gl = new GlBridge(frames, config.width, config.height);
            if (helper == null) helper = new BrowserHelper();
            if (keyboard == null) keyboard = new BrowserKeyboard(helper);
            if (tvScreen == null) tvScreen = new ScreenRenderer(helper, config.screenScale, config.screenCrop);
            if (wallScreen == null) wallScreen = new WallScreenRenderer(helper);
            return helper.start(config, new File(config.dataDir, "frames-0.bin"));
        } catch (Exception e) {
            Log.error("ZTV_start", e);
            return false;
        }
    }

    @LuaMethod(name = "ZTV_isRunning", global = true)
    public static boolean isRunning() {
        return helper != null && helper.isAlive();
    }

    @LuaMethod(name = "ZTV_load", global = true)
    public static void load(String url) {
        if (helper != null) helper.send("{\"cmd\":\"load\",\"url\":" + Json.str(url) + "}");
    }

    /** type: "down" | "up" | "move" | "wheel"; x/y em pixels do navegador; arg = botao ou delta da roda. */
    @LuaMethod(name = "ZTV_mouse", global = true)
    public static void mouse(String type, double x, double y, double arg) {
        if (helper == null) return;
        if (!"move".equals(type)) helper.markUserInput();
        String extra = "wheel".equals(type) ? ",\"delta\":" + arg : ",\"button\":" + (int) arg;
        helper.send("{\"cmd\":\"mouse\",\"type\":" + Json.str(type) + ",\"x\":" + (int) x + ",\"y\":" + (int) y + extra + "}");
    }

    /** Comandos simples sem argumento: back, forward, reload. */
    @LuaMethod(name = "ZTV_command", global = true)
    public static void command(String cmd) {
        if (helper != null) helper.send("{\"cmd\":" + Json.str(cmd) + "}");
    }

    /** Da (ou devolve) o teclado ao navegador. Com ele, o personagem para de reagir as teclas. */
    @LuaMethod(name = "ZTV_setKeyboardFocus", global = true)
    public static void setKeyboardFocus(boolean focused) {
        if (keyboard != null) keyboard.setFocused(focused);
    }

    @LuaMethod(name = "ZTV_hasKeyboardFocus", global = true)
    public static boolean hasKeyboardFocus() {
        return keyboard != null && keyboard.isFocused();
    }

    @LuaMethod(name = "ZTV_isPageFullscreen", global = true)
    public static boolean isPageFullscreen() {
        return helper != null && helper.isPageFullscreen();
    }

    // --- Video da pagina (sincronizacao) -----------------------------------

    @LuaMethod(name = "ZTV_hasMedia", global = true)
    public static boolean hasMedia() {
        return helper != null && helper.hasMedia();
    }

    @LuaMethod(name = "ZTV_getMediaTime", global = true)
    public static double getMediaTime() {
        return helper != null ? helper.getMediaTime() : 0;
    }

    @LuaMethod(name = "ZTV_getMediaDuration", global = true)
    public static double getMediaDuration() {
        return helper != null ? helper.getMediaDuration() : 0;
    }

    @LuaMethod(name = "ZTV_isMediaPaused", global = true)
    public static boolean isMediaPaused() {
        return helper == null || helper.isMediaPaused();
    }

    @LuaMethod(name = "ZTV_isMediaEnded", global = true)
    public static boolean isMediaEnded() {
        return helper != null && helper.isMediaEnded();
    }

    /** Anuncio do YouTube passando (a sincronizacao espera ele acabar). */
    @LuaMethod(name = "ZTV_isMediaAd", global = true)
    public static boolean isMediaAd() {
        return helper != null && helper.isMediaAd();
    }

    /** action: "play" | "pause" | "seek" (time em segundos, so pro seek). */
    @LuaMethod(name = "ZTV_media", global = true)
    public static void media(String action, double time) {
        if (helper != null) {
            helper.send("{\"cmd\":\"media\",\"action\":" + Json.str(action) + ",\"time\":" + time + "}");
        }
    }

    /** Volume 0..1 aplicado em todo audio/video da pagina. */
    @LuaMethod(name = "ZTV_setVolume", global = true)
    public static void setVolume(double volume) {
        if (helper != null) helper.send("{\"cmd\":\"volume\",\"value\":" + volume + "}");
    }

    /** Milissegundos desde o ultimo clique/tecla do jogador dentro da pagina. */
    @LuaMethod(name = "ZTV_getMsSinceInput", global = true)
    public static double getMsSinceInput() {
        return helper != null ? helper.msSinceUserInput() : Double.MAX_VALUE;
    }

    @LuaMethod(name = "ZTV_setMuted", global = true)
    public static void setMuted(boolean muted) {
        if (helper != null) helper.send("{\"cmd\":\"mute\",\"value\":" + muted + "}");
    }

    /** Chamar todo quadro (OnPreUIDraw): agenda o envio do quadro novo pra placa de video. */
    @LuaMethod(name = "ZTV_update", global = true)
    public static void update() {
        if (activeScreen != null) activeScreen.gameTick();
        if (gl != null && isRunning()) gl.update();
    }

    /** Troca a tela do mundo que mostra o video (solta a anterior). */
    private static void activate(ScreenTarget target) {
        if (activeScreen != null && activeScreen != target) activeScreen.detach();
        activeScreen = target;
        if (gl != null) gl.setScreen(target);
    }

    /** Mostra o video na tela desta TV do mundo (so neste PC). */
    @LuaMethod(name = "ZTV_attachScreen", global = true)
    public static boolean attachScreen(IsoObject tv) {
        if (tvScreen == null) return false;
        activate(tvScreen);
        return tvScreen.attach(tv);
    }

    /**
     * Mostra o video no telao de um drive-in: paredes `sprite` na faixa
     * [x0..x1] x [y0..y1] x andares [z0..z1]; axis "x" ou "y" = direcao da parede.
     */
    @LuaMethod(name = "ZTV_attachWall", global = true)
    public static boolean attachWall(double x0, double x1, double y0, double y1, double z0, double z1,
                                     String sprite, String axis) {
        if (wallScreen == null) return false;
        activate(wallScreen);
        return wallScreen.attach((int) x0, (int) x1, (int) y0, (int) y1, (int) z0, (int) z1, sprite, axis);
    }

    /** Devolve o visual original pra TV/telao que estava mostrando o video. */
    @LuaMethod(name = "ZTV_detachScreen", global = true)
    public static void detachScreen() {
        activate(null);
    }

    /** "x,y,z" da TV ou da ancora do telao que esta mostrando o video, ou nil. */
    @LuaMethod(name = "ZTV_getScreenId", global = true)
    public static String getScreenId() {
        return activeScreen != null ? activeScreen.getId() : null;
    }

    @LuaMethod(name = "ZTV_getTexture", global = true)
    public static Texture getTexture() {
        return gl != null ? gl.getTexture() : null;
    }

    @LuaMethod(name = "ZTV_getWidth", global = true)
    public static int getWidth() {
        return config != null ? config.width : 1280;
    }

    @LuaMethod(name = "ZTV_getHeight", global = true)
    public static int getHeight() {
        return config != null ? config.height : 720;
    }

    @LuaMethod(name = "ZTV_getTitle", global = true)
    public static String getTitle() {
        return helper != null ? helper.getTitle() : "";
    }

    @LuaMethod(name = "ZTV_getUrl", global = true)
    public static String getUrl() {
        return helper != null ? helper.getUrl() : "";
    }

    @LuaMethod(name = "ZTV_getError", global = true)
    public static String getError() {
        return helper != null ? helper.getLastError() : "";
    }

    @LuaMethod(name = "ZTV_getFramesRead", global = true)
    public static double getFramesRead() {
        return frames != null ? frames.getFramesRead() : 0;
    }

    @LuaMethod(name = "ZTV_stop", global = true)
    public static synchronized void stop() {
        activate(null);
        if (keyboard != null) keyboard.setFocused(false);
        if (helper != null) helper.stop();
    }

    static void shutdown() {
        try {
            if (helper != null) helper.stop();
            if (frames != null) frames.close();
        } catch (Throwable ignored) {
            // jogo fechando, nada a fazer
        }
    }
}
