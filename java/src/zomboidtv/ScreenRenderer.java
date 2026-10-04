package zomboidtv;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoTelevision;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Coloca o video na tela de uma TV do mundo.
 *
 * A TV vanilla desenha a tela como uma camada por cima do movel
 * (`overlaySprite`, escolhida da lista `screenSprites`; o sprite da tela e o
 * da TV com indice +16). Aqui essa camada e trocada por um sprite NOSSO:
 *
 *   1. Uma vez (thread de renderizacao): le da placa de video o alfa da tela
 *      vanilla e acha a forma da tela -- so a parte SOLIDA (a tela vanilla
 *      tem um brilho semitransparente em volta, que nao e tela). As bordas
 *      de cima e de baixo viram retas (minimos quadrados), o que acompanha a
 *      inclinacao isometrica e da contorno liso, sem copiar o serrilhado.
 *   2. Thread do jogo: cria a textura -- por padrao do tamanho exato do
 *      sprite vanilla (o unico formato que o Viewpoint mostra). Opcionalmente
 *      `scale` x mais pixels e/ou recortada no retangulo da tela, "dizendo"
 *      pro jogo o tamanho vanilla (tamanho logico + deslocamento).
 *   3. A cada quadro (ate SCREEN_FPS): monta a imagem pela media de algumas
 *      amostras do video (so o retangulo do <video> da pagina) e envia.
 *   4. Thread do jogo: invalida o cache de desenho do pedaco do mapa -- o
 *      mesmo que a TV vanilla faz ao trocar de tela.
 *
 * Como a camada e a mesma que o jogo usa, o Viewpoint (1a pessoa) tambem
 * mostra o video. A troca e so local: nada disso vai pela rede.
 */
final class ScreenRenderer implements ScreenTarget {
    /** Mesmo flag que IsoTelevision.updateTvScreen usa ao trocar de tela. */
    private static final long DIRTY_FLAGS = 256L;
    private static final int SCREEN_SPRITE_OFFSET = 16;
    private static final int SCREEN_FPS = 24;
    /**
     * Pixels da nossa tela por pixel da tela vanilla, em cada eixo, e se a
     * textura cobre so o retangulo da tela (recorte) ou o sprite inteiro.
     * Padrao 1 / sem recorte: o Viewpoint (1a pessoa) NAO mostra a tela quando
     * a textura tem tamanho real diferente do tamanho declarado ao jogo --
     * ele parece ler a textura pelo tamanho em pixels declarado. Configuravel
     * (screen.scale / screen.crop) pra testar outras combinacoes.
     */
    private final int scale;
    private final boolean crop;
    /** Amostras por eixo na media de cada pixel da tela. */
    private static final int SAMPLES = 2;
    /** Alfa a partir do qual um pixel da tela vanilla conta como tela (o brilho em volta fica abaixo). */
    private static final int CORE_ALPHA = 200;
    /** Recuo das bordas (em pixels vanilla) pra o video nao encostar na moldura. */
    private static final double INSET = 0.35;
    /** Area minima pra considerar o retangulo do <video> (evita miniaturas). */
    private static final int MIN_VIDEO_AREA = 200 * 120;

    /**
     * Forma da tela ja calculada.
     *   width/height       tamanho da textura (pixels reais, SCALE x)
     *   offX/offY          canto do retangulo da tela dentro do sprite vanilla
     *   origW/origH        tamanho "original" do sprite vanilla
     *   dst/u/v/alpha      pra cada pixel da tela: indice, ponto no video, cobertura
     *   du/dv              largura/altura de um pixel da tela em fracao do video
     */
    record Mapping(int width, int height, int offX, int offY, int origW, int origH,
                   int[] dst, float[] u, float[] v, byte[] alpha, float du, float dv) {}

    private final BrowserHelper helper;

    private IsoTelevision tv;
    private IsoSprite baseSprite;
    private List<IsoSprite> savedScreens;
    private IsoSprite savedOverlay;
    private IsoSprite sprite;
    private Texture texture;
    private ByteBuffer out;
    /** Calculado na thread de renderizacao; a textura e criada a partir dele na do jogo. */
    private volatile Mapping pendingMapping;
    private volatile Mapping mapping;
    private volatile boolean dirty;
    private long lastRenderMs;

    ScreenRenderer(BrowserHelper helper, int scale, boolean crop) {
        this.helper = helper;
        this.scale = Math.max(1, Math.min(8, scale));
        this.crop = crop;
    }

    IsoObject getTV() {
        return tv;
    }

