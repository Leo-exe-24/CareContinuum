package org.carecontinuum.model;

import ml.dmlc.xgboost4j.java.Booster;
import org.carecontinuum.service.FeatureEncoder;

import java.time.Instant;
import java.util.List;

/** Fitted XGBoost model paired with its train-only preprocessing encoder. */
public record ModelRun(
        Booster booster,
        FeatureEncoder encoder,
        List<String> inputFeatures,
        ModelEvaluation evaluation,
        Instant trainedAt
) {}
