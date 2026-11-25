package control;

import entity.JiraVersion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sequenza delle release considerate (indici 1..n in ordine di data) con la data di riferimento di
 * ciascuna: la piu' recente tra la data Jira e l'ultimo commit con modifiche nella storia della release.
 * Per la 0.9.0 la data Jira (26/08/2006) precede il commit di rilascio (09/10/2006): conta il codice.
 */
public final class ReleaseTimeline {

    private final List<String> versions = new ArrayList<>();
    private final List<LocalDate> dates = new ArrayList<>();
    private final Map<String, Integer> indexByVersion = new HashMap<>();
    private final Map<String, LocalDate> excludedVersionDates = new HashMap<>();

    /**
     * @param versions       versioni in ordine di indice (la prima ha indice 1)
     * @param referenceDates date di riferimento, nello stesso ordine
     * @param jiraVersions   tutte le versioni Jira: servono per mappare le AV su versioni escluse
     */
    public ReleaseTimeline(List<String> versions, List<LocalDate> referenceDates, List<JiraVersion> jiraVersions) {
        if (versions.size() != referenceDates.size()) {
            throw new IllegalArgumentException("Versioni e date di numero diverso");
        }
        for (int i = 0; i < versions.size(); i++) {
            this.versions.add(versions.get(i));
            this.dates.add(referenceDates.get(i));
            indexByVersion.put(versions.get(i), i + 1);
        }
        for (JiraVersion v : jiraVersions) {
            if (v.released() && v.releaseDate() != null && !indexByVersion.containsKey(v.name())) {
                excludedVersionDates.put(v.name(), v.releaseDate());
            }
        }
    }

    public int size() {
        return versions.size();
    }

    public String version(int index) {
        return versions.get(index - 1);
    }

    public LocalDate date(int index) {
        return dates.get(index - 1);
    }

    /** Prima release (indice) con data di riferimento uguale o successiva a {@code day}. */
    public Optional<Integer> firstOnOrAfter(LocalDate day) {
        for (int i = 0; i < dates.size(); i++) {
            if (!dates.get(i).isBefore(day)) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }

    /**
     * Indice di una versione citata in un ticket: le release considerate hanno il loro indice; una versione
     * esclusa ma rilasciata (milestone, beta, manutenzione fuori sequenza) corrisponde alla prima release
     * considerata uscita in quella data o dopo; le versioni mai rilasciate non hanno indice.
     */
    public Optional<Integer> indexOf(String versionName) {
        Integer idx = indexByVersion.get(versionName);
        if (idx != null) {
            return Optional.of(idx);
        }
        LocalDate d = excludedVersionDates.get(versionName);
        return d == null ? Optional.empty() : firstOnOrAfter(d);
    }
}