    @Override
    public String getId() {
        return tv == null || tv.getSquare() == null ? null
                : tv.getSquare().getX() + "," + tv.getSquare().getY() + "," + tv.getSquare().getZ();
    }

    // --- Thread do jogo ----------------------------------------------------

    /** Passa a mostrar o video nesta TV (e solta a anterior, se houver). */
    boolean attach(IsoObject obj) {
        if (!(obj instanceof IsoTelevision target)) return false;
        if (target == tv) return true;
        detach();

        IsoSprite base = target.getSprite();
        IsoSprite screen = base == null ? null
                : IsoSprite.getSprite(IsoSpriteManager.instance, base.getName(), SCREEN_SPRITE_OFFSET);
        Texture screenTex = screen == null ? null : screen.getTextureForCurrentFrame(target.getDir());
        if (screenTex == null) {
            Log.info("TV sem sprite de tela (" + (base != null ? base.getName() : "?") + ")");
            return false;
        }

        sprite = IsoSprite.CreateSprite(IsoSpriteManager.instance);
        sprite.name = "ZomboidTV_screen";
        if (scale == 1 && !crop) {
            // Formato padrao: textura do tamanho do sprite, ja criada aqui. O
            // sprite NUNCA troca de textura -- o Viewpoint pode guardar a
            // primeira que viu.
            int w = screenTex.getWidthOrig(), h = screenTex.getHeightOrig();
            texture = new Texture(w, h, 0);
            out = ByteBuffer.allocateDirect(w * h * 4);
            sprite.texture = texture;
        } else {
            // Ate a forma da tela ficar pronta, a camada mostra a tela vanilla.
            sprite.texture = screenTex;
        }
        sprite.depthTexture = screen.depthTexture; // mesma ordem de desenho da tela vanilla
        mapping = null;
        pendingMapping = null;

        tv = target;
        baseSprite = base;
        savedScreens = new ArrayList<>(screenSprites(target));
        savedOverlay = target.overlaySprite;
        target.clearTvScreenSprites();
        target.addTvScreenSprite(sprite);
        target.overlaySprite = sprite;
        target.invalidateRenderChunkLevel(DIRTY_FLAGS);

        RenderThread.queueInvokeOnRenderContext(() -> readScreenShape(screenTex));
        Log.info("video na TV " + base.getName());
        return true;
    }

    /** Devolve a tela vanilla pra TV. */
    @Override
    public void detach() {
        if (tv == null) return;
        IsoTelevision old = tv;
        tv = null;
        mapping = null;
        pendingMapping = null;
        try {
            old.clearTvScreenSprites();
            for (IsoSprite s : savedScreens) old.addTvScreenSprite(s);
            if (old.overlaySprite == sprite) old.overlaySprite = savedOverlay;
            if (old.getSquare() != null) old.invalidateRenderChunkLevel(DIRTY_FLAGS);
        } catch (Throwable e) {
            // TV de uma partida que ja foi descarregada (voltou pro menu): nada a restaurar
        }
        Texture t = texture;
        if (t != null) RenderThread.queueInvokeOnRenderContext(() -> t.getTextureId().destroy());
        texture = null;
        sprite = null;
        savedScreens = null;
    }

    /**
     * Todo quadro (thread do jogo). A TV vanilla pode recolocar a propria tela
     * (ligou de novo, girou o movel...), entao a nossa e reafirmada aqui.
     */
    @Override
    public void gameTick() {
        IsoTelevision t = tv;
        if (t == null) return;
        if (t.getSquare() == null) {
            // TV removida do mundo (pegaram, quebrou, chunk descarregou)
            detach();
            return;
        }
        if (t.getSprite() != baseSprite) {
            // Giraram a TV: a tela muda de formato -- refaz do zero.
            detach();
            attach(t);
            return;
        }
        Mapping pm = pendingMapping;
        if (pm != null && mapping == null) {
            if (texture == null) createTexture(pm);
            else mapping = pm; // formato padrao: a textura ja existe desde o attach
        }

        boolean on = t.getDeviceData() != null && t.getDeviceData().getIsTurnedOn();
        if (on && t.overlaySprite != sprite) {
            t.overlaySprite = sprite;
            dirty = true;
        }
        if (dirty) {
            dirty = false;
            t.invalidateRenderChunkLevel(DIRTY_FLAGS);
        }
    }

