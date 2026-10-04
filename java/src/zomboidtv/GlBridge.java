package zomboidtv;

import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;

/**
 * Leva os quadros do navegador pra placa de video.
 *
 * Toda chamada OpenGL precisa rodar na thread de renderizacao do jogo, entao
 * o envio e enfileirado com RenderThread.queueInvokeOnRenderContext (a mesma
 * fila que o jogo usa pra criar a propria textura -- por isso o envio sempre
 * roda depois da criacao). No maximo um envio fica na fila por vez.
 *
 * Cada quadro vai pra textura do navegador (desenhada na janela "Assistir")
 * e, se houver, pra tela da TV do mundo (ScreenRenderer).
 */
final class GlBridge {
    private final FrameSource frames;
    private final Texture texture;
    private final AtomicBoolean uploadQueued = new AtomicBoolean();
    private volatile ScreenTarget screen;
    private boolean screenFailed;

    GlBridge(FrameSource frames, int width, int height) {
        this.frames = frames;
        this.texture = new Texture(width, height, 0);
    }

    Texture getTexture() {
        return texture;
    }

    /** Tela do mundo (TV ou telao) que recebe cada quadro novo; null = nenhuma. */
    void setScreen(ScreenTarget screen) {
        this.screen = screen;
    }

    /** Chamado todo quadro pelo Lua (thread do jogo): agenda um envio se chegou quadro novo. */
    void update() {
        if (uploadQueued.compareAndSet(false, true)) {
            RenderThread.queueInvokeOnRenderContext(this::uploadOnRenderThread);
        }
    }

    private void uploadOnRenderThread() {
        uploadQueued.set(false);
        FrameSource.Frame f = frames.takeReady();
        int texId = texture.getID();
        if (f == null || texId <= 0) return;
        int w = Math.min(f.width, texture.getWidth());
        int h = Math.min(f.height, texture.getHeight());

        // Salva e restaura o estado que o jogo acha que esta ativo, pra nao
        // bagunçar o que ele desenha em seguida.
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, f.width);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, f.pixels);
        GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);

        ScreenTarget s = screen;
        if (s != null && !screenFailed) {
            try {
                s.renderFrame(f);
            } catch (Throwable t) {
                // Nao derruba o jogo nem fica repetindo o erro todo quadro
                screenFailed = true;
                Log.error("falha ao desenhar na tela da TV -- desativado ate reiniciar", t);
            }
        }
    }
}
