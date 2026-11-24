package entity;

/**
 * Modifica a un file in un commit, come riportata da {@code git log --numstat -M -w}.
 *
 * @param oldPath percorso prima del commit se il file e' stato rinominato, altrimenti {@code null}
 * @param path    percorso dopo il commit
 * @param added   righe aggiunte (-1 per i file binari)
 * @param deleted righe rimosse (-1 per i file binari)
 */
public record FileChange(String oldPath, String path, int added, int deleted) {

    public boolean isRename() {
        return oldPath != null && !oldPath.equals(path);
    }

    public boolean isBinary() {
        return added < 0 || deleted < 0;
    }

    /** {@code true} se il commit cambia almeno una riga del file (spazi esclusi). */
    public boolean changesLines() {
        return !isBinary() && added + deleted > 0;
    }

    public int touched() {
        return isBinary() ? 0 : added + deleted;
    }
}