/**
 * Utilitários de JSON sem dependências externas.
 * Centraliza o escape de texto e a leitura de campos simples de respostas de APIs,
 * para que Brapi, AwesomeAPI e Gemini usem exatamente a mesma lógica.
 */
public final class JsonUtil {

    private JsonUtil() {}

    /** Escapa um texto para ser colocado dentro de uma string JSON ("..."). */
    public static String escapar(String texto) {
        if (texto == null) return "";
        StringBuilder sb = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    // Qualquer outro caractere de controle também invalida o JSON se não for escapado
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Lê o valor numérico de "chave": 12.34. Retorna {@code padrao} se não existir ou for null. */
    public static double extrairNumero(String json, String chave, double padrao) {
        int i = posicaoDoValor(json, chave);
        if (i == -1) return padrao;
        int fim = i;
        while (fim < json.length() && "+-0123456789.eE".indexOf(json.charAt(fim)) >= 0) fim++;
        if (fim == i) return padrao; // era null, texto, etc.
        try {
            return Double.parseDouble(json.substring(i, fim));
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    /** Lê o valor textual de "chave": "texto", já decodificando quebras de linha, aspas, barras e escapes unicode. Retorna null se não achar. */
    public static String extrairTexto(String json, String chave) {
        int i = posicaoDoValor(json, chave);
        if (i == -1 || i >= json.length() || json.charAt(i) != '"') return null;

        StringBuilder sb = new StringBuilder();
        for (i = i + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return sb.toString();   // fim da string
            if (c != '\\') { sb.append(c); continue; }

            if (++i >= json.length()) break;
            char e = json.charAt(i);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    // Escape unicode de 4 dígitos hex (emoji vêm como dois seguidos; anexar char a char reconstrói o par)
                    if (i + 4 < json.length()) {
                        try {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        } catch (NumberFormatException ex) {
                            sb.append('?');
                        }
                        i += 4;
                    }
                    break;
                default: sb.append(e); // \" \\ \/
            }
        }
        return null; // string não terminada
    }

    /** Posição logo após "chave": (pulando espaços), ou -1. Aceita JSON compacto e formatado. */
    private static int posicaoDoValor(String json, String chave) {
        if (json == null) return -1;
        String alvo = "\"" + chave + "\"";
        int idx = json.indexOf(alvo);
        while (idx != -1) {
            int i = idx + alvo.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                return i;
            }
            idx = json.indexOf(alvo, idx + alvo.length());
        }
        return -1;
    }
}
