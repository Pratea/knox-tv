package zomboidtv;

/**
 * Ponto de entrada do mod Java, chamado pelo ZombieBuddy ao carregar o .jar.
 *
 * Nada pesado acontece aqui: o motor de navegador so e iniciado quando o Lua
 * pede (ZTV_start), pra nao gastar memoria em quem nunca liga uma TV.
 */
public class Main {
    public static final String VERSION = "0.7.0";

    public static void main(String[] args) {
        Log.info("KNOX TV " + VERSION + " carregado (cliente).");
        Runtime.getRuntime().addShutdownHook(new Thread(ZtvLua::shutdown, "KNOXTV-shutdown"));
    }
}
