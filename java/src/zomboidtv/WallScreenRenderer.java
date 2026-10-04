package zomboidtv;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Telao de drive-in: uma parede de varios tiles (ex: Rosewood, 19 x 4 andares
 * de `walls_interior_house_03_20`) mostrando o video, cada pedaco com a sua
 * parte da imagem.
 *
 * Mesma ideia da tela da TV (ScreenRenderer), generalizada:
 *   - o formato da face da parede vem do alfa do proprio sprite e vira um
 *     mapeamento (borda de cima/baixo por coluna) -- igual pra todos os tiles;
 *   - cada tile ganha uma camada (`overlaySprite`) com textura propria, mas
 *     todas sao pedacos de UM atlas: um envio por quadro, nao um por tile;
 *   - cada pedaco usa o MESMO formato da tela da TV, o unico que funciona
 *     nas duas visoes: 1x, do tamanho do sprite inteiro, sem recorte.
 *     (Testado: meia resolucao "fingindo" 1x quebra o Viewpoint, que le pelo
 *     tamanho declarado; recortado na face quebra a camera isometrica, que
 *     desalinha a textura de profundidade.) Como a face ocupa so uma faixa
 *     do sprite, a cada quadro so o retangulo da face de cada pedaco vai pra
 *     placa de video. Em 1x o telao tem ~1200 px de largura -- quase o video
 *     inteiro --, entao 1 amostra por pixel basta;
 *   - atualiza a SCREEN_FPS, pra nao pesar;
 *   - o tile na coluna c / linha r mostra o pedaco (c/N .. (c+1)/N, r/M ..)
 *     do video, entao a imagem continua de um tile pro outro.
 *
 * So os tiles nas coordenadas exatas do telao e com o sprite certo entram --
 * a mesma parede usada em casas nao e afetada. Troca so local (sem rede).
 */
final class WallScreenRenderer implements ScreenTarget {
    private static final long DIRTY_FLAGS = 256L;
    /** Amostras por pixel: em 1x o telao tem quase a largura do video, nao precisa reduzir. */
    private static final int SAMPLES = 1;
    private static final int SCREEN_FPS = 15;
    private static final int ATLAS_COLUMNS = 16;
    private static final int MIN_VIDEO_AREA = 200 * 120;

    /** Um pedaco do telao: o objeto parede e a posicao dele na grade do video. */
    private static final class Tile {
        final IsoObject obj;
        final int col, row;
        IsoSprite savedOverlay;
        IsoSprite sprite;
        int atlasX, atlasY;

        Tile(IsoObject obj, int col, int row) {
            this.obj = obj;
            this.col = col;
            this.row = row;
        }
    }

    private final BrowserHelper helper;
    private final List<Tile> tiles = new ArrayList<>();
    private String id;
    private int columns, rows;
    private Texture atlas;
    private ByteBuffer out;
    private int atlasW, atlasH;
    private volatile ScreenRenderer.Mapping pendingMapping;
    private volatile ScreenRenderer.Mapping mapping;
    private volatile boolean dirty;
    private volatile int[] faceBox;
    private long lastRenderMs;