    /**
     * Textura com `scale` x mais pixels, mas com tamanho logico, tamanho
     * "original" e deslocamento iguais aos da tela vanilla -- o jogo desenha
     * ela no mesmo lugar e tamanho, so que com mais detalhe. As coordenadas
     * de textura (xEnd/yEnd) continuam cobrindo a imagem inteira.
     */
    private void createTexture(Mapping m) {
        Texture t = new Texture(m.width(), m.height(), 0);
        try {
            setIntField(t, "width", m.width() / scale);
            setIntField(t, "height", m.height() / scale);
            setIntField(t, "widthOrig", m.origW());
            setIntField(t, "heightOrig", m.origH());
        } catch (ReflectiveOperationException e) {
            Log.error("nao foi possivel ajustar o tamanho da textura da tela", e);
            return;
        }
        t.offsetX = m.offX();
        t.offsetY = m.offY();
        out = ByteBuffer.allocateDirect(m.width() * m.height() * 4);
        texture = t;
        mapping = m;
        sprite.texture = t;
        dirty = true;
        Log.info("tela da TV: " + m.width() + "x" + m.height() + " pixels (" + scale + "x" + (crop ? ", recortada" : "") + ")");
    }

    private static void setIntField(Texture t, String name, int value) throws ReflectiveOperationException {
        Field f = Texture.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(t, value);
    }

    @SuppressWarnings("unchecked")
    private static List<IsoSprite> screenSprites(IsoTelevision t) {
        try {
            Field f = IsoTelevision.class.getDeclaredField("screenSprites");
            f.setAccessible(true);
            return (List<IsoSprite>) f.get(t);
        } catch (ReflectiveOperationException e) {
            Log.error("nao foi possivel ler as telas da TV", e);
            return List.of();
        }
    }

    // --- Thread de renderizacao ---------------------------------------------

    /** Le o alfa da tela vanilla direto do atlas e calcula a forma da tela. */
    private void readScreenShape(Texture screenTex) {
        byte[] alpha = readAlpha(screenTex);
        if (alpha == null) return;
        Mapping m = computeMapping(alpha, screenTex.getWidthOrig(), screenTex.getHeightOrig(), scale, crop);
        if (m == null) Log.info("tela vanilla vazia -- TV de costas?");
        pendingMapping = m;
    }

    private static int alphaFbo;

    /**
     * Alfa (0..255) de uma textura do jogo, no tamanho "original" do sprite
     * (as do atlas vem recortadas: o recorte e recolocado no lugar). Le direto
     * da placa de video -- so na thread de renderizacao. Null se falhar.
     */
    static byte[] readAlpha(Texture tex) {
        int aw = tex.getWidthHW();
        int ah = tex.getHeightHW();
        int rx = Math.round(tex.getXStart() * aw);
        int ry = Math.round(tex.getYStart() * ah);
        int rw = tex.getWidth();
        int rh = tex.getHeight();
        int offX = Math.round(tex.getOffsetX());
        int offY = Math.round(tex.getOffsetY());
        int w = tex.getWidthOrig();
        int h = tex.getHeightOrig();

        ByteBuffer px = ByteBuffer.allocateDirect(rw * rh * 4);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            if (alphaFbo == 0) alphaFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, alphaFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex.getID(), 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(rx, ry, rw, rh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        } catch (Throwable t) {
            Log.error("falha ao ler o formato do sprite", t);
            return null;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        }
        byte[] alpha = new byte[w * h];
        for (int j = 0; j < rh; j++) {
            for (int i = 0; i < rw; i++) {
                int x = offX + i, y = offY + j;
                if (x < w && y < h) alpha[y * w + x] = px.get((j * rw + i) * 4 + 3);
            }
        }
        return alpha;
    }

    /**
     * Forma da tela a partir do alfa vanilla (w x h, tamanho "original").
     *
     * So pixels com alfa >= CORE_ALPHA contam (o brilho em volta da tela fica
     * de fora). Pra cada coluna acha a borda de cima e a de baixo e ajusta
     * uma reta em cada; a tela vira o quadrilatero entre a primeira e a
     * ultima coluna e essas duas retas, recuado INSET. Cada pixel da textura
     * (scale x mais fina) recebe u = posicao horizontal, v = posicao entre as
     * duas retas naquela coluna, e um alfa de cobertura nas bordas (contorno
     * liso, sem serrilhado).
     */
    static Mapping computeMapping(byte[] alpha, int w, int h, double scale, boolean crop) {
        return computeMapping(alpha, w, h, scale, crop, INSET);
    }

