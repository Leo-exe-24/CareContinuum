package org.carecontinuum.model;

import java.util.Map;

/** Hold-out metrics, cross-validation summary, and actual run configuration. */
public record ModelEvaluation(
        double accuracy,
        double precision,
        double recall,
        double f1,
        double rocAuc,
        double prAuc,
        int[][] confusionMatrix,
        java.util.List<java.util.List<Double>> rocCurve,
        java.util.List<java.util.List<Double>> prCurve,
        int folds,
        double meanCvRocAuc,
        double stdCvRocAuc,
        double meanCvF1,
        double meanCvRecall,
        int positiveCases,
        int negativeCases,
        int trainingPatients,
        int testPatients,
        String mode,
        Map<String, Object> parameters
) {}
