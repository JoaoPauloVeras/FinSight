/**
 * Classe de entrada principal do sistema.
 * Inicializa o Servidor Backend Java para comunicar com o Frontend Web.
 */
public class Main {

    public static void main(String[] args) {
        try {
            System.out.println("Iniciando o Dashboard Financeiro Backend...\n");

            ServidorBackend servidor = new ServidorBackend();
            servidor.iniciar();

        } catch (java.net.BindException e) {
            System.err.println("❌ A porta do servidor já está em uso. Feche a execução anterior do servidor e tente de novo.");
            System.exit(1);
        } catch (Exception e) {
            // getMessage() pode ser null; imprimir a exceção inteira mostra sempre o motivo
            System.err.println("❌ Erro fatal ao iniciar o servidor Java: " + e);
            System.exit(1);
        }
    }
}