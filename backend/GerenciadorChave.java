/**
 * Classe responsável por ler e validar chaves de API injetadas
 * pelas Variáveis de Ambiente da JVM / IntelliJ IDEA
 */
public class GerenciadorChave {

    // Nome exato da variável de ambiente definida no IntelliJ IDEA
    private static final String NOME_VARIAVEL = "GEMINI_API_KEY";

    /**
     * Obtém a API Key guardada na memória do sistema.
     *
     * @return A chave da API pronta a ser utilizada
     * @throws IllegalStateException Caso a chave não tenha sido configurada no IntelliJ
     */
    public static String obterChave() {
        // Método nativo do Java que lê variáveis de ambiente
        String apiKey = System.getenv(NOME_VARIAVEL);

        // Validação preventiva: Impede o disparo de requisições se a chave não existir
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "\n[ERRO DE CONFIGURAÇÃO]: A variável 'GEMINI_API_KEY' não foi encontrada!\n" +
                            "Instruções de correção:\n" +
                            "1. No IntelliJ, vá a Run > Edit Configurations...\n" +
                            "2. No campo 'Environment variables', adicione: GEMINI_API_KEY=sua_chave\n" +
                            "3. Clique em Apply e tente novamente."
            );
        }

        return apiKey;
    }

    /**
     * Retorna uma versão mascarada da chave para exibição segura nos logs.
     * Exemplo: AIza********************
     */
    public static String obterChaveMascarada() {
        String chave = obterChave();
        if (chave.length() <= 4) {
            return "****";
        }
        return chave.substring(0, 4) + "*".repeat(chave.length() - 4);
    }
}