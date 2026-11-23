package entity;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Release considerata nell'analisi: versione Jira associata al commit Git che ne rappresenta il codice.
 * L'indice e' la posizione (da 1) nella sequenza ordinata per data, usata da Proportion.
 */
public final class Release {

    private final int index;
    private final String version;
    private final String jiraId;
    private final LocalDate releaseDate;
    private final String gitRef;
    private final String commit;

    public Release(int index, String version, String jiraId, LocalDate releaseDate, String gitRef, String commit) {
        this.index = index;
        this.version = Objects.requireNonNull(version);
        this.jiraId = Objects.requireNonNull(jiraId);
        this.releaseDate = Objects.requireNonNull(releaseDate);
        this.gitRef = Objects.requireNonNull(gitRef);
        this.commit = Objects.requireNonNull(commit);
    }

    public Release withIndex(int newIndex) {
        return new Release(newIndex, version, jiraId, releaseDate, gitRef, commit);
    }

    public int getIndex() {
        return index;
    }

    public String getVersion() {
        return version;
    }

    public String getJiraId() {
        return jiraId;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    /** Tag Git (es. "refs/tags/1.0.0") o, se non esiste, il commit scelto a mano. */
    public String getGitRef() {
        return gitRef;
    }

    /** Hash completo del commit da cui fare il checkout. */
    public String getCommit() {
        return commit;
    }

    @Override
    public String toString() {
        return index + " " + version + " (" + releaseDate + ")";
    }
}