import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;

/**
 * Cliente HTTP responsável pela integração com a Google Gemini API no FinSight.
 * Implementa um sistema de Fallback que alterna automaticamente entre modelos de IA
 * caso o primeiro falhe (modelo aposentado, sobrecarga 503, timeout...).
 */
public class GeminiApiClient {

    // Modelos em ordem de prioridade. Podem ser trocados SEM recompilar, pela variável de
    // ambiente GEMINI_MODELS (separados por vírgula), pois a Google aposenta modelos com frequência.
    private static final String[] MODELOS_PADRAO = {
            "gemini-3.8-flash",
            "gemini-3.6-flash"
    };

    private static final String URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Tempo máximo (segundos) que o Java aguarda a RESPOSTA de cada modelo antes de saltar para o próximo.
    // Em servidores pequenos (ex.: plano gratuito do Render) 12s é pouco; ajuste com GEMINI_TIMEOUT_SEGUNDOS.
    private static final int TIMEOUT_SEGUNDOS = lerTimeout();
    // Tempo máximo só para ESTABELECER a conexão com a Google (se estourar, o problema é de rede, não do modelo)
    private static final int TIMEOUT_CONEXAO_SEGUNDOS = 10;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_CONEXAO_SEGUNDOS))
            .build();

    private static int lerTimeout() {
        String env = System.getenv("GEMINI_TIMEOUT_SEGUNDOS");
        if (env != null && !env.isBlank()) {
            try {
                int v = Integer.parseInt(env.trim());
                if (v >= 5 && v <= 60) return v;
            } catch (NumberFormatException ignorado) {
                // usa o padrão
            }
        }
        return 30;
    }

    private static String[] modelos() {
        String env = System.getenv("GEMINI_MODELS");
        if (env == null || env.isBlank()) return MODELOS_PADRAO;
        String[] lista = Arrays.stream(env.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        return lista.length > 0 ? lista : MODELOS_PADRAO;
    }

    /**
     * Processa o prompt percorrendo os modelos disponíveis.
     *
     * @throws IllegalStateException se GEMINI_API_KEY não estiver configurada
     * @throws RuntimeException      se TODOS os modelos falharem (a mensagem lista o motivo de cada um)
     */
    public String gerarAnaliseMercado(String prompt) throws Exception {
        // Reaproveita a validação que já existia em GerenciadorChave (antes a classe não era usada)
        String apiKey = GerenciadorChave.obterChave();

        // JsonUtil.escapar trata aspas, barras, quebras de linha e demais caracteres de controle
        String payload = "{\"contents\":[{\"parts\":[{\"text\":\"" + JsonUtil.escapar(prompt) + "\"}]}]}";

        StringBuilder falhas = new StringBuilder();
        for (String modelo : modelos()) {
            try {
                System.out.println("🤖 [FinSight IA] Tentando o modelo: " + modelo);
                long inicio = System.currentTimeMillis();
                String resposta = executarChamadaModelo(modelo, apiKey, payload);
                System.out.println("✅ [FinSight IA] Sucesso com o modelo: " + modelo
                        + " em " + (System.currentTimeMillis() - inicio) + " ms");
                return resposta;
            } catch (Exception e) {
                System.err.println("⚠️ [FALLBACK] O modelo '" + modelo + "' falhou (" + e.getMessage() + ").");
                falhas.append("\n - ").append(modelo).append(": ").append(e.getMessage());
            }
        }

        // Antes a mensagem escondia o motivo real; agora o usuário vê o que aconteceu com cada modelo
        throw new RuntimeException("Nenhum modelo Gemini respondeu:" + falhas
                + "\nSe aparecer HTTP 404, o modelo foi aposentado: liste os ativos em "
                + "https://ai.google.dev/gemini-api/docs/models e ajuste a variável GEMINI_MODELS.");
    }

    private String executarChamadaModelo(String modelo, String apiKey, String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL_BASE + modelo + ":generateContent"))
                .header("Content-Type", "application/json")
                // A chave vai no cabeçalho, não na URL: URLs aparecem em logs e mensagens de erro
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(TIMEOUT_SEGUNDOS))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw new RuntimeException("não foi possível conectar à API do Google em "
                    + TIMEOUT_CONEXAO_SEGUNDOS + "s (problema de rede do servidor)");
        } catch (HttpTimeoutException e) {
            throw new RuntimeException("conectou, mas o modelo não respondeu em " + TIMEOUT_SEGUNDOS
                    + "s (modelo lento ou sobrecarregado)");
        } catch (java.io.IOException e) {
            throw new RuntimeException("falha de rede (" + e.getClass().getSimpleName() + ")");
        }

        if (response.statusCode() != 200) {
            String motivo = JsonUtil.extrairTexto(response.body(), "message");
            if (motivo != null && motivo.length() > 160) motivo = motivo.substring(0, 160) + "...";
            throw new RuntimeException("HTTP " + response.statusCode() + (motivo != null ? " - " + motivo : ""));
        }

        String texto = JsonUtil.extrairTexto(response.body(), "text");
        if (texto == null || texto.isBlank()) {
            throw new RuntimeException("resposta sem texto (possível bloqueio de segurança)");
        }
        return texto.trim();
    }
}