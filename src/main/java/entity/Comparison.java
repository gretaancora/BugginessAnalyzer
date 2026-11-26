package entity;

/**
 * Confronto statistico tra due modelli su una metrica. Per la 10x10 CV il test e' il t-test corretto
 * (Nadeau-Bengio) sui fold appaiati; per l'holdout e' il bootstrap appaiato sulle righe del test.
 * {@code pHolm} e' il p-value corretto con Holm dentro la famiglia (famiglia, gruppo, metrica).
 *
 * @param family    TECHNIQUES (configurazioni contro la baseline di un classificatore), CLASSIFIERS (classificatori a
 *                  parita' di tecniche) o BEST (versioni migliori dei classificatori)
 * @param group     classificatore (TECHNIQUES), coppia di tecniche (CLASSIFIERS) o "-" (BEST)
 * @param n         fold appaiati validi (CV) o righe del test (holdout)
 * @param meanA     media di A sui fold (CV) o valore sul test (holdout)
 * @param statistic statistica t (CV); NaN per il bootstrap
 */
public record Comparison(String validation, String family, String group, String metric, String modelA,
                         String modelB, int n, double meanA, double meanB, double difference, double statistic,
                         double pValue, double pHolm, double ciLow, double ciHigh) {

    public Comparison withHolm(double adjusted) {
        return new Comparison(validation, family, group, metric, modelA, modelB, n, meanA, meanB, difference,
                statistic, pValue, adjusted, ciLow, ciHigh);
    }
}