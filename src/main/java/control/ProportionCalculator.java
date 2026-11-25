package control;

import entity.Ticket;
import entity.TicketLifecycle;
import entity.TicketLifecycle.IvSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Determina OV, FV e IV dei ticket (Proportion "total": un solo P, medio su tutti i ticket con affected
 * version coerenti).
 *
 * <ul>
 *   <li>OV = prima release con data di riferimento uguale o successiva all'apertura del ticket;</li>
 *   <li>FV = prima release con data di riferimento uguale o successiva alla risoluzione;</li>
 *   <li>le affected version sono coerenti se la piu' vecchia (IV) rispetta IV &lt;= OV e IV &lt; FV;</li>
 *   <li>P = media di (FV - IV) / (FV - OV) sui ticket coerenti, con FV - OV = 1 quando FV = OV (bug
 *       aperto e corretto durante lo sviluppo della stessa release);</li>
 *   <li>per gli altri ticket IV = FV - (FV - OV) * P (stesso denominatore), arrotondata e limitata tra 1 e OV.</li>
 * </ul>
 * I ticket aperti o risolti dopo l'ultima release, o senza data di risoluzione, non hanno OV o FV e restano fuori.
 */
public final class ProportionCalculator {

    private final ReleaseTimeline timeline;

    public ProportionCalculator(ReleaseTimeline timeline) {
        this.timeline = timeline;
    }

    public record Result(List<TicketLifecycle> lifecycles, double p, int ticketsForP, int withoutOvOrFv) {
    }

    private record Dated(Ticket ticket, LocalDate created, LocalDate resolved, int ov, int fv, Optional<Integer> iv,
                         boolean hasAv) {
    }

    public Result compute(List<Ticket> tickets) {
        List<Dated> dated = new ArrayList<>();
        int skipped = 0;
        for (Ticket t : tickets) {
            if (t.resolved() == null) {
                skipped++;
                continue;
            }
            LocalDate created = t.created().toLocalDate();
            LocalDate resolved = t.resolved().toLocalDate();
            Optional<Integer> ov = timeline.firstOnOrAfter(created);
            Optional<Integer> fv = timeline.firstOnOrAfter(resolved);
            if (ov.isEmpty() || fv.isEmpty() || fv.get() < ov.get()) {
                skipped++;
                continue;
            }
            Optional<Integer> iv = t.affectedVersions().stream()
                    .map(timeline::indexOf).flatMap(Optional::stream).min(Integer::compare);
            dated.add(new Dated(t, created, resolved, ov.get(), fv.get(), iv, iv.isPresent()));
        }

        double sum = 0;
        int n = 0;
        for (Dated d : dated) {
            if (coherent(d)) {
                sum += (double) (d.fv() - d.iv().orElseThrow()) / span(d.ov(), d.fv());
                n++;
            }
        }
        double p = n == 0 ? 0 : sum / n;

        List<TicketLifecycle> lifecycles = new ArrayList<>();
        for (Dated d : dated) {
            int iv;
            IvSource source;
            if (coherent(d)) {
                iv = d.iv().orElseThrow();
                source = IvSource.AFFECTED_VERSION;
            } else {
                iv = estimate(d.ov(), d.fv(), p);
                source = d.hasAv() ? IvSource.PROPORTION_INCOHERENT_AV : IvSource.PROPORTION_NO_AV;
            }
            lifecycles.add(new TicketLifecycle(d.ticket().key(), d.created(), d.resolved(), d.ov(), d.fv(), iv, source));
        }
        return new Result(List.copyOf(lifecycles), p, n, skipped);
    }

    private static boolean coherent(Dated d) {
        return d.iv().isPresent() && d.iv().get() <= d.ov() && d.iv().get() < d.fv();
    }

    /** FV - OV, con 1 quando FV = OV. */
    static int span(int ov, int fv) {
        return Math.max(1, fv - ov);
    }

    /** IV stimata con Proportion, arrotondata all'intero piu' vicino e limitata tra 1 e OV. */
    static int estimate(int ov, int fv, double p) {
        long iv = Math.round(fv - span(ov, fv) * p);
        return (int) Math.max(1, Math.min(ov, iv));
    }
}
