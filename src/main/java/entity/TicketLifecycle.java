package entity;

import java.time.LocalDate;

/**
 * Ciclo di vita di un bug sulla sequenza delle release (indici da 1).
 *
 * @param ov       release in sviluppo quando il ticket e' stato aperto (prima release con data >= apertura)
 * @param fv       release in sviluppo quando il ticket e' stato risolto (prima release con data >= risoluzione)
 * @param iv       release in cui il bug e' stato introdotto
 * @param ivSource da dove viene la IV
 */
public record TicketLifecycle(String key, LocalDate created, LocalDate resolved, int ov, int fv, int iv,
                              IvSource ivSource) {

    public enum IvSource {
        /** La piu' vecchia affected version, coerente con OV e FV. */
        AFFECTED_VERSION,
        /** Stimata con Proportion: il ticket non ha affected version utilizzabili. */
        PROPORTION_NO_AV,
        /** Stimata con Proportion: le affected version del ticket sono incoerenti con OV o FV. */
        PROPORTION_INCOHERENT_AV
    }

    /** Release in cui il bug e' presente: da IV inclusa a FV esclusa. */
    public boolean affects(int release) {
        return release >= iv && release < fv;
    }
}
