package zomboidtv;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

import zombie.ZomboidFileSystem;

/**
 * Configuracao local do mod.
 *
 * Por padrao o motor de navegador e o que vem DENTRO do mod
 * (KNOXTV/42/engine/win-x64/KNOXTVEngine.exe), achado a partir da
 * localizacao do proprio .jar -- quem assina na Workshop nao configura nada.
 *
 * Zomboid/KNOXTV/config.properties e opcional e serve pra desenvolvimento:
 *   helper.exe  outro executavel do motor (ex: electron.exe do npm)
 *   helper.app  pasta do app, quando helper.exe e o Electron "cru"
 *   width/height/fps  resolucao e taxa de quadros do navegador
 *   screen.scale      pixels da tela da TV por pixel vanilla (padrao 1)
 *   screen.crop       textura so no retangulo da tela (padrao false)
 */
final class Config {
    private static final String ENGINE_EXE = "engine/win-x64/KNOXTVEngine.exe";

    final File dataDir;
    final File browserDataDir;
    final String helperExe;
    final String helperApp;
    final int width;
    final int height;
    final int fps;
    /** Resolucao da tela da TV no mundo (ver ScreenRenderer). */
    final int screenScale;
    final boolean screenCrop;

    private Config(File dataDir, Properties p) {
        this.dataDir = dataDir;
        this.browserDataDir = new File(dataDir, "browser-data");
        String exe = p.getProperty("helper.exe", "").trim();
        this.helperExe = exe.isEmpty() ? bundledEngine() : exe;
        this.helperApp = exe.isEmpty() ? "" : p.getProperty("helper.app", "").trim();
        this.width = intProp(p, "width", 1280);
        this.height = intProp(p, "height", 720);
        this.fps = intProp(p, "fps", 30);
        this.screenScale = intProp(p, "screen.scale", 1);
        this.screenCrop = Boolean.parseBoolean(p.getProperty("screen.crop", "false").trim());
    }

    static Config load() {
        File cache = new File(ZomboidFileSystem.instance.getCacheDir());
        File dir = new File(cache, "KNOXTV");
        // O mod se chamava ZomboidTV: leva a pasta antiga (logins do navegador,
        // config) pro nome novo na primeira vez, pra ninguem ser deslogado.
        File legacy = new File(cache, "ZomboidTV");
        if (!dir.exists() && legacy.isDirectory()) {
            if (legacy.renameTo(dir)) Log.info("dados movidos de " + legacy + " para " + dir);
            else Log.info("nao foi possivel mover " + legacy + " -- comecando do zero em " + dir);
        }
        // A pasta nova pode ja existir (criada antes da migracao) sem os
        // logins: nesse caso traz so o browser-data da pasta antiga.
        File newBrowser = new File(dir, "browser-data");
        File oldBrowser = new File(legacy, "browser-data");
        if (!newBrowser.exists() && oldBrowser.isDirectory()) {
            dir.mkdirs();
            if (oldBrowser.renameTo(newBrowser)) Log.info("logins do navegador movidos para " + newBrowser);
        }
        dir.mkdirs();
        Properties p = new Properties();
        File f = new File(dir, "config.properties");
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
                Log.info("usando " + f);
            } catch (Exception e) {
                Log.error("falha ao ler " + f, e);
            }
        }
        Config cfg = new Config(dir, p);
        Log.info("motor: " + cfg.helperExe);
        return cfg;
    }

    /**
     * Motor que vem no mod. O .jar fica em <mod>/42/media/java/client/, entao
     * a pasta da versao (42) e quatro niveis acima dele.
     */
    private static String bundledEngine() {
        try {
            File jar = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File versionDir = jar.getParentFile().getParentFile().getParentFile().getParentFile();
            return new File(versionDir, ENGINE_EXE).getAbsolutePath();
        } catch (Exception e) {
            Log.error("nao foi possivel localizar a pasta do mod", e);
            return "";
        }
    }

    private static int intProp(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
