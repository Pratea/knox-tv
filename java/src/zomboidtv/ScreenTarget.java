package zomboidtv;

/**
 * Lugar do mundo que mostra o video: a tela de uma TV (ScreenRenderer) ou o
 * telao de um drive-in (WallScreenRenderer). So um fica ativo por vez -- o
 * da sessao do navegador.
 */
interface ScreenTarget {
    /** "x,y,z" da TV ou da ancora do telao, ou null se nao esta mostrando nada. */
    String getId();

    /** Thread de renderizacao: recebe cada quadro novo do navegador. */
    void renderFrame(FrameSource.Frame f);

    /** Thread do jogo, todo quadro: reafirma as camadas e invalida o cache de desenho. */
    void gameTick();

    /** Devolve o visual original. */
    void detach();
}
