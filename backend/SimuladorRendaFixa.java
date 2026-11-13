/**
 * Classe responsável por simular investimentos de Renda Fixa
 * aplicando as alíquotas da Tabela Regressiva de Imposto de Renda e isenções fiscais.
 */
public class SimuladorRendaFixa {

    /**
     * Retorna a alíquota de Imposto de Renda com base no tempo da aplicação.
     *
     * @param dias Tempo do investimento em dias
     * @return Percentual do IR sobre os lucros (ex: 0.225 para 22.5%)
     */
    public double calcularAliquotaIR(int dias) {
        if (dias <= 180) {
            return 0.225; // 22.5% até 180 dias
        } else if (dias <= 360) {
            return 0.20;  // 20.0% de 181 a 360 dias
        } else if (dias <= 720) {
            return 0.175; // 17.5% de 361 a 720 dias
        } else {
            return 0.15;  // 15.0% acima de 720 dias
        }
    }

    /**
     * Simula rendimentos de CDB ou Tesouro Selic (sujeitos a Imposto de Renda).
     *
     * @param valorInicial Capital investido
     * @param percentualCdi Percentual do CDI pago pelo título (ex: 100 para 100% do CDI)
     * @param taxaCdiAnual Taxa CDI anual atual (ex: 10.75)
     * @param dias Período em dias
     * @return Objeto com o resumo formatado da simulação
     */
    public String simularCdbOuSelic(double valorInicial, double percentualCdi, double taxaCdiAnual, int dias) {
        double anos = dias / 365.0;
        double taxaEfetivaAnual = (taxaCdiAnual * (percentualCdi / 100.0)) / 100.0;

        double montanteBruto = valorInicial * Math.pow(1 + taxaEfetivaAnual, anos);
        double lucroBruto = montanteBruto - valorInicial;

        double aliquotaIr = calcularAliquotaIR(dias);
        double valorImposto = lucroBruto * aliquotaIr;
        double montanteLiquido = montanteBruto - valorImposto;

        return String.format(
                "Rendimento Bruto: R$ %.2f | IR (%.1f%%): R$ %.2f | Valor Líquido: R$ %.2f",
                lucroBruto, (aliquotaIr * 100), valorImposto, montanteLiquido
        );
    }

    /**
     * Simula rendimentos de LCI ou LCA (Isentos de Imposto de Renda para Pessoa Física).
     *
     * @param valorInicial Capital investido
     * @param percentualCdi Percentual do CDI pago pelo título
     * @param taxaCdiAnual Taxa CDI anual atual
     * @param dias Período em dias
     * @return Objeto com o resumo formatado da simulação
     */
    public String simularLciLca(double valorInicial, double percentualCdi, double taxaCdiAnual, int dias) {
        double anos = dias / 365.0;
        double taxaEfetivaAnual = (taxaCdiAnual * (percentualCdi / 100.0)) / 100.0;

        double montanteLiquido = valorInicial * Math.pow(1 + taxaEfetivaAnual, anos);
        double lucroLiquido = montanteLiquido - valorInicial;

        return String.format(
                "Rendimento Líquido: R$ %.2f | IR: ISENTO (R$ 0,00) | Valor Líquido: R$ %.2f",
                lucroLiquido, montanteLiquido
        );
    }
}