public interface ServicoCambio {
    /**
     * Obtém a taxa de câmbio de uma moeda em relação ao Real (BRL).
     *
     * @param moeda Código da moeda (ex: USD, EUR, BTC)
     * @return O valor da taxa em Reais (R$)
     * @throws Exception Caso ocorra erro na busca da cotação
     */
    double obterTaxa(String moeda) throws Exception;
}
