package org.carecontinuum.service;

import ml.dmlc.xgboost4j.java.XGBoostError;
import org.carecontinuum.model.ModelRun;
import org.carecontinuum.model.PatientFeatures;
import org.carecontinuum.model.ValidationReport;
import org.carecontinuum.model.Visit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates loading, validation, aggregation, training, and persistence. */
@Service
public class CareContinuumService {
    private static final Logger log = LoggerFactory.getLogger(CareContinuumService.class);
    private final CsvDataService dataService;
    private final ValidationService validationService;
    private final FeatureEngineeringService featureEngineering;
    private final ModelService modelService;
    private final int seed;
    private final int syntheticCount;
    private final double defaultTestSize;
    private final int defaultFolds;

    public CareContinuumService(CsvDataService dataService, ValidationService validationService,
                                FeatureEngineeringService featureEngineering, ModelService modelService,
                                @Value("${carecontinuum.random-seed:42}") int seed,
                                @Value("${carecontinuum.synthetic-patient-count:2000}") int syntheticCount,
                                @Value("${carecontinuum.test-size:0.20}") double defaultTestSize,
                                @Value("${carecontinuum.cv-folds:5}") int defaultFolds) {
        this.dataService = dataService; this.validationService = validationService;
        this.featureEngineering = featureEngineering; this.modelService = modelService;
        this.seed = seed; this.syntheticCount = syntheticCount; this.defaultTestSize = defaultTestSize; this.defaultFolds = defaultFolds;
    }

    public void loadDemo(Workspace workspace) throws Exception {
        ClassPathResource resource = new ClassPathResource("data/synthetic/carecontinuum_demo.csv");
        CsvDataService.RawTable raw = dataService.read(resource.getInputStream());
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String column : DataCatalog.EXPECTED_COLUMNS) if (raw.headers().contains(column)) mapping.put(column, column);
        List<Visit> visits = dataService.map(raw, mapping);
        if (visits.isEmpty()) visits = new SyntheticDataGenerator().generate(syntheticCount, seed);
        activate(workspace, visits, SyntheticDataGenerator.DATASET_LABEL);
    }

    public void importUpload(Workspace workspace, Map<String, String> mapping) throws Exception {
        if (workspace.pendingUpload() == null) throw new IllegalStateException("Upload a CSV or Excel file before mapping columns.");
        Map<String, String> unique = new LinkedHashMap<>();
        mapping.forEach((canonical, source) -> {
            if (source != null && !source.isBlank() && !source.equals("— not mapped —")) unique.put(canonical, source);
        });
        long distinct = unique.values().stream().distinct().count();
        if (distinct != unique.size()) throw new IllegalArgumentException("Each uploaded source column can be mapped only once.");
        List<Visit> visits = dataService.map(workspace.pendingUpload(), unique);
        activate(workspace, visits, "Uploaded de-identified data");
        workspace.setPendingUpload(null);
    }

    public ModelRun train(Workspace workspace, String mode, double testSize, int folds, List<String> selectedFeatures) throws XGBoostError {
        if (!workspace.loaded()) throw new IllegalStateException("No dataset loaded.");
        ModelRun model = modelService.train(workspace.patients(), mode, testSize, folds, selectedFeatures);
        workspace.setModel(model);
        workspace.setModelMessage("Training, hold-out evaluation, and cross-validation completed.");
        return model;
    }

    public void saveModel(Workspace workspace) throws Exception {
        if (workspace.model() == null) throw new IllegalStateException("Model has not been trained yet.");
        modelService.save(workspace.model(), workspace.datasetLabel(), workspace.visits().size());
    }

    public void loadModel(Workspace workspace) throws Exception {
        workspace.setModel(modelService.loadSaved());
        workspace.setModelMessage("Saved XGBoost model and fitted preprocessing encoder loaded.");
    }

    public List<String> modelFeatures(Workspace workspace) {
        return workspace.loaded() ? modelService.defaultFeatureChoices(workspace.patients()) : List.of();
    }

    private void activate(Workspace workspace, List<Visit> visits, String label) throws Exception {
        if (visits.isEmpty()) throw new IllegalArgumentException("No visit rows were found in this dataset.");
        ValidationReport report = validationService.validate(visits);
        List<PatientFeatures> patients = featureEngineering.build(visits);
        workspace.setVisits(visits);
        workspace.setPatients(patients);
        workspace.setValidation(report);
        workspace.setDatasetLabel(label);
        workspace.setModel(null);
        workspace.setModelMessage("");
        log.info("Loaded dataset: {} rows, {} patients, {} providers", visits.size(), report.uniquePatients(), report.uniqueProviders());
        long labeled = patients.stream().filter(patient -> patient.target() != null).count();
        if (!report.missingColumns().contains("adverse_outcome (model target)") && labeled >= 30) {
            try {
                workspace.setModel(modelService.train(patients, "fast", defaultTestSize, defaultFolds, modelService.defaultFeatureChoices(patients)));
                workspace.setModelMessage("Model trained from this dataset. Metrics are dataset-specific research results.");
            } catch (Exception exception) {
                log.warn("Model training failed for loaded dataset: {}", exception.getMessage());
                workspace.setModelMessage("Model training is unavailable: " + exception.getMessage());
            }
        } else {
            workspace.setModelMessage(report.missingColumns().contains("adverse_outcome (model target)")
                    ? "No binary outcome was mapped. Continuity and exploration work; model training is unavailable."
                    : "At least 30 patients with consistent binary target labels are required to train.");
        }
    }
}
