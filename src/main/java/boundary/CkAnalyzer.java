package boundary;

import com.github.mauricioaniche.ck.CK;
import com.github.mauricioaniche.ck.CKClassResult;
import com.github.mauricioaniche.ck.CKNotifier;
import com.github.mauricioaniche.ck.metric.CouplingExtras;
import com.github.mauricioaniche.ck.metric.NOCExtras;
import entity.OoMetrics;
import exception.AnalysisException;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calcola le metriche OO con CK (com.github.mauricioaniche:ck) analizzando i sorgenti senza compilarli.
 *
 * <p>Due accorgimenti richiesti da CK 0.7.0:
 * <ul>
 *   <li>Fan-In e NOC sono calcolati su tutti i file analizzati, quindi si leggono solo a fine analisi;</li>
 *   <li>CK li accumula in oggetti globali ({@code CouplingExtras}, {@code NOCExtras}) che sopravvivono tra
 *       un'analisi e l'altra: vanno azzerati prima di ogni release. {@code CouplingExtras} non ha un metodo
 *       per farlo, quindi si azzera il suo campo statico via reflection.</li>
 * </ul>
 */
public final class CkAnalyzer {

    public record Result(List<OoMetrics> classes, List<String> filesWithErrors) {
    }

    /**
     * @param root  cartella con il codice della release (usata anche per risolvere i tipi)
     * @param files file da analizzare, relativi a {@code root}
     */
    public Result analyze(Path root, List<String> files) throws AnalysisException {
        resetGlobalState();
        Path base = root.toAbsolutePath().normalize();
        List<CKClassResult> raw = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        CKNotifier notifier = new CKNotifier() {
            @Override
            public void notify(CKClassResult result) {
                raw.add(result);
            }

            @Override
            public void notifyError(String sourceFilePath, Exception e) {
                errors.add(relative(base, sourceFilePath));
            }
        };
        Path[] paths = files.stream().map(base::resolve).toArray(Path[]::new);
        try {
            new CK(false, 0, false).calculate(base, notifier, paths);
        } catch (RuntimeException e) {
            throw new AnalysisException("CK ha interrotto l'analisi di " + base + ": " + e.getMessage(), e);
        }

        // solo ora Fan-In e NOC sono completi
        List<OoMetrics> out = new ArrayList<>(raw.size());
        for (CKClassResult r : raw) {
            float lcom = r.getLcomNormalized();
            double lcomStar = Float.isNaN(lcom) || lcom < 0 ? 0 : lcom;
            out.add(new OoMetrics(relative(base, r.getFile()), r.getClassName(), r.getType(),
                    r.getCbo(), r.getFanin(), r.getRfc(), lcomStar, r.getNoc()));
        }
        return new Result(out, List.copyOf(errors));
    }

    private static String relative(Path base, String file) {
        return base.relativize(Path.of(file).toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static void resetGlobalState() throws AnalysisException {
        NOCExtras.resetInstance();
        try {
            Field instance = CouplingExtras.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new AnalysisException("Impossibile azzerare lo stato di CK (CouplingExtras): " + e.getMessage(), e);
        }
    }
}
