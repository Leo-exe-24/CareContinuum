package org.carecontinuum.web;

import ml.dmlc.xgboost4j.java.XGBoostError;
import org.carecontinuum.model.GapAlert;
import org.carecontinuum.model.ModelEvaluation;
import org.carecontinuum.model.PatientFeatures;
import org.carecontinuum.model.PredictionResult;
import org.carecontinuum.model.Visit;
import org.carecontinuum.service.CareContinuumService;
import org.carecontinuum.service.ContinuityService;
import org.carecontinuum.service.CsvDataService;
import org.carecontinuum.service.DataCatalog;
import org.carecontinuum.service.GapDetectionService;
import org.carecontinuum.service.ModelService;
import org.carecontinuum.service.ReportService;
import org.carecontinuum.service.Workspace;
import org.carecontinuum.model.ValidationReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Local web UI routes for the CareContinuum workflow. */
@Controller
public class DashboardController {
    private final Workspace workspace;
    private final CareContinuumService careService;
    private final CsvDataService dataService;
    private final ModelService modelService;
    private final ContinuityService continuityService;
    private final GapDetectionService gapService;
    private final ReportService reportService;
    private final Path reportDirectory;
    private final double cocLow;
    private final double cocHigh;
    private final double riskLow;
    private final double riskHigh;
    private final int longGapDays;
    private final double defaultTestSize;
    private final int defaultFolds;
    private final int randomSeed;

    public DashboardController(Workspace workspace, CareContinuumService careService, CsvDataService dataService,
                               ModelService modelService, ContinuityService continuityService,
                               GapDetectionService gapService, ReportService reportService,
                               @Value("${carecontinuum.report-dir:reports}") String reportDirectory,
                               @Value("${carecontinuum.coc-low-threshold:0.30}") double cocLow,
                               @Value("${carecontinuum.coc-high-threshold:0.70}") double cocHigh,
                               @Value("${carecontinuum.risk-low-threshold:0.33}") double riskLow,
                               @Value("${carecontinuum.risk-high-threshold:0.66}") double riskHigh,
                               @Value("${carecontinuum.gap.long-followup-days:60}") int longGapDays,
                               @Value("${carecontinuum.test-size:0.20}") double defaultTestSize,
                               @Value("${carecontinuum.cv-folds:5}") int defaultFolds,
                               @Value("${carecontinuum.random-seed:42}") int randomSeed) {
        this.workspace = workspace; this.careService = careService; this.dataService = dataService;
        this.modelService = modelService; this.continuityService = continuityService; this.gapService = gapService;
        this.reportService = reportService;
        this.reportDirectory = Path.of(reportDirectory); this.cocLow = cocLow; this.cocHigh = cocHigh;
        this.riskLow = riskLow; this.riskHigh = riskHigh; this.longGapDays = longGapDays;
        this.defaultTestSize = defaultTestSize; this.defaultFolds = defaultFolds;
        this.randomSeed = randomSeed;
    }

    @GetMapping({"/", "/home"})
    public String home(Model model) { base(model, "home"); return "dashboard"; }

    @PostMapping("/demo/load")
    public String loadDemo(RedirectAttributes redirect) {
        try { careService.loadDemo(workspace); redirect.addFlashAttribute("success", "Synthetic demo loaded, validated, and prepared."); }
        catch (Exception exception) { redirect.addFlashAttribute("error", safeMessage(exception)); }
        return "redirect:/overview";
    }

