package zomboidtv;

import java.util.ArrayList;
import java.util.List;

import zombie.core.Color;
import zombie.core.Core;
import zombie.input.GameKeyboard;
import zombie.ui.UINineGrid;
import zombie.ui.UITextEntryInterface;

/**
 * Faz o navegador se passar por uma "caixa de texto" do jogo.
 *
 * O jogo entrega o teclado pra quem estiver em Core.currentTextEntryBox
 * (Core.updateKeyboardAux) e, enquanto ela estiver ativa, desliga os atalhos
 * do jogo -- e o mesmo mecanismo do chat. Implementando a interface, o
 * navegador recebe teclas especiais e caracteres ja traduzidos pelo layout do
 * teclado (acentos, cedilha, maiusculas) sem nenhum patch no jogo.
 *
 * Esc devolve o teclado pro jogo (alem de ir pra pagina, onde sai da tela cheia).
 */
final class BrowserKeyboard implements UITextEntryInterface {
    // Codigos de tecla do jogo (DirectInput/LWJGL 2)
    private static final int KEY_ESCAPE = 1;
    private static final int KEY_TAB = 15;
    private static final int KEY_LCONTROL = 29, KEY_RCONTROL = 157;
    private static final int KEY_LSHIFT = 42, KEY_RSHIFT = 54;
    private static final int KEY_LMENU = 56, KEY_RMENU = 184;

    private static final Color FRAME_COLOUR = new Color(1f, 1f, 1f, 1f);

    private final BrowserHelper helper;
    private boolean doingTextEntry;

    BrowserKeyboard(BrowserHelper helper) {
        this.helper = helper;
    }

    /** Pega ou devolve o teclado. */
    void setFocused(boolean focused) {
        if (focused) {
            if (Core.currentTextEntryBox != null && Core.currentTextEntryBox != this) {
                Core.UnfocusActiveTextEntryBox();
            }
            Core.currentTextEntryBox = this;
            doingTextEntry = true;
            helper.send("{\"cmd\":\"focus\",\"value\":true}");
        } else if (Core.currentTextEntryBox == this) {
            doingTextEntry = false;
            Core.currentTextEntryBox = null;
        }
    }

    boolean isFocused() {
        return Core.currentTextEntryBox == this && doingTextEntry;
    }

    // --- Envio pro motor ---------------------------------------------------

    private void key(String name) {
        key(name, null);
    }

    private void key(String name, String ch) {
        helper.markUserInput();
        StringBuilder b = new StringBuilder("{\"cmd\":\"key\"");
        if (name != null) b.append(",\"key\":").append(Json.str(name));
        if (ch != null) b.append(",\"char\":").append(Json.str(ch));
        b.append(",\"modifiers\":").append(modifiers()).append('}');
        helper.send(b.toString());
    }

    private void edit(String action) {
        helper.markUserInput();
        helper.send("{\"cmd\":\"edit\",\"action\":" + Json.str(action) + "}");
    }

    /** Modificadores segurados agora (Shift+setas seleciona texto, etc.). */
    private static String modifiers() {
        List<String> m = new ArrayList<>(3);
        if (GameKeyboard.isKeyDownRaw(KEY_LSHIFT) || GameKeyboard.isKeyDownRaw(KEY_RSHIFT)) m.add("\"shift\"");
        if (GameKeyboard.isKeyDownRaw(KEY_LCONTROL) || GameKeyboard.isKeyDownRaw(KEY_RCONTROL)) m.add("\"control\"");
        if (GameKeyboard.isKeyDownRaw(KEY_LMENU) || GameKeyboard.isKeyDownRaw(KEY_RMENU)) m.add("\"alt\"");
        return "[" + String.join(",", m) + "]";
    }

    // --- UITextEntryInterface ------------------------------------------------

    @Override public boolean isDoingTextEntry() { return doingTextEntry; }
    @Override public void setDoingTextEntry(boolean b) { doingTextEntry = b; }
    @Override public String getUIName() { return "KNOXTV"; }
    @Override public boolean isEditable() { return true; }
    @Override public UINineGrid getFrame() { return null; }
    @Override public boolean isIgnoreFirst() { return false; }
    @Override public void setIgnoreFirst(boolean b) { }
    @Override public void setSelectingRange(boolean b) { }
    @Override public Color getStandardFrameColour() { return FRAME_COLOUR; }
    @Override public boolean isTextLimit() { return false; }
    @Override public boolean isOnlyNumbers() { return false; }
    @Override public boolean isOnlyText() { return false; }

    // Enter precisa do char "\r" alem do keyDown, senao formularios (busca do
    // YouTube) nao sao enviados.
    @Override public void onKeyEnter() { key("Enter", "\r"); }
    @Override public void onKeyHome() { key("Home"); }
    @Override public void onKeyEnd() { key("End"); }
    @Override public void onKeyUp() { key("Up"); }
    @Override public void onKeyDown() { key("Down"); }
    @Override public void onKeyLeft() { key("Left"); }
    @Override public void onKeyRight() { key("Right"); }
    @Override public void onKeyDelete() { key("Delete"); }
    @Override public void onKeyBack() { key("Backspace"); }
    @Override public void pasteFromClipboard() { edit("paste"); }
    @Override public void copyToClipboard() { edit("copy"); }
    @Override public void cutToClipboard() { edit("cut"); }
    @Override public void selectAll() { edit("selectAll"); }

    @Override
    public void onOtherKey(int keyCode) {
        if (keyCode == KEY_ESCAPE) {
            key("Escape");
            setFocused(false);
        } else if (keyCode == KEY_TAB) {
            key("Tab");
        }
    }

    @Override
    public void putCharacter(char c) {
        if (c < 0x20) return; // controles ja tratados acima
        String s = String.valueOf(c);
        // Letras/numeros/espaco tambem vao como tecla (keyDown), pros atalhos
        // da pagina funcionarem (espaco/k pausa, f tela cheia, j/l pula 10s).
        // O resto (acentos, simbolos) vai so como texto.
        boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ';
        key(plain ? (c == ' ' ? "Space" : s) : null, s);
    }
}
