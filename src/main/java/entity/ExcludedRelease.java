package entity;

import java.time.LocalDate;

/** Versione Jira scartata, con il motivo dell'esclusione (finisce nel CSV delle escluse). */
public record ExcludedRelease(String name, String jiraId, LocalDate releaseDate, String reason) {

    public static ExcludedRelease of(JiraVersion v, String reason) {
        return new ExcludedRelease(v.name(), v.id(), v.releaseDate(), reason);
    }
}