    @GetMapping("/overview")
    public String overview(Model model) throws XGBoostError {
        base(model, "overview");
        if (!workspace.loaded()) return "redirect:/";
        List<PatientFeatures> patients = workspace.patients();
        List<Double> coc = values(patients, "coc_score");
        List<Double> fragmentation = values(patients, "provider_fragmentation");
        model.addAttribute("patientCount", patients.size());
        model.addAttribute("visitCount", workspace.visits().size());
        model.addAttribute("providerCount", workspace.validation().uniqueProviders());
        model.addAttribute("averageCoc", average(coc));
        model.addAttribute("medianCoc", median(coc));
        model.addAttribute("averageProviders", average(values(patients, "unique_providers")));
        model.addAttribute("averageEmergency", average(values(patients, "emergency_visits")));
        model.addAttribute("cocValues", coc);
        model.addAttribute("fragmentationValues", fragmentation);
        model.addAttribute("utilizationAverages", List.of(average(values(patients, "emergency_visits")), average(values(patients, "outpatient_visits")), average(values(patients, "previous_hospitalizations"))));
        model.addAttribute("ageValues", values(patients, "age"));
        model.addAttribute("outcomeCounts", List.of((int) patients.stream().filter(row -> Integer.valueOf(0).equals(row.target())).count(), (int) patients.stream().filter(row -> Integer.valueOf(1).equals(row.target())).count()));
        if (workspace.modelReady()) {
            ModelEvaluation evaluation = workspace.model().evaluation();
            model.addAttribute("evaluation", evaluation);
            Map<String, Double> probabilities = modelService.scoreCohort(workspace.model(), patients);
            model.addAttribute("riskValues", patients.stream().filter(row -> probabilities.containsKey(row.patientId())).map(row -> Map.of("patientId", row.patientId(), "coc", num(row.value("coc_score")), "risk", probabilities.get(row.patientId()), "outcome", row.target() == null ? -1 : row.target())).toList());
        }
        return "dashboard";
    }

    @GetMapping("/patients")
    public String patients(@RequestParam(name = "patientId", required = false) String patientId, Model model) throws XGBoostError {
        base(model, "patients");
        if (!workspace.loaded()) return "redirect:/";
        if (patientId != null && !patientId.isBlank()) {
            Optional<PatientFeatures> selected = patient(patientId);
            if (selected.isPresent()) {
                PatientFeatures patient = selected.get();
                model.addAttribute("selectedPatient", patient);
                model.addAttribute("patientVisits", visits(patientId));
                model.addAttribute("patientTimeline", timeline(visits(patientId)));
                model.addAttribute("patientGaps", gapService.detect(patient));
                model.addAttribute("continuityCategory", continuityService.classify(doubleValue(patient.value("coc_score")), cocLow, cocHigh));
                if (workspace.modelReady()) model.addAttribute("prediction", modelService.predict(workspace.model(), patient));
                model.addAttribute("cocTrend", cocTrend(visits(patientId)));
            }
        }
        return "dashboard";
    }

