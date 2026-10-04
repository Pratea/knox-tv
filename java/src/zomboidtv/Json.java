package zomboidtv;

/**
 * JSON minimo pra conversar com o motor. Os dois lados sao nossos e as
 * mensagens sao planas (sem objetos aninhados), entao nao vale puxar uma
 * biblioteca inteira pra dentro do .jar.
 */
final class Json {
    private Json() {}

    static String str(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** Valor de um campo numerico de nivel superior, ou {@code def} se ausente. */
    static double num(String json, String name, double def) {
        String key = "\"" + name + "\":";
        int i = json.indexOf(key);
        if (i < 0) return def;
        int start = i + key.length();
        int end = start;
        while (end < json.length() && "-+.0123456789eE".indexOf(json.charAt(end)) >= 0) end++;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Campo booleano de nivel superior (ausente = false). */
    static boolean bool(String json, String name) {
        return json.contains("\"" + name + "\":true");
    }

    /** Valor de um campo string de nivel superior, ou "" se ausente. */
    static String field(String json, String name) {
        String key = "\"" + name + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return "";
        StringBuilder b = new StringBuilder();
        for (int j = i + key.length(); j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '"') break;
            if (c == '\\' && j + 1 < json.length()) {
                char n = json.charAt(++j);
                switch (n) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (j + 4 < json.length()) {
                            b.append((char) Integer.parseInt(json.substring(j + 1, j + 5), 16));
                            j += 4;
                        }
                    }
                    default -> b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
