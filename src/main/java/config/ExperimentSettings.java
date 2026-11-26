package config;

import exception.ConfigException;

import java.util.List;

/**
 * Parametri degli esperimenti di classificazione.
 *
 * @param repetitions          ripetizioni della cross-validation (10)
 * @param folds                fold della cross-validation (10)
 * @param holdoutTrainFraction frazione di righe desiderata nel training dell'holdout ordinato (0,8): il taglio
 *                             cade al confine tra due release, quello che ci si avvicina di piu'
 * @param validationFraction   frazione del training usata come validation set (0,2): nell'holdout con lo stesso
 *                             taglio per release, nella CV con uno split stratificato
 * @param kValues              valori di k provati per IBk
 * @param bootstrapSamples     ricampionamenti del bootstrap appaiato (holdout)
 * @param seed                 seme di base di tutte le scelte casuali
 * @param threads              fold della CV eseguiti in parallelo
 * @param runHoldout           se eseguire l'ordered holdout
 * @param runCrossValidation   se eseguire la 10x10 cross-validation
 */
public record ExperimentSettings(int repetitions, int folds, double holdoutTrainFraction, double validationFraction,
                                 List<Integer> kValues, int bootstrapSamples, int seed, int threads,
                                 boolean runHoldout, boolean runCrossValidation) {

    public ExperimentSettings {
        kValues = List.copyOf(kValues);
    }

    /** Stessi parametri, con entrambe le validazioni. */
    public ExperimentSettings(int repetitions, int folds, double holdoutTrainFraction, double validationFraction,
                              List<Integer> kValues, int bootstrapSamples, int seed, int threads) {
        this(repetitions, folds, holdoutTrainFraction, validationFraction, kValues, bootstrapSamples, seed, threads,
                true, true);
    }

    /** Controlla che i parametri siano coerenti. */
    public ExperimentSettings validate() throws ConfigException {
        if (repetitions < 1 || folds < 2) {
            throw new ConfigException("ml.cv.repetitions deve essere >= 1 e ml.cv.folds >= 2");
        }
        if (holdoutTrainFraction <= 0 || holdoutTrainFraction >= 1) {
            throw new ConfigException("ml.holdout.train.fraction deve essere tra 0 e 1: " + holdoutTrainFraction);
        }
        if (validationFraction <= 0 || validationFraction >= 0.5) {
            throw new ConfigException("ml.validation.fraction deve essere tra 0 e 0,5: " + validationFraction);
        }
        if (kValues.isEmpty() || kValues.stream().anyMatch(k -> k < 1)) {
            throw new ConfigException("ml.ibk.k deve contenere almeno un intero positivo: " + kValues);
        }
        if (bootstrapSamples < 100) {
            throw new ConfigException("ml.bootstrap.samples deve essere almeno 100: " + bootstrapSamples);
        }
        if (!runHoldout && !runCrossValidation) {
            throw new ConfigException("ml.validations deve contenere holdout, cv o entrambi");
        }
        if (threads < 1) {
            throw new ConfigException("ml.threads deve essere positivo: " + threads);
        }
        return this;
    }

    /** Numero di fold dello split interno della CV: 1/0,2 = 5, di cui uno fa da validation set. */
    public int innerFolds() {
        return (int) Math.round(1 / validationFraction);
    }
}