    /**
     * `inset` = recuo das bordas em pixels vanilla. 0 = sem recuo e sem
     * suavizar o contorno: e o que o telao usa, pra os pedacos de parede
     * vizinhos se encontrarem sem fresta.
     */
    static Mapping computeMapping(byte[] alpha, int w, int h, double scale, boolean crop, double inset) {
        int x0 = -1, x1 = -1;
        int[] top = new int[w];
        int[] bottom = new int[w];
        for (int x = 0; x < w; x++) {
            top[x] = -1;
            for (int y = 0; y < h; y++) {
                if ((alpha[y * w + x] & 0xff) >= CORE_ALPHA) {
                    if (top[x] < 0) top[x] = y;
                    bottom[x] = y;
                }
            }
            if (top[x] >= 0) {
                if (x0 < 0) x0 = x;
                x1 = x;
            }
        }
        if (x0 < 0 || x1 - x0 < 2) return null;

        // Retas pelo centro dos pixels; a borda de cima fica meio pixel acima, a de baixo meio abaixo.
        double[] tl = fitLine(top, x0, x1);
        double[] bl = fitLine(bottom, x0, x1);
        double xl = x0 + inset, xr = x1 + 1 - inset;
        java.util.function.DoubleUnaryOperator topAt = x -> tl[0] + tl[1] * (x - 0.5) + inset;
        java.util.function.DoubleUnaryOperator botAt = x -> bl[0] + bl[1] * (x - 0.5) + 1 - inset;

        // Area coberta pela textura (pixels vanilla, inteiros): so o retangulo
        // da tela, ou o sprite inteiro -- e textura scale x mais fina
        int bx0 = 0, by0 = 0, bx1 = w, by1 = h;
        if (crop) {
            bx0 = (int) Math.floor(xl);
            bx1 = (int) Math.ceil(xr);
            by0 = (int) Math.floor(Math.min(topAt.applyAsDouble(xl), topAt.applyAsDouble(xr)));
            by1 = (int) Math.ceil(Math.max(botAt.applyAsDouble(xl), botAt.applyAsDouble(xr)));
        }
        int tw = (int) Math.round((bx1 - bx0) * scale);
        int th = (int) Math.round((by1 - by0) * scale);

        List<float[]> pix = new ArrayList<>();
        for (int Y = 0; Y < th; Y++) {
            double ly = by0 + (Y + 0.5) / scale;
            for (int X = 0; X < tw; X++) {
                double lx = bx0 + (X + 0.5) / scale;
                double t = topAt.applyAsDouble(lx), b = botAt.applyAsDouble(lx);
                // Distancia (em pixels da textura) ate a borda mais proxima
                double d = Math.min(Math.min(lx - xl, xr - lx), Math.min(ly - t, b - ly)) * scale;
                double cover = inset > 0 ? Math.max(0, Math.min(1, d + 0.5)) : (d > -0.5 ? 1 : 0);
                if (cover <= 0) continue;
                pix.add(new float[] {
                    Y * tw + X,
                    (float) clamp01((lx - xl) / (xr - xl)),
                    (float) clamp01((ly - t) / (b - t)),
                    (float) cover,
                });
            }
        }
        int n = pix.size();
        int[] dst = new int[n];
        float[] u = new float[n];
        float[] v = new float[n];
        byte[] a = new byte[n];
        for (int k = 0; k < n; k++) {
            float[] p = pix.get(k);
            dst[k] = (int) p[0];
            u[k] = p[1];
            v[k] = p[2];
            a[k] = (byte) Math.round(p[3] * 255);
        }
        double avgH = (botAt.applyAsDouble((xl + xr) / 2) - topAt.applyAsDouble((xl + xr) / 2));
        return new Mapping(tw, th, bx0, by0, w, h, dst, u, v, a,
                (float) (1 / ((xr - xl) * scale)), (float) (1 / (Math.max(1, avgH) * scale)));
    }

