package entity;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Ticket Jira di tipo bug, chiuso o risolto come "Fixed".
 *
 * @param resolved         data di risoluzione, {@code null} se Jira non la riporta
 * @param affectedVersions nomi delle affected version (campo "versions")
 * @param fixVersions      nomi delle fix version
 */
public record Ticket(String key, OffsetDateTime created, OffsetDateTime resolved,
                     List<String> affectedVersions, List<String> fixVersions) {

    public Ticket {
        affectedVersions = List.copyOf(affectedVersions);
        fixVersions = List.copyOf(fixVersions);
    }
}