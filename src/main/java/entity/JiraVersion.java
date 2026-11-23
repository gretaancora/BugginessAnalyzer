package entity;

import java.time.LocalDate;

/**
 * Versione cosi' come compare su Jira.
 *
 * @param releaseDate data di rilascio, {@code null} se Jira non la riporta
 */
public record JiraVersion(String id, String name, boolean released, LocalDate releaseDate) {
}