    @GetMapping("/continuity")
    public String continuity(@RequestParam(name = "patientId", required = false) String patientId, Model model) {
        base(model, "continuity");
        if (!workspace.loaded()) return "redirect:/";
        model.addAttribute("cocValues", values(workspace.patients(), "coc_score"));
        model.addAttribute("providerValues", values(workspace.patients(), "unique_providers"));
        model.addAttribute("switchValues", values(workspace.patients(), "provider_switch_count"));
        model.addAttribute("continuitySummary", continuitySummary());
        List<PatientFeatures> calculable = workspace.patients().stream().filter(row -> doubleValue(row.value("coc_score")) != null).toList();
        model.addAttribute("calculablePatientCount", calculable.size());
        model.addAttribute("calculableMeanCoc", average(values(calculable, "coc_score")));
        if (patientId != null && !patientId.isBlank()) {
            model.addAttribute("networkPatientId", patientId);
            model.addAttribute("networkVisits", visits(patientId));
            model.addAttribute("providerCounts", visits(patientId).stream().map(Visit::providerId).filter(value -> !value.isBlank()).collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting())));
        }
        return "dashboard";
    }

    @GetMapping("/risk")
    public String risk(@RequestParam(name = "patientId", required = false) String patientId, Model model) throws XGBoostError {
        base(model, "risk");
        if (!workspace.loaded()) return "redirect:/";
        model.addAttribute("manualFeatureNames", workspace.modelReady() ? workspace.model().inputFeatures() : modelService.defaultFeatureChoices(workspace.patients()));
        model.addAttribute("manualDefaults", manualDefaults());
        if (patientId != null && workspace.modelReady()) {
            PatientFeatures patient = patient(patientId).orElse(null);
            if (patient != null) {
                model.addAttribute("riskPatient", patient);
                model.addAttribute("prediction", modelService.predict(workspace.model(), patient));
                model.addAttribute("riskGaps", gapService.detect(patient));
            }
        }
        return "dashboard";
    }

    @PostMapping("/risk/manual")
    public String manualRisk(@RequestParam MultiValueMap<String, String> params, Model model) throws XGBoostError {
        base(model, "risk");
        if (!workspace.modelReady()) { model.addAttribute("error", "Model has not been trained yet."); return "dashboard"; }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String feature : workspace.model().inputFeatures()) {
            String raw = params.getFirst("f_" + feature);
            Object parsed = raw;
            if (DataCatalog.NUMERIC_FEATURES.contains(feature)) {
                try { parsed = Double.valueOf(raw); } catch (RuntimeException ignored) { parsed = null; }
            }
            values.put(feature, parsed);
        }
        PatientFeatures manual = new PatientFeatures("MANUAL-RESEARCH-INPUT", values, null);
        model.addAttribute("manualFeatureNames", workspace.model().inputFeatures());
        model.addAttribute("manualDefaults", values);
        model.addAttribute("manualPrediction", modelService.predict(workspace.model(), manual));
        model.addAttribute("predictionDisclaimer", "This prediction is generated by a research prototype and must not be used as a medical diagnosis or as a substitute for professional clinical judgment.");
        return "dashboard";
    }

    @GetMapping("/model")
    public String model(Model model) throws XGBoostError {
        base(model, "model");
        if (!workspace.loaded()) return "redirect:/";
        model.addAttribute("featureChoices", modelService.defaultFeatureChoices(workspace.patients()));
        model.addAttribute("selectedFeatures", workspace.modelReady() ? workspace.model().inputFeatures() : modelService.defaultFeatureChoices(workspace.patients()));
        model.addAttribute("defaultTestSize", defaultTestSize);
        model.addAttribute("defaultFolds", defaultFolds);
        model.addAttribute("classCounts", List.of((int) workspace.patients().stream().filter(row -> Integer.valueOf(0).equals(row.target())).count(), (int) workspace.patients().stream().filter(row -> Integer.valueOf(1).equals(row.target())).count()));
        if (workspace.modelReady()) {
            model.addAttribute("evaluation", workspace.model().evaluation());
            model.addAttribute("featureImportance", modelService.globalImportance(workspace.model(), workspace.patients(), 250));
        }
        return "dashboard";
    }

    @PostMapping("/model/train")
    public String train(@RequestParam(name = "mode", defaultValue = "fast") String mode,
                        @RequestParam(name = "testSize", required = false) Double testSize,
                        @RequestParam(name = "folds", required = false) Integer folds,
                        @RequestParam(name = "features", required = false) List<String> features,
                        RedirectAttributes redirect) {
        try {
            if (features == null || features.isEmpty()) throw new IllegalArgumentException("Select at least one model input feature.");
            careService.train(workspace, mode, testSize == null ? defaultTestSize : testSize, folds == null ? defaultFolds : folds, features);
            redirect.addFlashAttribute("success", "Model trained, evaluated, and cross-validated at patient level.");
        } catch (Exception exception) { redirect.addFlashAttribute("error", safeMessage(exception)); }
        return "redirect:/model";
    }

    @PostMapping("/model/save")
    public String saveModel(RedirectAttributes redirect) {
        try { careService.saveModel(workspace); redirect.addFlashAttribute("success", "Model, fitted preprocessor, and JSON metadata saved under models/."); }
        catch (Exception exception) { redirect.addFlashAttribute("error", safeMessage(exception)); }
        return "redirect:/model";
    }

    @PostMapping("/model/load")
    public String loadModel(RedirectAttributes redirect) {
        try { careService.loadModel(workspace); redirect.addFlashAttribute("success", "Saved model and preprocessing encoder loaded."); }
        catch (Exception exception) { redirect.addFlashAttribute("error", safeMessage(exception)); }
        return "redirect:/model";
    }

    @GetMapping("/explain")
    public String explain(@RequestParam(name = "patientId", required = false) String patientId, Model model) throws XGBoostError {
        base(model, "explain");
        if (!workspace.loaded()) return "redirect:/";
        if (workspace.modelReady()) {
            model.addAttribute("featureImportance", modelService.globalImportance(workspace.model(), workspace.patients(), 250));
            if (patientId != null) {
                PatientFeatures patient = patient(patientId).orElse(null);
                if (patient != null) {
                    model.addAttribute("explainPatient", patient);
                    model.addAttribute("explainPrediction", modelService.predict(workspace.model(), patient));
                }
            }
        }
        return "dashboard";
    }

    @GetMapping("/data")
    public String data(@RequestParam(name = "search", required = false) String search,
                       @RequestParam(name = "sort", required = false) String sort,
                       Model model) {
        base(model, "data");
        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("sort", sort == null ? "visit_date" : sort);
        if (workspace.pendingUpload() != null) {
            model.addAttribute("uploadHeaders", workspace.pendingUpload().headers());
            model.addAttribute("mappingDefaults", defaultMappings(workspace.pendingUpload().headers()));
        }
        if (workspace.loaded()) {
            List<Map<String, String>> rows = workspace.visits().stream().map(Visit::values)
                    .filter(row -> search == null || search.isBlank() || row.values().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))))
                    .sorted(sortComparator(sort)).limit(500).toList();
            model.addAttribute("visitRows", rows);
            model.addAttribute("visitHeaders", DataCatalog.EXPECTED_COLUMNS);
            model.addAttribute("patientFeatureRows", workspace.patients().stream().limit(25).toList());
        }
        return "dashboard";
    }

    @PostMapping("/data/upload")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        try {
            if (file.isEmpty()) throw new IllegalArgumentException("Choose a CSV or XLSX file first.");
            CsvDataService.RawTable raw = dataService.read(file);
            workspace.setPendingUpload(raw);
            redirect.addFlashAttribute("success", "Upload preview is ready. Map columns before importing.");
        } catch (Exception exception) { redirect.addFlashAttribute("error", safeMessage(exception)); }
        return "redirect:/data";
    }

    @PostMapping("/data/import")
    public String importData(@RequestParam MultiValueMap<String, String> params, RedirectAttributes redirect) {
        try {
            Map<String, String> mapping = new LinkedHashMap<>();
            params.forEach((key, values) -> { if (key.startsWith("map_")) mapping.put(key.substring(4), values.isEmpty() ? "" : values.getFirst()); });
            careService.importUpload(workspace, mapping);
            redirect.addFlashAttribute("success", "Uploaded data validated and loaded into this browser session.");
        } catch (Exception exception) { redirect.addFlashAttribute("error", safeMessage(exception)); }
        return "redirect:/data";
    }

    @GetMapping("/reports")
    public String reports(Model model) { base(model, "reports"); return workspace.loaded() ? "dashboard" : "redirect:/"; }

    @GetMapping("/settings")
    public String settings(Model model) {
        base(model, "settings");
        model.addAttribute("cocLow", cocLow); model.addAttribute("cocHigh", cocHigh);
        model.addAttribute("riskLow", riskLow); model.addAttribute("riskHigh", riskHigh);
        model.addAttribute("defaultTestSize", defaultTestSize); model.addAttribute("defaultFolds", defaultFolds); model.addAttribute("randomSeed", randomSeed);
        return "dashboard";
    }

    @GetMapping("/about")
    public String about(Model model) { base(model, "about"); return "dashboard"; }

    @GetMapping("/reports/patient/{patientId}.pdf")
    public ResponseEntity<byte[]> patientPdf(@PathVariable("patientId") String patientId) throws Exception {
        PatientFeatures patient = patient(patientId).orElseThrow(() -> new IllegalArgumentException("Patient ID is not present in the loaded dataset."));
        PredictionResult prediction = workspace.modelReady() ? modelService.predict(workspace.model(), patient) : null;
        byte[] bytes = reportService.patientReport(patient, visits(patientId), prediction, gapService.detect(patient), workspace.datasetLabel());
        writeReport("carecontinuum_" + safeFilename(patientId) + "_report.pdf", bytes);
        return attachment(bytes, "carecontinuum_" + safeFilename(patientId) + "_report.pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/reports/model.pdf")
    public ResponseEntity<byte[]> modelPdf() throws Exception {
        if (!workspace.modelReady() || workspace.model().evaluation() == null) throw new IllegalStateException("Model has not been trained yet.");
        byte[] bytes = reportService.modelReport(workspace.model().evaluation(), workspace.model().inputFeatures(), workspace.datasetLabel(), workspace.patients().size(), workspace.visits().size());
        writeReport("carecontinuum_model_evaluation.pdf", bytes);
        return attachment(bytes, "carecontinuum_model_evaluation.pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/exports/validation.csv")
    public ResponseEntity<byte[]> validationCsv() throws Exception {
        ValidationReport report = workspace.validation();
        if (report == null) throw new IllegalStateException("No dataset loaded.");
        List<List<?>> rows = new ArrayList<>();
        for (Map<String, Object> issue : report.issues()) rows.add(List.of(issue.getOrDefault("row_index", ""), issue.getOrDefault("issues", "")));
        return csv(dataService.writeCsv(List.of("row_index", "issues"), rows), "carecontinuum_validation_report.csv");
    }

    @GetMapping("/exports/features.csv")
    public ResponseEntity<byte[]> featuresCsv() throws Exception {
        List<String> features = modelService.defaultFeatureChoices(workspace.patients());
        List<String> headers = new ArrayList<>(List.of("patient_id")); headers.addAll(features); headers.add("adverse_outcome");
        List<List<?>> rows = new ArrayList<>();
        for (PatientFeatures patient : workspace.patients()) {
            List<Object> row = new ArrayList<>(); row.add(patient.patientId()); features.forEach(feature -> row.add(patient.value(feature))); row.add(patient.target()); rows.add(row);
        }
        return csv(dataService.writeCsv(headers, rows), "carecontinuum_patient_features.csv");
    }

    @GetMapping("/exports/continuity.csv")
    public ResponseEntity<byte[]> continuityCsv() throws Exception {
        List<String> headers = List.of("patient_id", "total_visits", "valid_provider_visits", "unique_providers", "coc_score", "usual_provider_share", "provider_fragmentation", "sequential_continuity", "provider_switch_count");
        List<List<?>> rows = new ArrayList<>();
        for (PatientFeatures patient : workspace.patients()) {
            Map<String, Object> values = patient.values();
            rows.add(List.of(patient.patientId(), values.getOrDefault("total_visits", ""), values.getOrDefault("valid_provider_visits", ""), values.getOrDefault("unique_providers", ""), values.getOrDefault("coc_score", ""), values.getOrDefault("usual_provider_share", ""), values.getOrDefault("provider_fragmentation", ""), values.getOrDefault("sequential_continuity", ""), values.getOrDefault("provider_switch_count", "")));
        }
        return csv(dataService.writeCsv(headers, rows), "carecontinuum_coc_results.csv");
    }

    @GetMapping("/exports/predictions.csv")
    public ResponseEntity<byte[]> predictionsCsv() throws Exception {
        if (!workspace.modelReady()) throw new IllegalStateException("Model has not been trained yet.");
        Map<String, Double> scores = modelService.scoreCohort(workspace.model(), workspace.patients());
        List<List<?>> rows = new ArrayList<>();
        for (PatientFeatures patient : workspace.patients()) {
            Double score = scores.get(patient.patientId());
            String category = score == null ? "Unavailable" : score < riskLow ? "Lower predicted risk" : score <= riskHigh ? "Intermediate predicted risk" : "Higher predicted risk";
            rows.add(List.of(patient.patientId(), patient.target() == null ? "" : patient.target(), score == null ? "" : score, category, patient.value("coc_score") == null ? "" : patient.value("coc_score")));
        }
        return csv(dataService.writeCsv(List.of("patient_id", "research_outcome", "predicted_probability", "prototype_category", "coc_score"), rows), "carecontinuum_predictions.csv");
    }

    @GetMapping("/exports/gaps.csv")
    public ResponseEntity<byte[]> gapsCsv() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        for (PatientFeatures patient : workspace.patients()) for (GapAlert gap : gapService.detect(patient)) rows.add(List.of(patient.patientId(), gap.code(), gap.reason(), gap.supportingMetric(), gap.severity(), gap.suggestedAction()));
        return csv(dataService.writeCsv(List.of("patient_id", "code", "reason", "supporting_metric", "severity", "suggested_action"), rows), "carecontinuum_continuity_gaps.csv");
    }

    @GetMapping("/exports/metrics.csv")
    public ResponseEntity<byte[]> metricsCsv() throws Exception {
        ModelEvaluation evaluation = workspace.model() == null ? null : workspace.model().evaluation();
        if (evaluation == null) throw new IllegalStateException("Model evaluation metrics are unavailable.");
        List<String> headers = List.of("accuracy", "precision", "recall", "f1", "roc_auc", "pr_auc", "cv_folds", "cv_mean_roc_auc", "cv_std_roc_auc", "cv_mean_f1", "cv_mean_recall");
        List<?> row = List.of(evaluation.accuracy(), evaluation.precision(), evaluation.recall(), evaluation.f1(), evaluation.rocAuc(), evaluation.prAuc(), evaluation.folds(), evaluation.meanCvRocAuc(), evaluation.stdCvRocAuc(), evaluation.meanCvF1(), evaluation.meanCvRecall());
        return csv(dataService.writeCsv(headers, List.of(row)), "carecontinuum_model_metrics.csv");
    }

    private void base(Model model, String page) {
        model.addAttribute("page", page);
        model.addAttribute("pageTitle", switch (page) {
            case "overview" -> "Overview";
            case "patients" -> "Patient Explorer";
            case "continuity" -> "Continuity Analysis";
            case "risk" -> "AI Risk Prediction";
            case "model" -> "Model Training";
            case "explain" -> "Explainable AI";
            case "data" -> "Data Explorer";
            case "reports" -> "Reports";
            case "settings" -> "Settings";
            case "about" -> "About";
            default -> "Home";
        });
        model.addAttribute("workspace", workspace);
        model.addAttribute("navItems", List.of(
                new NavItem("home", "Home", "/"), new NavItem("overview", "Overview", "/overview"),
                new NavItem("patients", "Patient Explorer", "/patients"), new NavItem("continuity", "Continuity Analysis", "/continuity"),
                new NavItem("risk", "AI Risk Prediction", "/risk"), new NavItem("model", "Model Training", "/model"),
                new NavItem("explain", "Explainable AI", "/explain"), new NavItem("data", "Data Explorer", "/data"),
                new NavItem("reports", "Reports", "/reports"), new NavItem("settings", "Settings", "/settings"),
                new NavItem("about", "About", "/about")));
        model.addAttribute("allPatients", workspace.patients());
        model.addAttribute("continuityDisclaimer", "Prototype visualization thresholds — not clinical guidelines.");
        model.addAttribute("prototypeDisclaimer", "Research Prototype — Not a Medical Diagnosis");
        model.addAttribute("longGapDays", longGapDays);
    }

    private Optional<PatientFeatures> patient(String id) { return workspace.patients().stream().filter(row -> row.patientId().equals(id)).findFirst(); }
    private List<Visit> visits(String id) { return workspace.visits().stream().filter(visit -> visit.patientId().equals(id)).sorted(Comparator.comparing(Visit::visitDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList(); }
    private List<Map<String, Object>> continuitySummary() {
        return List.of("Low continuity", "Moderate continuity", "High continuity", "Not calculable").stream().map(category -> {
            List<PatientFeatures> members = workspace.patients().stream().filter(row -> continuityService.classify(doubleValue(row.value("coc_score")), cocLow, cocHigh).equals(category)).toList();
            return Map.<String, Object>of("category", category, "patients", members.size(), "meanCoc", average(values(members, "coc_score")), "meanProviders", average(values(members, "unique_providers")));
        }).toList();
    }

    private List<Map<String, Object>> cocTrend(List<Visit> visits) {
        List<Visit> ordered = visits.stream().filter(visit -> visit.visitDate() != null).sorted(Comparator.comparing(Visit::visitDate)).toList();
        if (ordered.size() < 4) return List.of();
        LocalDate start = ordered.getFirst().visitDate(), end = ordered.getLast().visitDate();
        List<Map<String, Object>> trend = new ArrayList<>();
        while (!start.isAfter(end)) {
            LocalDate windowStart = start, windowEnd = start.plusDays(180);
            double coc = continuityService.calculateCoc(ordered.stream().filter(visit -> !visit.visitDate().isBefore(windowStart) && visit.visitDate().isBefore(windowEnd)).toList());
            if (Double.isFinite(coc)) trend.add(Map.of("windowStart", windowStart.toString(), "coc", coc));
            start = windowEnd;
        }
        return trend;
    }

    private List<TimelineRow> timeline(List<Visit> visits) {
        List<Visit> ordered = visits.stream().sorted(Comparator.comparing(Visit::visitDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        List<TimelineRow> result = new ArrayList<>();
        Visit previous = null;
        for (Visit visit : ordered) {
            Long daysSincePrevious = previous == null || previous.visitDate() == null || visit.visitDate() == null ? null : ChronoUnit.DAYS.between(previous.visitDate(), visit.visitDate());
            boolean providerSwitch = previous != null && !visit.providerId().isBlank() && !previous.providerId().isBlank() && !visit.providerId().equals(previous.providerId());
            boolean longGap = daysSincePrevious != null && daysSincePrevious >= longGapDays;
            boolean emergency = visit.visitType().toLowerCase(Locale.ROOT).contains("emergency");
            String event = emergency ? "Emergency encounter" : longGap ? "Long visit interval" : providerSwitch ? "Provider change" : "Routine encounter";
            result.add(new TimelineRow(visit.get("visit_date"), visit.providerId().isBlank() ? "Missing" : visit.providerId(), visit.get("provider_specialty"), visit.get("facility_id"), visit.visitType(), event, providerSwitch, longGap, emergency, daysSincePrevious));
            previous = visit;
        }
        return result;
    }

    private Map<String, String> defaultMappings(List<String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, List<String>> aliases = Map.of(
                "patient_id", List.of("patient_id", "patient id", "Patient ID"),
                "visit_id", List.of("visit_id", "visit id", "Visit ID"),
                "visit_date", List.of("visit_date", "visit date", "Visit Date", "date"),
                "provider_id", List.of("provider_id", "provider id", "Provider ID"),
                "adverse_outcome", List.of("adverse_outcome", "outcome", "Outcome", "target"));
        for (String canonical : DataCatalog.EXPECTED_COLUMNS) {
            List<String> candidates = aliases.getOrDefault(canonical, List.of(canonical));
            headers.stream().filter(header -> candidates.stream().anyMatch(value -> value.equalsIgnoreCase(header))).findFirst().ifPresent(source -> result.put(canonical, source));
        }
        return result;
    }

    private Map<String, Object> manualDefaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String feature : modelService.defaultFeatureChoices(workspace.patients())) {
            List<Double> numbers = workspace.patients().stream().map(row -> doubleValue(row.value(feature))).filter(value -> value != null).sorted().toList();
            if (DataCatalog.NUMERIC_FEATURES.contains(feature)) result.put(feature, numbers.isEmpty() ? 0 : numbers.get(numbers.size() / 2));
            else result.put(feature, workspace.patients().stream().map(row -> row.value(feature)).filter(value -> value != null).map(Object::toString).findFirst().orElse("Unknown"));
        }
        return result;
    }

    private static List<Double> values(List<PatientFeatures> rows, String feature) { return rows.stream().map(row -> doubleValue(row.value(feature))).filter(value -> value != null && Double.isFinite(value)).toList(); }
    private static Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try { return Double.valueOf(value.toString()); } catch (RuntimeException ignored) { return null; }
    }
    private static double num(Object value) { Double result = doubleValue(value); return result == null ? Double.NaN : result; }
    private static double average(List<Double> values) { return values.isEmpty() ? Double.NaN : values.stream().mapToDouble(value -> value).average().orElse(Double.NaN); }
    private static double median(List<Double> values) { if (values.isEmpty()) return Double.NaN; List<Double> sorted = values.stream().sorted().toList(); int mid = sorted.size() / 2; return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2 : sorted.get(mid); }

    private static Comparator<Map<String, String>> sortComparator(String sort) {
        String key = sort == null || sort.isBlank() ? "visit_date" : sort.toLowerCase(Locale.ROOT);
        return Comparator.comparing(row -> row.getOrDefault(key, ""), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private void writeReport(String filename, byte[] bytes) throws Exception {
        Files.createDirectories(reportDirectory);
        Files.write(reportDirectory.resolve(filename), bytes);
    }

    private static ResponseEntity<byte[]> csv(byte[] bytes, String filename) {
        return attachment(bytes, filename, MediaType.parseMediaType("text/csv;charset=UTF-8"));
    }

    private static ResponseEntity<byte[]> attachment(byte[] bytes, String filename, MediaType type) {
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString()).body(bytes);
    }

    private static String safeFilename(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String safeMessage(Exception exception) { return exception.getMessage() == null ? "The request could not be completed." : exception.getMessage(); }

    public record NavItem(String id, String label, String href) {}
    public record TimelineRow(String date, String provider, String specialty, String facility, String visitType, String event,
                              boolean providerSwitch, boolean longGap, boolean emergency, Long daysSincePrevious) {}
}
