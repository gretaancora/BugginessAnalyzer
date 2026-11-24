package entity;

import java.time.Instant;
import java.util.List;

/** Commit con autore, data e file modificati. */
public record Commit(String hash, String author, Instant time, List<FileChange> changes) {

    public Commit {
        changes = List.copyOf(changes);
    }
}