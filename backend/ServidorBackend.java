import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Servidor Backend Java - FinSight Analytics (sem dependências externas).
 *
 * Endpoints:
 *  - /api/acoes      : cotações da B3 via Brapi (lista padrão ou ?ticker=XXXX)
 *  - /api/rendafixa  : simulação de CDB, Tesouro Selic e LCI/LCA
 *  - /api/cambio     : cotação de moedas via AwesomeAPI
 *  - /api/ia         : análise via Google Gemini
 *
 * Variáveis de ambiente opcionais: GEMINI_API_KEY, BRAPI_TOKEN, CDI_ANUAL, GEMINI_MODELS,
 * IA_MAX_POR_MINUTO, PORT, BIND_ADDRESS, STATIC_DIR e CORS_ORIGIN (só para desenvolvimento local; em produção deixe VAZIA).
 *
 * Além da API, este servidor entrega o site (index.html e logo) na rota "/", então site e API ficam
 * na mesma origem e não é preciso CORS.
 */
public class ServidorBackend {

    // O Render define a variável PORT; localmente usa 8080
    private static final int PORTA = inteiroDoAmbiente("PORT", 8080);
    private static final double CDI_ANUAL_PADRAO = 10.75; // % a.a. — sobrescreva com a variável CDI_ANUAL
    private static final int MAX_PROMPT = 2000;

    // Teto GLOBAL de chamadas à IA por minuto (protege sua cota/fatura do Gemini mesmo contra abuso
    // vindo de vários IPs). Sobrescreva com a variável IA_MAX_POR_MINUTO.
    private static final LimiteGlobal LIMITE_IA = new LimiteGlobal(inteiroDoAmbiente("IA_MAX_POR_MINUTO", 10), 60_000);

    // Ações da lista inicial: nome amigável e setor (a Brapi não devolve setor na cotação simples).
    // PETR4, VALE3, ITUB4 e MGLU3 funcionam sem token; para incluir BBAS3 é preciso BRAPI_TOKEN.
    private static final String[][] ACOES_PADRAO = {
            {"PETR4", "Petrobras", "Petróleo e Gás"},
            {"VALE3", "Vale", "Mineração"},
            {"ITUB4", "Itaú Unibanco", "Bancos"},
            {"MGLU3", "Magazine Luiza", "Varejo"}
    };

    public void iniciar() throws IOException {
        // Local: só aceita conexões da própria máquina. Na nuvem (Docker/Render) defina BIND_ADDRESS=0.0.0.0,
        // porque o tráfego chega pela rede interna da plataforma, que já cuida do HTTPS.
        String bind = System.getenv("BIND_ADDRESS");
        InetAddress endereco = vazio(bind) ? InetAddress.getLoopbackAddress() : InetAddress.getByName(bind.trim());
        HttpServer server = HttpServer.create(new InetSocketAddress(endereco, PORTA), 0);

        server.createContext("/api/rendafixa", new RendaFixaHandler());
        server.createContext("/api/cambio", new CambioHandler());
        server.createContext("/api/acoes", new AcoesHandler());
        server.createContext("/api/ia", new IaHandler());
        server.createContext("/", new EstaticoHandler());   // site (index.html + logo)

        // Com setExecutor(null) tudo roda em UMA thread: uma chamada lenta ao Gemini travaria as demais rotas
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("=================================================");
        System.out.println("🚀 FINSIGHT BACKEND JAVA RODANDO EM http://localhost:" + PORTA);
        if (vazio(System.getenv("GEMINI_API_KEY"))) {
            System.out.println("⚠️  GEMINI_API_KEY não definida: a rota /api/ia vai retornar erro.");
        }
        if (vazio(System.getenv("BRAPI_TOKEN"))) {
            System.out.println("ℹ️  BRAPI_TOKEN não definido: só PETR4, VALE3, ITUB4 e MGLU3 serão consultáveis.");
        }
        System.out.println("=================================================");
    }

    // =========================================================================
    // BASE COMUM DOS HANDLERS (CORS, OPTIONS, tratamento de erros)
    // =========================================================================
    abstract static class RotaJson implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            configurarCors(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            try {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
                enviarRespostaJson(exchange, 200, responder(params));
            } catch (IllegalArgumentException e) {
                // Dados inválidos enviados pelo cliente → 400
                enviarRespostaJson(exchange, 400, erroJson(e.getMessage()));
            } catch (LimiteExcedidoException e) {
                // Excesso de requisições → 429
                enviarRespostaJson(exchange, 429, erroJson(e.getMessage()));
            } catch (Exception e) {
                System.err.println("❌ [" + exchange.getRequestURI().getPath() + "] " + descrever(e));
                enviarRespostaJson(exchange, 500, erroJson(descrever(e)));
            }
        }

