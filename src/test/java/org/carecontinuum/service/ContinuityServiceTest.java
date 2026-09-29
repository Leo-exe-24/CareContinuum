package org.carecontinuum.service;

import org.carecontinuum.model.ContinuityMetrics;
import org.carecontinuum.model.Visit;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContinuityServiceTest {
    private final ContinuityService service = new ContinuityService();

    @Test
    void computesBiceBoxermanUpcAndSequentialContinuity() {
        List<Visit> visits = List.of(visit("2024-01-01", "A"), visit("2024-01-02", "A"), visit("2024-01-03", "B"), visit("2024-01-04", "B"), visit("2024-01-05", "B"));

        ContinuityMetrics metrics = service.calculatePatient("P1", visits);

        assertEquals(0.4, metrics.cocScore(), 1e-9);
        assertEquals(0.6, metrics.usualProviderShare(), 1e-9);
        assertEquals(0.75, metrics.sequentialContinuity(), 1e-9);
        assertEquals(2, metrics.uniqueProviders());
        assertEquals("Moderate continuity", service.classify(metrics.cocScore(), 0.3, 0.7));
    }

    @Test
    void leavesCocAndSequentialContinuityUndefinedWithOneValidVisit() {
        ContinuityMetrics metrics = service.calculatePatient("P2", List.of(visit("2024-01-01", "A")));

        assertFalse(Double.isFinite(metrics.cocScore()));
        assertFalse(Double.isFinite(metrics.sequentialContinuity()));
        assertEquals("Not calculable", service.classify(metrics.cocScore(), 0.3, 0.7));
    }

    @Test
    void acceptsUsSlashDatesForUploadedVisits() {
        assertEquals("2024-02-03", visit("2/3/2024", "A").visitDate().toString());
    }

    private static Visit visit(String date, String provider) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("patient_id", "P1"); row.put("visit_id", date); row.put("visit_date", date); row.put("provider_id", provider);
        return new Visit(row);
    }
}
