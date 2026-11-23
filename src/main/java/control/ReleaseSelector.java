package control;

import entity.ExcludedRelease;
import entity.JiraVersion;
import entity.VersionNumber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sceglie, tra le versioni Jira, quelle che formano la sequenza di release dell'analisi.
 *
 * <p>Regole, nell'ordine:
 * <ol>
 *   <li>solo versioni rilasciate ({@code released=true}) e con data di rilascio;</li>
 *   <li>niente pre-release (milestone, alpha, beta, RC): sono fotografie del codice in sviluppo e
 *       i loro bug hanno un ciclo di vita diverso da quello del codice stabile;</li>
 *   <li>niente release "fuori sequenza": una release resta solo se la sua linea (major.minor) e' la
 *       piu' recente tra quelle gia' rilasciate a quella data (stesso giorno compreso). Una 1.0.3 uscita
 *       dopo la 1.1.0 e' manutenzione di una linea vecchia e romperebbe la linearita' degli indici
 *       usati da Proportion.</li>
 * </ol>
 * Le release tenute sono ordinate per data e, a parita' di data, per numero di versione.
 */
public final class ReleaseSelector {

    private static final Comparator<Candidate> CHRONOLOGICAL = Comparator
            .comparing((Candidate c) -> c.version().releaseDate())
            .thenComparing(Candidate::number)
            .thenComparing(c -> c.version().name());

    private final Pattern preReleasePattern;

    public ReleaseSelector(Pattern preReleasePattern) {
        this.preReleasePattern = preReleasePattern;
    }

    public record Selection(List<JiraVersion> kept, List<ExcludedRelease> excluded) {
    }

    private record Candidate(JiraVersion version, VersionNumber number) {
    }

    public Selection select(List<JiraVersion> all) {
        List<ExcludedRelease> excluded = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();

        for (JiraVersion v : all) {
            Optional<VersionNumber> number = VersionNumber.parse(v.name());
            if (!v.released()) {
                excluded.add(ExcludedRelease.of(v, "non rilasciata"));
            } else if (v.releaseDate() == null) {
                excluded.add(ExcludedRelease.of(v, "senza data di rilascio"));
            } else if (preReleasePattern.matcher(v.name()).matches()) {
                excluded.add(ExcludedRelease.of(v, "pre-release (milestone/beta)"));
            } else if (number.isEmpty()) {
                excluded.add(ExcludedRelease.of(v, "nome di versione non numerico"));
            } else {
                candidates.add(new Candidate(v, number.get()));
            }
        }

        candidates.sort(CHRONOLOGICAL);

        List<JiraVersion> kept = new ArrayList<>();
        for (Candidate c : candidates) {
            Optional<Candidate> newerLine = firstReleaseOfNewerLine(c, candidates);
            if (newerLine.isPresent()) {
                Candidate n = newerLine.get();
                excluded.add(ExcludedRelease.of(c.version(), String.format(
                        "fuori sequenza: linea %s rilasciata dopo l'uscita della linea %s (%s del %s)",
                        c.number().line(), n.number().line(), n.version().name(), n.version().releaseDate())));
            } else {
                kept.add(c.version());
            }
        }
        return new Selection(List.copyOf(kept), List.copyOf(excluded));
    }

    /** Prima release di una linea piu' recente uscita entro la data di {@code c}, se esiste. */
    private static Optional<Candidate> firstReleaseOfNewerLine(Candidate c, List<Candidate> sorted) {
        VersionNumber line = c.number().line();
        Candidate newest = null;
        for (Candidate other : sorted) {
            if (other.version().releaseDate().isAfter(c.version().releaseDate())) {
                break;
            }
            VersionNumber otherLine = other.number().line();
            if (otherLine.compareTo(line) > 0
                    && (newest == null || otherLine.compareTo(newest.number().line()) > 0)) {
                newest = other;
            }
        }
        return Optional.ofNullable(newest);
    }

    /** Numero di release da tenere nel dataset: floor(total * percentage / 100). */
    public static int datasetSize(int total, int percentage) {
        return total * percentage / 100;
    }
}