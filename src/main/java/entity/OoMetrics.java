package entity;

/**
 * Metriche OO di una classe calcolate da CK.
 *
 * @param file      percorso del file relativo alla radice della release
 * @param className nome binario della classe (es. "org.a.B" o "org.a.B$C")
 * @param type      tipo secondo CK: class, interface, enum, innerclass, anonymous
 * @param lcomStar  LCOM normalizzato tra 0 e 1 (0 se non definito)
 */
public record OoMetrics(String file, String className, String type,
                        int cbo, int fanIn, int rfc, double lcomStar, int noc) {

    public static final String[] COLUMNS = {"CBO", "FanIn", "RFC", "LCOM*", "NOC"};

    public boolean isTopLevel() {
        return !"innerclass".equals(type) && !"anonymous".equals(type) && !className.contains("$");
    }

    /** Nome semplice della classe (dopo l'ultimo punto). */
    public String simpleName() {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
