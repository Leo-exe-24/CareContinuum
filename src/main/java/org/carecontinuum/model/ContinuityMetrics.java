package org.carecontinuum.model;

/** Provider concentration and sequential continuity metrics for one patient. */
public record ContinuityMetrics(
        String patientId,
        int totalVisits,
        int validProviderVisits,
        int uniqueProviders,
        Double cocScore,
        Double usualProviderShare,
        Double providerFragmentation,
        Double sequentialContinuity
) {}
