package org.carecontinuum.service;

import org.carecontinuum.model.Visit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvDataServiceTest {
    private final CsvDataService service = new CsvDataService();

    @Test
    void readsCsvAndMapsSourceHeadersExplicitly() throws Exception {
        String csv = "\uFEFFPerson,Encounter Date,Clinician,Status\nP-1,2024-01-01,DR-2,1\n";
        CsvDataService.RawTable raw = service.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        List<Visit> visits = service.map(raw, Map.of("patient_id", "Person", "visit_date", "Encounter Date", "provider_id", "Clinician", "adverse_outcome", "Status"));

        assertEquals(1, visits.size());
        assertEquals("P-1", visits.getFirst().patientId());
        assertEquals("DR-2", visits.getFirst().providerId());
        assertEquals("2024-01-01", visits.getFirst().visitDate().toString());
        assertEquals(4, visits.getFirst().values().size());
    }

    @Test
    void exportsFormulaLikeTextAsInertText() throws Exception {
        byte[] csv = service.writeCsv(List.of("value"), List.of(List.of("=HYPERLINK(\"https://example.invalid\")")));
        String output = new String(csv, StandardCharsets.UTF_8);

        assertTrue(output.contains("'=HYPERLINK"));
    }
}
