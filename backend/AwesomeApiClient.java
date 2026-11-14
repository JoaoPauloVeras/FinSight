import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente HTTP responsável por consultar cotações de câmbio em tempo real
 * através da AwesomeAPI REST pública.
 *
 * Sem chave, a AwesomeAPI limita as requisições por IP (e servidores em nuvem compartilham IPs,
 * então o HTTP 429 é comum). Com a variável de ambiente AWESOMEAPI_KEY a cota passa a ser sua
 * (gratuita, cadastro em awesomeapi.com.br). Além disso, as cotações ficam 60s em cache aqui.
 */
public class AwesomeApiClient implements ServicoCambio {

    private static final String URL_BASE = "https://economia.awesomeapi.com.br/json/last/";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Cache simples em memória: a própria AwesomeAPI já guarda 1 minuto sem chave, então não perdemos precisão
    private static final long VALIDADE_CACHE_MS = 60_000;
    private static final int MAX_ENTRADAS_CACHE = 200;
    private record Entrada(double taxa, long instanteMs) {}
    private static final ConcurrentHashMap<String, Entrada> CACHE = new ConcurrentHashMap<>();

    /**
     * Busca a taxa de câmbio de uma moeda em relação ao Real (BRL).
     *
     * @param moeda Código da moeda (ex: USD, EUR, BTC)
     * @return Valor da cotação em Reais (R$)
     */
    @Override
    public double obterTaxa(String moeda) throws Exception {
        String codigo = (moeda == null) ? "" : moeda.trim().toUpperCase();
        // Valida antes de montar a URL: o valor vem da internet e não pode "escapar" do caminho
        if (!codigo.matches("[A-Z]{3,5}")) {
            throw new IllegalArgumentException("Código de moeda inválido: " + moeda);
        }

        Entrada emCache = CACHE.get(codigo);
        if (emCache != null && System.currentTimeMillis() - emCache.instanteMs() < VALIDADE_CACHE_MS) {
            return emCache.taxa();
        }

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(URL_BASE + codigo + "-BRL"))
                .timeout(Duration.ofSeconds(10))
                .GET();

        // Chave opcional: vai no cabeçalho (não na URL) para não aparecer em logs
        String chave = System.getenv("AWESOMEAPI_KEY");
        if (chave != null && !chave.isBlank()) {
            req.header("x-api-key", chave.trim());
        }

        HttpResponse<String> response = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 429) {
            // Melhor uma cotação de poucos minutos atrás do que um erro
            if (emCache != null) return emCache.taxa();
            throw new RuntimeException("A AwesomeAPI atingiu o limite de requisições (HTTP 429). "
                    + "Tente novamente em instantes; para evitar isso, configure AWESOMEAPI_KEY no servidor.");
        }
        if (status != 200) {
            throw new RuntimeException("A AwesomeAPI respondeu HTTP " + status + " para a moeda " + codigo);
        }

        // "bid" = preço de compra
        String bid = JsonUtil.extrairTexto(response.body(), "bid");
        if (bid == null) {
            throw new RuntimeException("Resposta da AwesomeAPI sem cotação para: " + codigo);
        }
        double taxa = Double.parseDouble(bid);

        if (CACHE.size() >= MAX_ENTRADAS_CACHE) CACHE.clear(); // limite de memória
        CACHE.put(codigo, new Entrada(taxa, System.currentTimeMillis()));
        return taxa;
    }
}