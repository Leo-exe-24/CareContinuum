package org.carecontinuum.service;

import org.carecontinuum.model.PatientFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureEngineeringServiceTest {
    @Test
    void producesOneLongitudinalFeatureRowPerSyntheticPatient() {
        List<org.carecontinuum.model.Visit> visits = new SyntheticDataGenerator().generate(80, 42);
        List<PatientFeatures> patients = new FeatureEngineeringService(new ContinuityService()).build(visits);

        assertEquals(80, patients.size());
        assertTrue(patients.stream().allMatch(patient -> patient.value("total_visits") instanceof Number n && n.intValue() >= 2 && n.intValue() <= 15));
        assertTrue(patients.stream().allMatch(patient -> patient.target() != null));
        PatientFeatures first = patients.stream().filter(patient -> patient.patientId().equals("DEMO-001")).findFirst().orElseThrow();
        assertEquals(6, ((Number) first.value("total_visits")).intValue());
        assertEquals(1.0, (Double) first.value("coc_score"), 1e-9);
        assertNotNull(first.value("average_days_between_visits"));
    }
}
