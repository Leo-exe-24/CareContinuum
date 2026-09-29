package org.carecontinuum.model;

/** Transparent potential continuity-gap signal; never a diagnosis. */
public record GapAlert(String code, String reason, String supportingMetric, String severity, String suggestedAction) {}
