public class SimuladorInvestimento {
    /**
     * Calcula o montante final acumulado com base em juros compostos: M = P * (1 + i)^t
     *
     * @param valorInicial Capital inicial investido em Reais (R$)
     * @param taxaAnualPercentual Taxa de juros anual em percentagem (ex: 10 para 10%)
     * @param anos Tempo em que o valor ficará investido (em anos)
     * @return Montante final acumulado
     */
    public double calcularJurosCompostos(double valorInicial, double taxaAnualPercentual, int anos) {
        // Converte a percentagem para valor decimal (ex: 10.0 / 100.0 = 0.10)
        double taxaDecimal = taxaAnualPercentual / 100.0;

        // Aplica a fórmula matemática M = P * (1 + i)^t
        return valorInicial * Math.pow(1 + taxaDecimal, anos);
    }
}
