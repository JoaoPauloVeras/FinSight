import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente HTTP responsável por consultar cotações de câmbio em tempo real
 * através da AwesomeAPI REST pública.
 */
public class AwesomeApiClient implements ServicoCambio {

    private static final String URL_BASE = "https://economia.awesomeapi.com.br/json/last/";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL_BASE + codigo + "-BRL"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("A AwesomeAPI respondeu HTTP " + response.statusCode()
                    + " para a moeda " + codigo);
        }

        // "bid" = preço de compra
        String bid = JsonUtil.extrairTexto(response.body(), "bid");
        if (bid == null) {
            throw new RuntimeException("Resposta da AwesomeAPI sem cotação para: " + codigo);
        }
        return Double.parseDouble(bid);
    }
}