    /** Reta y = a + b*x pelas colunas [x0, x1] com tela (ignora 2 em cada ponta, que costumam ser canto arredondado). */
    private static double[] fitLine(int[] ys, int x0, int x1) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = 0;
        int from = x1 - x0 > 6 ? x0 + 2 : x0;
        int to = x1 - x0 > 6 ? x1 - 2 : x1;
        for (int x = from; x <= to; x++) {
            if (ys[x] < 0) continue;
            sx += x; sy += ys[x]; sxx += (double) x * x; sxy += (double) x * ys[x];
            n++;
        }
        if (n < 2) return new double[] {n == 1 ? sy : 0, 0};
        double den = n * sxx - sx * sx;
        double b = den == 0 ? 0 : (n * sxy - sx * sy) / den;
        return new double[] {(sy - b * sx) / n, b};
    }

    private static double clamp01(double d) {
        return d < 0 ? 0 : (d > 1 ? 1 : d);
    }

    /**
     * Monta e envia a imagem da tela a partir do quadro do navegador (BGRA).
     * Chamado pelo GlBridge logo depois de enviar o quadro, na thread de
     * renderizacao, enquanto o buffer do quadro ainda e valido.
     */
    @Override
    public void renderFrame(FrameSource.Frame f) {
        Mapping m = mapping;
        Texture t = texture;
        ByteBuffer o = out;
        if (m == null || t == null || o == null || t.getID() <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRenderMs < 1000 / SCREEN_FPS) return;
        lastRenderMs = now;

        // So o retangulo do <video>, quando ele e grande o bastante; senao a pagina toda
        double sx = 0, sy = 0, sw = f.width, sh = f.height;
        int[] r = helper.getMediaRect();
        if (r != null && r[2] * r[3] >= MIN_VIDEO_AREA) {
            sx = Math.max(0, r[0]);
            sy = Math.max(0, r[1]);
            sw = Math.min(f.width - sx, r[2]);
            sh = Math.min(f.height - sy, r[3]);
        }

        sample(m, f.pixels, f.width, f.height, sx, sy, sw, sh, o);

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getID());
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, m.width(), m.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, o);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        dirty = true;
    }

    /**
     * Preenche `out` (RGBA, tamanho da textura) com o retangulo (sx,sy,sw,sh)
     * do quadro `src` (BGRA, frameW x frameH), cada pixel sendo a media de
     * SAMPLES x SAMPLES pontos da area do video que cai nele. Fora da tela
     * `out` nao e tocado -- fica sempre zerado (transparente).
     */
    static void sample(Mapping m, ByteBuffer src, int frameW, int frameH,
                       double sx, double sy, double sw, double sh, ByteBuffer out) {
        sample(m, src, frameW, frameH, sx, sy, sw, sh, out, m.width(), 0, 0, SAMPLES);
    }

    /**
     * Igual ao de cima, mas escrevendo num atlas: o pixel (X,Y) da tela vai
     * pra (ox+X, oy+Y) de um buffer com `outW` pixels por linha, com
     * `samples` x `samples` amostras por pixel.
     */
    static void sample(Mapping m, ByteBuffer src, int frameW, int frameH,
                       double sx, double sy, double sw, double sh,
                       ByteBuffer out, int outW, int ox, int oy, int samples) {
        sample(m, src, frameW, frameH, sx, sy, sw, sh, out, outW, ox, oy, samples,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * Igual ao de cima, com uma area valida (clipX0..clipX1, clipY0..clipY1,
     * em pixels do quadro): o que cair fora dela sai PRETO -- sao as faixas
     * pretas quando o video mantem a proporcao numa tela de formato diferente.
     */
    static void sample(Mapping m, ByteBuffer src, int frameW, int frameH,
                       double sx, double sy, double sw, double sh,
                       ByteBuffer out, int outW, int ox, int oy, int samples,
                       double clipX0, double clipY0, double clipX1, double clipY1) {
        int stride = frameW * 4;
        int maxX = frameW - 1, maxY = frameH - 1;
        double fw = m.du() * sw, fh = m.dv() * sh; // area do video que cai num pixel da tela
        int count = samples * samples;
        for (int k = 0; k < m.dst().length; k++) {
            double cx = sx + m.u()[k] * sw, cy = sy + m.v()[k] * sh;
            if (cx < clipX0 || cx > clipX1 || cy < clipY0 || cy > clipY1) {
                int d = m.dst()[k];
                int o = ((oy + d / m.width()) * outW + ox + d % m.width()) * 4;
                out.put(o, (byte) 0).put(o + 1, (byte) 0).put(o + 2, (byte) 0).put(o + 3, m.alpha()[k]);
                continue;
            }
            int rs = 0, gs = 0, bs = 0;
            for (int j = 0; j < samples; j++) {
                int py = (int) (cy + fh * ((j + 0.5) / samples - 0.5));
                py = py < 0 ? 0 : (py > maxY ? maxY : py);
                for (int i = 0; i < samples; i++) {
                    int pxx = (int) (cx + fw * ((i + 0.5) / samples - 0.5));
                    pxx = pxx < 0 ? 0 : (pxx > maxX ? maxX : pxx);
                    int o = py * stride + pxx * 4;
                    bs += src.get(o) & 0xff;
                    gs += src.get(o + 1) & 0xff;
                    rs += src.get(o + 2) & 0xff;
                }
            }
            int d = m.dst()[k];
            int o = ((oy + d / m.width()) * outW + ox + d % m.width()) * 4;
            out.put(o, (byte) (rs / count));
            out.put(o + 1, (byte) (gs / count));
            out.put(o + 2, (byte) (bs / count));
            out.put(o + 3, m.alpha()[k]);
        }
    }
}
