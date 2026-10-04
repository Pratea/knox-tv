package zomboidtv;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Le os quadros que o motor escreve no arquivo de memoria compartilhada.
 *
 * Usa tres buffers pra nenhum lado esperar o outro:
 *   back   -- a thread de leitura copia o quadro novo aqui
 *   ready  -- ultimo quadro completo, esperando ir pra placa de video
 *   front  -- o que a thread de renderizacao esta enviando agora
 * A troca e so de referencias, dentro de um lock curtinho.
 *
 * O layout do arquivo esta documentado em helper/main.js.
 */
final class FrameSource {
    static final int HEADER_SIZE = 64;
    static final int MAX_WIDTH = 1920;
    static final int MAX_HEIGHT = 1080;
    static final long FILE_SIZE = HEADER_SIZE + (long) MAX_WIDTH * MAX_HEIGHT * 4;

    static final class Frame {
        final ByteBuffer pixels = ByteBuffer.allocateDirect(MAX_WIDTH * MAX_HEIGHT * 4);
        int width;
        int height;
    }

    private final File file;
    private MappedByteBuffer map;
    private Frame back = new Frame();
    private Frame ready = new Frame();
    private Frame front = new Frame();
    private boolean hasReady;
    private volatile boolean running;
    private volatile long framesRead;

    FrameSource(File file) {
        this.file = file;
    }

    /**
     * Cria o arquivo no tamanho final ANTES de iniciar o motor: no Windows
     * nao da pra redimensionar um arquivo mapeado, entao os dois lados
     * precisam concordar no tamanho desde o comeco.
     */
    void open() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            if (raf.length() != FILE_SIZE) raf.setLength(FILE_SIZE);
            map = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE);
        }
        map.order(ByteOrder.LITTLE_ENDIAN);
        running = true;
        Thread t = new Thread(this::pollLoop, "KNOXTV-frames");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        running = false;
    }

    long getFramesRead() {
        return framesRead;
    }

    /** Pega o quadro mais novo pra enviar; null se nao chegou nada desde o ultimo. */
    synchronized Frame takeReady() {
        if (!hasReady) return null;
        Frame t = front;
        front = ready;
        ready = t;
        hasReady = false;
        return front;
    }

    private synchronized void publish() {
        Frame t = ready;
        ready = back;
        back = t;
        hasReady = true;
    }

    private void pollLoop() {
        int lastSeq = 0;
        while (running) {
            try {
                int seq = map.getInt(4);
                if (seq != lastSeq && (seq & 1) == 0 && copyFrame(seq)) {
                    lastSeq = seq;
                    framesRead++;
                    publish();
                } else {
                    Thread.sleep(3);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.error("leitura de quadro", e);
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** Copia o quadro pro buffer de tras; falso se o motor escreveu no meio (seqlock). */
    private boolean copyFrame(int seq) {
        int w = map.getInt(8);
        int h = map.getInt(12);
        if (w <= 0 || h <= 0 || w > MAX_WIDTH || h > MAX_HEIGHT) return false;
        int len = w * h * 4;
        ByteBuffer src = map.duplicate();
        src.position(HEADER_SIZE).limit(HEADER_SIZE + len);
        back.pixels.clear();
        back.pixels.put(src);
        back.pixels.flip();
        back.width = w;
        back.height = h;
        return map.getInt(4) == seq;
    }
}
