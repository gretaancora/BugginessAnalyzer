package exception;

/** Errore di uno strumento di analisi (CK, SonarCloud). */
public class AnalysisException extends Exception {

    private static final long serialVersionUID = 1L;

    public AnalysisException(String message) {
        super(message);
    }

    public AnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}