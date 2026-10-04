package zomboidtv;

/** Log simples com prefixo, aparece no console.txt do jogo. */
final class Log {
    private Log() {}

    static void info(String msg) {
        System.out.println("[KNOX TV] " + msg);
    }

    static void error(String msg, Throwable t) {
        System.out.println("[KNOX TV] ERRO: " + msg + (t != null ? " -- " + t : ""));
        if (t != null) t.printStackTrace(System.out);
    }
}