        /** Devolve o JSON de sucesso. Lance IllegalArgumentException para erro de entrada (400). */
        protected abstract String responder(Map<String, String> params) throws Exception;
    }

    // =========================================================================
    // 1. AÇÕES (/api/acoes)
    // =========================================================================
    static class AcoesHandler extends RotaJson {
        private final BrapiClient brapi = new BrapiClient();

        @Override
        protected String responder(Map<String, String> params) throws Exception {
            String ticker = params.get("ticker");

            // Busca individual (ex: MGLU3, ^BVSP)
            if (!vazio(ticker)) {
                BrapiClient.Cotacao c = brapi.obterCotacao(ticker);
                return acaoJson(c.ticker, c.nome, "Mercado B3", c.preco, c.variacaoPercentual);
            }

            // Lista padrão: preço e variação REAIS. O que falhar é informado, não substituído por número inventado.
            List<String> itens = new ArrayList<>();
            List<String> indisponiveis = new ArrayList<>();
            for (String[] a : ACOES_PADRAO) {
                try {
                    BrapiClient.Cotacao c = brapi.obterCotacao(a[0]);
                    itens.add(acaoJson(a[0], a[1], a[2], c.preco, c.variacaoPercentual));
                } catch (Exception e) {
                    System.err.println("⚠️ [Brapi] " + a[0] + " indisponível: " + descrever(e));
                    indisponiveis.add("\"" + a[0] + "\"");
                }
            }
            return "{\"acoes\":[" + String.join(",", itens) + "],"
                    + "\"indisponiveis\":[" + String.join(",", indisponiveis) + "]}";
        }
    }

    // =========================================================================
    // 2. RENDA FIXA (/api/rendafixa)
    // =========================================================================
    static class RendaFixaHandler extends RotaJson {
        private final SimuladorRendaFixa simulador = new SimuladorRendaFixa();

        @Override
        protected String responder(Map<String, String> params) {
            double valor = numero(params, "valor", 10000);
            int dias = (int) numero(params, "dias", 365);
            String tipo = params.getOrDefault("tipo", "cdb").trim().toLowerCase();
            double percentualCdi = numero(params, "percentualCdi", 100.0);

            if (valor <= 0) throw new IllegalArgumentException("O valor investido deve ser maior que zero.");
            if (dias < 1 || dias > 36500) throw new IllegalArgumentException("O prazo deve estar entre 1 e 36500 dias.");
            if (percentualCdi <= 0 || percentualCdi > 1000) {
                throw new IllegalArgumentException("O percentual do CDI deve estar entre 0 e 1000.");
            }
            if (!List.of("cdb", "tesouro", "lci", "lca").contains(tipo)) {
                throw new IllegalArgumentException("Tipo de investimento inválido: " + tipo);
            }

            double taxaCdiAnual = taxaCdiAnual();

            // Taxa efetiva = CDI de referência × % do CDI pago pelo título
            double taxaEfetivaAnual = (taxaCdiAnual * (percentualCdi / 100.0)) / 100.0;
            double anos = dias / 365.0;
            double rendimentoBruto = valor * Math.pow(1 + taxaEfetivaAnual, anos) - valor;

            // CDB e Tesouro pagam IR (tabela regressiva); LCI e LCA são isentas para pessoa física
            boolean isento = tipo.equals("lci") || tipo.equals("lca");
            double aliquotaIr = isento ? 0.0 : simulador.calcularAliquotaIR(dias);
            double imposto = rendimentoBruto * aliquotaIr;
            double valorLiquido = valor + rendimentoBruto - imposto;

            return String.format(Locale.US,
                    "{\"valorInicial\":%.2f,\"percentualCdi\":%.1f,\"taxaCdiAnual\":%.2f,"
                            + "\"rendimentoBruto\":%.2f,\"aliquotaIr\":%.3f,\"imposto\":%.2f,\"valorLiquido\":%.2f}",
                    valor, percentualCdi, taxaCdiAnual, rendimentoBruto, aliquotaIr, imposto, valorLiquido);
        }
    }

    // =========================================================================
    // 3. CÂMBIO (/api/cambio)
    // =========================================================================
    static class CambioHandler extends RotaJson {
        private final ServicoCambio servicoCambio = new AwesomeApiClient();

        @Override
        protected String responder(Map<String, String> params) throws Exception {
            String moeda = params.getOrDefault("moeda", "USD").trim().toUpperCase();
            double taxa = servicoCambio.obterTaxa(moeda); // valida o código da moeda
            return String.format(Locale.US, "{\"moeda\":\"%s\",\"taxa\":%.4f}", JsonUtil.escapar(moeda), taxa);
        }
    }

    // =========================================================================
    // 4. IA GEMINI (/api/ia)
    // =========================================================================
    static class IaHandler extends RotaJson {
        @Override
        protected String responder(Map<String, String> params) throws Exception {
            String pergunta = params.getOrDefault("prompt", "Analise o mercado.");
            if (pergunta.length() > MAX_PROMPT) {
                throw new IllegalArgumentException("Pergunta muito longa (máximo de " + MAX_PROMPT + " caracteres).");
            }
            if (!LIMITE_IA.permitir()) {
                throw new LimiteExcedidoException("Muitas consultas à IA neste momento. Tente novamente em instantes.");
            }

            // Instrução de sistema que existia na versão anterior e tinha sumido: mantém as respostas
            // curtas, o que evita estourar o timeout de 12s por modelo
            // Também pede uma estrutura de leitura fácil: um bloco por tema, com título em negrito e tópicos
            String promptComInstrucao =
                    "Atue como analista sênior do FinSight. Responda de forma executiva, objetiva e direta, "
                            + "com no máximo 250 palavras. Organize em seções curtas: cada tema começa com um título "
                            + "em **negrito** numa linha própria, seguido de 2 a 4 tópicos iniciados por '- ', e deixe "
                            + "uma linha em branco entre as seções. Não use tabelas nem títulos com '#'. Pergunta: " + pergunta;

            System.out.println("🤖 [FinSight IA Analytics] Processando: " + pergunta);
            String resposta = new GeminiApiClient().gerarAnaliseMercado(promptComInstrucao);
            return "{\"analise\":\"" + JsonUtil.escapar(resposta) + "\"}";
        }
    }

    // =========================================================================
    // SITE ESTÁTICO (/)
    // =========================================================================
    /** Entrega SOMENTE os arquivos da lista abaixo (lista fixa = sem risco de acessar outros arquivos do servidor). */
    static class EstaticoHandler implements HttpHandler {
        private static final Map<String, String> ARQUIVOS = Map.of(
                "/", "index.html",
                "/index.html", "index.html",
                "/logo-finsight.png", "logo-finsight.png");
        private static final Map<String, String> TIPOS = Map.of(
                "index.html", "text/html; charset=UTF-8",
                "logo-finsight.png", "image/png");

        private final Path pasta = resolverPasta();

        private static Path resolverPasta() {
            String env = System.getenv("STATIC_DIR");
            if (!vazio(env)) return Paths.get(env.trim()).toAbsolutePath();
            Path padrao = Paths.get("frontend").toAbsolutePath();
            return Files.isDirectory(padrao) ? padrao : Paths.get(".").toAbsolutePath().normalize();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String metodo = exchange.getRequestMethod();
                if (!"GET".equalsIgnoreCase(metodo) && !"HEAD".equalsIgnoreCase(metodo)) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                String arquivo = ARQUIVOS.get(exchange.getRequestURI().getPath());
                Path caminho = (arquivo == null) ? null : pasta.resolve(arquivo);
                if (caminho == null || !Files.isRegularFile(caminho)) {
                    byte[] msg = "Não encontrado".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    exchange.sendResponseHeaders(404, "HEAD".equalsIgnoreCase(metodo) ? -1 : msg.length);
                    if (!"HEAD".equalsIgnoreCase(metodo)) {
                        try (OutputStream os = exchange.getResponseBody()) { os.write(msg); }
                    }
                    return;
                }

                byte[] bytes = Files.readAllBytes(caminho);
                var h = exchange.getResponseHeaders();
                h.set("Content-Type", TIPOS.get(arquivo));
                h.set("X-Content-Type-Options", "nosniff");
                h.set("X-Frame-Options", "DENY");
                h.set("Referrer-Policy", "strict-origin-when-cross-origin");
                h.set("Strict-Transport-Security", "max-age=31536000");
                h.set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
                        + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; "
                        + "form-action 'self'; frame-ancestors 'none'");
                h.set("Cache-Control", arquivo.endsWith(".html") ? "no-cache" : "public, max-age=86400");

                if ("HEAD".equalsIgnoreCase(metodo)) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                }
            } finally {
                exchange.close();
            }
        }
    }

    // =========================================================================
    // UTILITÁRIOS
    // =========================================================================
    private static String acaoJson(String ticker, String nome, String setor, double preco, double variacao) {
        // Locale.US garante ponto decimal; NÃO é preciso (nem seguro) trocar vírgulas na string inteira
        return String.format(Locale.US,
                "{\"ticker\":\"%s\",\"nome\":\"%s\",\"setor\":\"%s\",\"preco\":%.2f,\"var\":%.2f}",
                JsonUtil.escapar(ticker), JsonUtil.escapar(nome), JsonUtil.escapar(setor), preco, variacao);
    }

    /** Erro de "muitas requisições" (vira HTTP 429). */
    static class LimiteExcedidoException extends RuntimeException {
        LimiteExcedidoException(String mensagem) { super(mensagem); }
    }

    /** Janela deslizante simples: no máximo {@code max} permissões a cada {@code janelaMs} milissegundos. */
    static class LimiteGlobal {
        private final int max;
        private final long janelaMs;
        private final java.util.ArrayDeque<Long> marcas = new java.util.ArrayDeque<>();

        LimiteGlobal(int max, long janelaMs) {
            this.max = max;
            this.janelaMs = janelaMs;
        }

        synchronized boolean permitir() {
            long agora = System.currentTimeMillis();
            while (!marcas.isEmpty() && agora - marcas.peekFirst() > janelaMs) marcas.pollFirst();
            if (marcas.size() >= max) return false;
            marcas.addLast(agora);
            return true;
        }
    }

    private static int inteiroDoAmbiente(String nome, int padrao) {
        String v = System.getenv(nome);
        if (vazio(v)) return padrao;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : padrao;
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    /**
     * Texto de erro legível. Falhas de rede (ConnectException, timeout...) costumam ter getMessage() == null,
     * o que antes aparecia como "null" ou como o nome da classe da exceção.
     */
    static String descrever(Exception e) {
        if (e instanceof IOException) {
            return "Falha de rede ao consultar o serviço externo (" + e.getClass().getSimpleName()
                    + "). Verifique a conexão com a internet.";
        }
        return (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.toString();
    }

    private static String erroJson(String mensagem) {
        return "{\"erro\":\"" + JsonUtil.escapar(mensagem) + "\"}";
    }

    private static double taxaCdiAnual() {
        String env = System.getenv("CDI_ANUAL");
        if (!vazio(env)) {
            try {
                double v = Double.parseDouble(env.trim().replace(',', '.'));
                if (v > 0) return v;
            } catch (NumberFormatException ignorado) {
                // cai no valor padrão
            }
        }
        return CDI_ANUAL_PADRAO;
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static double numero(Map<String, String> params, String nome, double padrao) {
        String v = params.get(nome);
        if (vazio(v)) return padrao;
        try {
            double d = Double.parseDouble(v.trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor inválido para '" + nome + "': " + v);
        }
    }

    private static void configurarCors(HttpExchange exchange) {
        // Em produção front e API ficam na MESMA origem (o Nginx serve os dois), então NÃO precisa de CORS
        // e o melhor é não enviar nenhum cabeçalho. Para desenvolvimento local (front em outra porta/arquivo),
        // defina CORS_ORIGIN, por exemplo "*" ou "http://localhost:5500".
        String origem = System.getenv("CORS_ORIGIN");
        if (!vazio(origem)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origem.trim());
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void enviarRespostaJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Lê a query string já decodificando chaves e valores (UTF-8, %20, + etc.).
     * A decodificação acontece só aqui, uma vez; decodificar de novo nos handlers
     * quebraria textos que contenham um "%" literal.
     */
    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;

        for (String param : query.split("&")) {
            if (param.isEmpty()) continue;
            String[] par = param.split("=", 2); // divide só no primeiro "=" para não cortar textos que o contenham
            map.put(decodificar(par[0]), par.length > 1 ? decodificar(par[1]) : "");
        }
        return map;
    }

    private static String decodificar(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Parâmetro mal codificado na URL.");
        }
    }
}