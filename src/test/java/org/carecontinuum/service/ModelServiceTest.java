package org.carecontinuum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.carecontinuum.model.ModelRun;
import org.carecontinuum.model.PatientFeatures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelServiceTest {
    @Test
    void trainsScoresAndExplainsAResearchOutcome() throws Exception {
        List<org.carecontinuum.model.Visit> visits = new SyntheticDataGenerator().generate(100, 420);
        List<PatientFeatures> patients = new FeatureEngineeringService(new ContinuityService()).build(visits);
        ModelService service = new ModelService(new ObjectMapper(), Path.of("target", "test-models").toString(), 42, 0.2, 2, 0.33, 0.66, 4, 0.05, 40, 0.85, 0.85, 2);

        ModelRun run = service.train(patients, "fast", 0.2, 2, List.of("age", "chronic_condition_count", "provider_fragmentation"));
        var prediction = service.predict(run, patients.getFirst());

        assertEquals(100, run.evaluation().trainingPatients() + run.evaluation().testPatients());
        assertTrue(run.evaluation().testPatients() >= 18 && run.evaluation().testPatients() <= 22);
        assertTrue(prediction.probability() >= 0 && prediction.probability() <= 1);
        assertNotNull(prediction.category());
        assertTrue(!prediction.contributions().isEmpty());
        assertEquals(1.0, run.evaluation().rocCurve().getLast().getFirst(), 1e-9);
    }
}
