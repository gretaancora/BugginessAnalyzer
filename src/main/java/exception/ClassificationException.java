package exception;

/** Errore durante l'addestramento o la valutazione di un classificatore (Weka). */
public class ClassificationException extends Exception {

    private static final long serialVersionUID = 1L;

    public ClassificationException(String message) {
        super(message);
    }

    public ClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}