    WallScreenRenderer(BrowserHelper helper) {
        this.helper = helper;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Liga o telao formado pelas paredes `spriteName` na faixa dada.
     * axis "y": a parede corre ao longo de y (x fixo); "x": ao longo de x.
     */
    boolean attach(int x0, int x1, int y0, int y1, int z0, int z1, String spriteName, String axis) {
        String newId = x0 + "," + y0 + "," + z0;
        if (newId.equals(id)) return true;
        detach();

        IsoCell cell = IsoWorld.instance.getCell();
        boolean alongY = "y".equals(axis);
        int length = alongY ? (y1 - y0 + 1) : (x1 - x0 + 1);
        Texture wallTex = null;
        for (int z = z0; z <= z1; z++) {
            for (int i = 0; i < length; i++) {
                int x = alongY ? x0 : x0 + i;
                int y = alongY ? y0 + i : y0;
                IsoObject wall = findWall(cell.getGridSquare(x, y, z), spriteName);
                if (wall == null) continue; // pedaco demolido/nao carregado: fica sem video
                // Esquerda da tela: no eixo y, y maior fica mais a esquerda na camera isometrica
                int col = alongY ? (y1 - y) : (x - x0);
                tiles.add(new Tile(wall, col, z1 - z));
                if (wallTex == null) wallTex = wall.getSprite().getTextureForCurrentFrame(wall.getDir());
            }
        }
        if (tiles.isEmpty() || wallTex == null) {
            Log.info("telao: nenhuma parede '" + spriteName + "' carregada em " + newId);
            tiles.clear();
            return false;
        }
        id = newId;
        columns = length;
        rows = z1 - z0 + 1;
        Texture tex = wallTex;
        RenderThread.queueInvokeOnRenderContext(() -> {
            byte[] alpha = ScreenRenderer.readAlpha(tex);
            if (alpha != null) pendingMapping = ScreenRenderer.computeMapping(alpha, tex.getWidthOrig(), tex.getHeightOrig(), 1, false, 0);
        });
        Log.info("telao " + newId + ": " + tiles.size() + " pedacos (" + columns + " x " + rows + ")");
        return true;
    }

    private static IsoObject findWall(IsoGridSquare sq, String spriteName) {
        if (sq == null) return null;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject o = sq.getObjects().get(i);
            if (o.getSprite() != null && spriteName.equals(o.getSprite().getName())) return o;
        }
        return null;
    }

    /** Thread do jogo, 1a etapa: cria o atlas quando o formato da parede fica pronto. */
    private void createAtlas(ScreenRenderer.Mapping m) {
        int atlasCols = Math.min(ATLAS_COLUMNS, tiles.size());
        int atlasRows = (tiles.size() + atlasCols - 1) / atlasCols;
        atlasW = atlasCols * m.width();
        atlasH = atlasRows * m.height();
        atlas = new Texture(atlasW, atlasH, 0);
        out = ByteBuffer.allocateDirect(atlasW * atlasH * 4);
    }

