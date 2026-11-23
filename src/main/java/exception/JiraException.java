package exception;

/** Errore nell'interazione con Jira. */
public class JiraException extends Exception {

    private static final long serialVersionUID = 1L;

    public JiraException(String message) {
        super(message);
    }

    public JiraException(String message, Throwable cause) {
        super(message, cause);
    }
}