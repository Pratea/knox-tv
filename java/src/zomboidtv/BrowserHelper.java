package zomboidtv;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Processo auxiliar do navegador (Electron, ver helper/main.js).
 *
 * Comandos e eventos trafegam como JSON por linha num socket local: o Java
 * abre um servidor em 127.0.0.1 numa porta livre e passa a porta pro motor,
 * que conecta de volta. (O stdin do Electron nao funciona de forma confiavel
 * no Windows.) Se o processo morrer, o jogo continua normal -- so a TV para.
 */
final class BrowserHelper {
    private static final int CONNECT_TIMEOUT_MS = 20_000;

    private Process process;
    private volatile Writer out;
    private volatile String title = "";
    private volatile String url = "";
    private volatile String lastError = "";
    private volatile boolean pageFullscreen;
    /** Ultimo clique/tecla do jogador DENTRO da pagina (System.currentTimeMillis). */
    private volatile long lastUserInput;

    // Estado do video principal da pagina, lido pelo motor a cada 500ms
    private volatile boolean hasMedia;
    private volatile double mediaTime;
    private volatile double mediaDuration;
    private volatile boolean mediaPaused;
    private volatile boolean mediaEnded;
    private volatile boolean mediaAd;
    /** Retangulo do video na pagina (x, y, largura, altura em pixels do quadro). */
    private volatile int[] mediaRect = new int[4];
    /** Comandos enviados antes do motor conectar; despachados ao conectar. */
    private final List<String> pending = new ArrayList<>();

    boolean start(Config cfg, File framesFile) {
        if (isAlive()) return true;
        if (cfg.helperExe.isEmpty() || !new File(cfg.helperExe).isFile()) {
            lastError = "motor do navegador nao encontrado: '" + cfg.helperExe + "'";
            Log.info(lastError);
            return false;
        }
        try {
            ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            List<String> cmd = new ArrayList<>();
            cmd.add(cfg.helperExe);
            if (!cfg.helperApp.isEmpty()) cmd.add(cfg.helperApp);
            cmd.add("--frames");
            cmd.add(framesFile.getAbsolutePath());
            cmd.add("--width");
            cmd.add(String.valueOf(cfg.width));
            cmd.add("--height");
            cmd.add(String.valueOf(cfg.height));
            cmd.add("--fps");
            cmd.add(String.valueOf(cfg.fps));
            cmd.add("--port");
            cmd.add(String.valueOf(server.getLocalPort()));
            cmd.add("--data-dir");
            cmd.add(cfg.browserDataDir.getAbsolutePath());

            lastError = "";
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            drainLog(process);
            acceptInBackground(server);
            Log.info("motor do navegador iniciado (pid " + process.pid() + ", porta " + server.getLocalPort() + ")");
            return true;
        } catch (Exception e) {
            lastError = "falha ao iniciar o motor: " + e.getMessage();
            Log.error(lastError, e);
            return false;
        }
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    void send(String json) {
        synchronized (pending) {
            if (out == null) {
                if (isAlive()) pending.add(json);
                return;
            }
            try {
                out.write(json);
                out.write('\n');
                out.flush();
            } catch (Exception e) {
                Log.error("falha ao enviar comando ao motor", e);
            }
        }
    }

    void stop() {
        if (process == null) return;
        send("{\"cmd\":\"quit\"}");
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
        }
        process = null;
        out = null;
    }

    String getTitle() { return title; }
    String getUrl() { return url; }
    String getLastError() { return lastError; }
    /** A pagina pediu tela cheia (ex: botao de tela cheia do YouTube). */
    boolean isPageFullscreen() { return pageFullscreen; }

    /**
     * Marca que o jogador mexeu na pagina agora. A sincronizacao usa isso pra
     * distinguir "o jogador pausou/trocou o video" (vai pro servidor) de "o
     * video mudou sozinho ou por comando remoto" (nao vai, senao vira eco).
     */
    void markUserInput() { lastUserInput = System.currentTimeMillis(); }
    long msSinceUserInput() { return System.currentTimeMillis() - lastUserInput; }

    boolean hasMedia() { return hasMedia; }
    double getMediaTime() { return mediaTime; }
    double getMediaDuration() { return mediaDuration; }
    boolean isMediaPaused() { return mediaPaused; }
    boolean isMediaEnded() { return mediaEnded; }
    boolean isMediaAd() { return mediaAd; }
    /** Retangulo do video principal na pagina, ou null se nao ha video. */
    int[] getMediaRect() { return hasMedia ? mediaRect : null; }

    private void acceptInBackground(ServerSocket server) {
        daemon("KNOXTV-helper-conn", () -> {
            try (server; Socket s = acceptOnce(server)) {
                s.setTcpNoDelay(true);
                synchronized (pending) {
                    out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                    for (String json : pending) {
                        out.write(json);
                        out.write('\n');
                    }
                    out.flush();
                    pending.clear();
                }
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) onEvent(line);
            } catch (Exception e) {
                if (isAlive()) Log.error("conexao com o motor", e);
            } finally {
                out = null;
            }
        });
    }

    private static Socket acceptOnce(ServerSocket server) throws Exception {
        server.setSoTimeout(CONNECT_TIMEOUT_MS);
        return server.accept();
    }

    /** A saida do processo precisa ser drenada, senao o buffer enche e o Electron trava. */
    private void drainLog(Process p) {
        daemon("KNOXTV-helper-log", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // Eventos JSON chegam pelo socket; aqui so interessa o log do Chromium.
                    if (!line.isBlank() && !line.startsWith("{")) Log.info("motor: " + line);
                }
            } catch (Exception ignored) {
                // processo encerrou
            }
        });
    }

    private void onEvent(String line) {
        // Parse minimo: so precisamos de alguns campos e o formato e nosso.
        String event = Json.field(line, "event");
        switch (event) {
            case "title" -> title = Json.field(line, "title");
            case "navigated" -> url = Json.field(line, "url");
            case "error" -> lastError = Json.field(line, "desc") + " (" + Json.field(line, "url") + ")";
            case "crashed" -> lastError = "pagina travou: " + Json.field(line, "reason");
            case "fullscreen" -> pageFullscreen = "on".equals(Json.field(line, "value"));
            case "media" -> onMedia(line);
            default -> { }
        }
        // Navegacao/titulo/video mudam o tempo todo -- nao poluir o console.txt
        if (!"navigated".equals(event) && !"title".equals(event) && !"media".equals(event)) {
            Log.info("motor: " + line);
        }
    }

    private void onMedia(String line) {
        if (Json.bool(line, "none")) {
            hasMedia = false;
            return;
        }
        mediaTime = Json.num(line, "t", 0);
        mediaDuration = Json.num(line, "d", 0);
        mediaPaused = Json.bool(line, "p");
        mediaEnded = Json.bool(line, "e");
        mediaAd = Json.bool(line, "ad");
        mediaRect = new int[] {
            (int) Json.num(line, "rx", 0), (int) Json.num(line, "ry", 0),
            (int) Json.num(line, "rw", 0), (int) Json.num(line, "rh", 0),
        };
        hasMedia = true;
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
