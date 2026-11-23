package exception;

/** Errore nell'interazione con Git. */
public class GitException extends Exception {

    private static final long serialVersionUID = 1L;

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}