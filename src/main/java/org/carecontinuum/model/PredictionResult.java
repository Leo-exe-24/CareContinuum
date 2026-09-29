package org.carecontinuum.model;

import java.util.List;

/** Synthetic research probability with local model contributions. */
public record PredictionResult(double probability, String category, List<Contribution> contributions) {
    public record Contribution(String feature, double value, String direction) {}
}
