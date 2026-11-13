import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cliente HTTP para consulta de ativos e ações da B3 via Brapi API.
 *
 * Observação: sem token a Brapi só libera PETR4, VALE3, ITUB4 e MGLU3.
 * Para qualquer outro ativo defina a variável de ambiente BRAPI_TOKEN.
 */
public class BrapiClient {

    /** Dados de uma cotação já interpretados. */
    public static class Cotacao {
        public final String ticker;
        public final String nome;
        public final double preco;
        public final double variacaoPercentual;

        public Cotacao(String ticker, String nome, double preco, double variacaoPercentual) {
            this.ticker = ticker;
            this.nome = nome;
            this.preco = preco;
            this.variacaoPercentual = variacaoPercentual;
        }
    }

    private static final String URL_BASE = "https://brapi.dev/api/quote/";

    // Um único HttpClient reaproveitado (criar um por requisição desperdiça threads e conexões)
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Consulta preço, nome e variação do dia de um ativo (ex: PETR4, VALE3, ^BVSP).
     */
    public Cotacao obterCotacao(String ticker) throws Exception {
        String codigo = (ticker == null) ? "" : ticker.trim().toUpperCase();
        if (!codigo.matches("\\^?[A-Z0-9]{1,12}")) {
            throw new IllegalArgumentException("Ticker inválido: " + ticker);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder()
                // URLEncoder converte o "^" do índice (^BVSP) em %5E; sem isso o URI.create falha
                .uri(URI.create(URL_BASE + URLEncoder.encode(codigo, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .GET();

        String token = System.getenv("BRAPI_TOKEN");
        if (token != null && !token.isBlank()) {
            req.header("Authorization", "Bearer " + token.trim());
        }

        HttpResponse<String> response = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 401 || status == 402 || status == 403) {
            throw new RuntimeException("A Brapi exige token para " + codigo
                    + " (HTTP " + status + "). Defina a variável de ambiente BRAPI_TOKEN.");
        }
        if (status == 404) {
            throw new RuntimeException("Ticker não encontrado na Brapi: " + codigo);
        }
        if (status != 200) {
            throw new RuntimeException("A Brapi respondeu HTTP " + status + " para " + codigo);
        }

        String json = response.body();
        double preco = JsonUtil.extrairNumero(json, "regularMarketPrice", Double.NaN);
        if (Double.isNaN(preco)) {
            throw new RuntimeException("A Brapi não retornou preço para " + codigo);
        }
        double variacao = JsonUtil.extrairNumero(json, "regularMarketChangePercent", 0.0);
        String nome = JsonUtil.extrairTexto(json, "shortName");
        if (nome == null || nome.isBlank()) nome = codigo;

        return new Cotacao(codigo, nome, preco, variacao);
    }
}