    /**
     * Thread do jogo, 2a etapa (atlas ja criado na placa de video): uma
     * camada por tile, cada uma um pedaco do atlas. So depois do atlas pronto
     * porque o jogo recalcula o tamanho dos pedacos quando o atlas fica
     * pronto -- e desfaria o tamanho "de sprite" ajustado aqui.
     */
    private void createLayers(ScreenRenderer.Mapping m) throws ReflectiveOperationException {
        int cw = m.width(), ch = m.height();
        int atlasCols = Math.min(ATLAS_COLUMNS, tiles.size());
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            t.atlasX = (i % atlasCols) * cw;
            t.atlasY = (i / atlasCols) * ch;
            // Pedaco do atlas do tamanho do sprite inteiro (cw x ch = sprite original)
            Texture sub = new Texture(atlas.getTextureId(), "ZomboidTV_telao_" + i, t.atlasX, t.atlasY, cw, ch);
            t.sprite = IsoSprite.CreateSprite(IsoSpriteManager.instance);
            t.sprite.name = "ZomboidTV_telao";
            t.sprite.texture = sub;
            t.sprite.depthTexture = t.obj.getSprite().depthTexture;
            t.savedOverlay = t.obj.overlaySprite;
            t.obj.overlaySprite = t.sprite;
        }
        // faceBox antes de mapping: renderFrame so comeca quando mapping existe
        faceBox = faceBox(m);
        mapping = m;
        dirty = true;
        Log.info("telao: atlas " + atlasW + "x" + atlasH + " (" + tiles.size() + " pedacos de " + cw + "x" + ch
                + ", face " + faceBox[2] + "x" + faceBox[3] + ")");
    }

    /**
     * Na arte isometrica (2:1) a face da parede aparece com a largura "deitada":
     * o comprimento real de 1 tile e a largura na tela x sqrt(1 + 0.5^2).
     */
    private static final double ISO_EDGE = Math.sqrt(1.25);

    /**
     * Proporcao (largura/altura) real do telao: N tiles de largura x M andares,
     * medidos na propria face da parede (largura e altura de um pedaco).
     * Drive-in de Rosewood (19 x 4) da ~1.75, quase 16:9; uma sala de cinema
     * de 7 x 1 da ~2.5, formato de tela de cinema.
     */
    private double screenAspect(ScreenRenderer.Mapping m) {
        double faceW = 1 / m.du(), faceH = 1 / m.dv();
        return columns * faceW * ISO_EDGE / (rows * faceH);
    }

    /** {x, y, largura, altura} da area da face dentro de um pedaco. */
    private static int[] faceBox(ScreenRenderer.Mapping m) {
        int x0 = m.width(), y0 = m.height(), x1 = -1, y1 = -1;
        for (int d : m.dst()) {
            int x = d % m.width(), y = d / m.width();
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (y < y0) y0 = y;
            if (y > y1) y1 = y;
        }
        return new int[] {x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    }

    private static void setIntField(Texture t, String name, int value) throws ReflectiveOperationException {
        Field f = Texture.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(t, value);
    }

    @Override
    public void gameTick() {
        if (id == null) return;
        ScreenRenderer.Mapping pm = pendingMapping;
        if (pm != null && atlas == null) createAtlas(pm);
        if (pm != null && mapping == null && atlas != null && atlas.getID() > 0) {
            try {
                createLayers(pm);
            } catch (ReflectiveOperationException e) {
                Log.error("telao: nao foi possivel criar as camadas", e);
                detach();
                return;
            }
        }
        if (mapping == null) return;
        boolean wasDirty = dirty;
        dirty = false;
        for (Tile t : tiles) {
            if (t.obj.getSquare() == null) continue; // parede removida
            if (t.obj.overlaySprite != t.sprite) {
                t.obj.overlaySprite = t.sprite;
                wasDirty = true;
            }
            if (wasDirty) t.obj.invalidateRenderChunkLevel(DIRTY_FLAGS);
        }
    }

    @Override
    public void detach() {
        if (id == null) return;
        for (Tile t : tiles) {
            try {
                if (t.sprite != null && t.obj.overlaySprite == t.sprite) t.obj.overlaySprite = t.savedOverlay;
                if (t.obj.getSquare() != null) t.obj.invalidateRenderChunkLevel(DIRTY_FLAGS);
            } catch (Throwable e) {
                // partida ja descarregada: nada a restaurar
            }
        }
        tiles.clear();
        Texture a = atlas;
        if (a != null) RenderThread.queueInvokeOnRenderContext(() -> a.getTextureId().destroy());
        atlas = null;
        out = null;
        mapping = null;
        faceBox = null;
        pendingMapping = null;
        id = null;
    }

    @Override
    public void renderFrame(FrameSource.Frame f) {
        ScreenRenderer.Mapping m = mapping;
        Texture a = atlas;
        ByteBuffer o = out;
        int[] fb = faceBox;
        if (m == null || a == null || o == null || fb == null || a.getID() <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRenderMs < 1000 / SCREEN_FPS) return;
        lastRenderMs = now;

        double sx = 0, sy = 0, sw = f.width, sh = f.height;
        int[] r = helper.getMediaRect();
        if (r != null && r[2] * r[3] >= MIN_VIDEO_AREA) {
            sx = Math.max(0, r[0]);
            sy = Math.max(0, r[1]);
            sw = Math.min(f.width - sx, r[2]);
            sh = Math.min(f.height - sy, r[3]);
        }
        // Mantem a proporcao do video: a tela "ve" um retangulo virtual do
        // formato dela, centrado no video; o que sobra vira faixa preta.
        double screenAspect = screenAspect(m);
        double videoAspect = sw / sh;
        double vw = sw, vh = sh, vx = sx, vy = sy;
        if (videoAspect < screenAspect) {      // tela mais larga: faixas nas laterais
            vw = sh * screenAspect;
            vx = sx - (vw - sw) / 2;
        } else {                               // tela mais alta: faixas em cima/embaixo
            vh = sw / screenAspect;
            vy = sy - (vh - sh) / 2;
        }
        double tw = vw / columns, th = vh / rows;
        for (Tile t : tiles) {
            ScreenRenderer.sample(m, f.pixels, f.width, f.height,
                    vx + t.col * tw, vy + t.row * th, tw, th, o, atlasW, t.atlasX, t.atlasY, SAMPLES,
                    sx, sy, sx + sw, sy + sh);
        }

        // So o retangulo da face de cada pedaco (o resto do sprite e sempre transparente)
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.getID());
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, atlasW);
        try {
            for (Tile t : tiles) {
                int x = t.atlasX + fb[0], y = t.atlasY + fb[1];
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, x);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, y);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, fb[2], fb[3], GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, o);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
        dirty = true;
    }
}
