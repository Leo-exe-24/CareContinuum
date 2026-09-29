package org.carecontinuum.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.carecontinuum.model.GapAlert;
import org.carecontinuum.model.ModelEvaluation;
import org.carecontinuum.model.PatientFeatures;
import org.carecontinuum.model.PredictionResult;
import org.carecontinuum.model.Visit;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;

/** Local PDF report generation using OpenPDF. */
@Service
public class ReportService {
    private static final String DISCLAIMER = "Research Prototype — Not a Medical Diagnosis. This prediction must not be used as a diagnosis or substitute for professional clinical judgment.";

    public byte[] patientReport(PatientFeatures patient, List<Visit> visits, PredictionResult prediction,
                                List<GapAlert> gaps, String datasetLabel) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.LETTER, 44, 44, 42, 42);
        PdfWriter.getInstance(document, output);
        document.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, new Color(21, 59, 58));
        document.add(new Paragraph("CareContinuum", title));
        document.add(new Paragraph("Patient Continuity Summary · Generated " + Instant.now()));
        document.add(new Paragraph(DISCLAIMER));
        document.add(new Paragraph("Dataset: " + datasetLabel));
        document.add(new Paragraph("Patient overview", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        PdfPTable overview = table("Measure", "Value");
        overview.addCell("Patient ID"); overview.addCell(patient.patientId());
        for (String key : List.of("age", "sex", "chronic_condition_count", "medication_count", "previous_hospitalizations", "emergency_visits", "outpatient_visits", "lab_abnormality_count", "followup_delay_days", "missed_appointments", "caregiver_changes", "total_visits", "unique_providers", "provider_switch_count", "coc_score", "usual_provider_share", "sequential_continuity")) {
            overview.addCell(key.replace('_', ' ')); overview.addCell(String.valueOf(patient.value(key)));
        }
        document.add(overview);
        document.add(new Paragraph("Recent visit timeline", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        PdfPTable timeline = table("Date", "Provider", "Specialty", "Facility", "Visit type");
        for (Visit visit : visits) addRow(timeline, visit.get("visit_date"), visit.providerId(), visit.get("provider_specialty"), visit.get("facility_id"), visit.visitType());
        document.add(timeline);
        document.add(new Paragraph("Research Risk Outcome", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        if (prediction != null) {
            document.add(new Paragraph(String.format(java.util.Locale.ROOT, "Predicted probability: %.1f%% · Category: %s", 100 * prediction.probability(), prediction.category())));
            document.add(new Paragraph("Model feature contribution — not causal evidence."));
            PdfPTable contributors = table("Feature", "Contribution", "Direction");
            prediction.contributions().stream().limit(8).forEach(item -> addRow(contributors, item.feature(), String.format(java.util.Locale.ROOT, "%.4f", item.value()), item.direction()));
            document.add(contributors);
        } else document.add(new Paragraph("No model prediction is available."));
        document.add(new Paragraph("Potential continuity gaps", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        PdfPTable alerts = table("Reason", "Supporting metric", "Severity", "Suggested follow-up");
        if (gaps.isEmpty()) addRow(alerts, "No configured gap rule triggered.", "—", "—", "—");
        else gaps.forEach(gap -> addRow(alerts, gap.reason(), gap.supportingMetric(), gap.severity(), gap.suggestedAction()));
        document.add(alerts);
        document.add(new Paragraph("All gap rules and suggestions are non-diagnostic workflow prompts. COC and model thresholds are prototype settings, not clinical guidelines."));
        document.close();
        return output.toByteArray();
    }

    public byte[] modelReport(ModelEvaluation evaluation, List<String> features, String datasetLabel, int patientCount, int visitCount) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.LETTER, 44, 44, 42, 42);
        PdfWriter.getInstance(document, output);
        document.open();
        document.add(new Paragraph("CareContinuum · Model Evaluation Report", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, new Color(21, 59, 58))));
        document.add(new Paragraph("Generated " + Instant.now()));
        document.add(new Paragraph("Synthetic demonstration result when the bundled demo is used. Metrics are generated from the current run."));
        PdfPTable dataset = table("Dataset item", "Value");
        dataset.addCell("Dataset type"); dataset.addCell(datasetLabel);
        dataset.addCell("Patients"); dataset.addCell(Integer.toString(patientCount));
        dataset.addCell("Visits"); dataset.addCell(Integer.toString(visitCount));
        dataset.addCell("Training / test patients"); dataset.addCell(evaluation.trainingPatients() + " / " + evaluation.testPatients());
        dataset.addCell("Target"); dataset.addCell("Research Risk Outcome (adverse_outcome)");
        dataset.addCell("Input features"); dataset.addCell(String.join(", ", features));
        dataset.addCell("Training configuration"); dataset.addCell(evaluation.parameters().toString());
        document.add(dataset);
        PdfPTable metrics = table("Metric", "Value");
        metrics.addCell("Accuracy"); metrics.addCell(format(evaluation.accuracy()));
        metrics.addCell("Precision"); metrics.addCell(format(evaluation.precision()));
        metrics.addCell("Recall"); metrics.addCell(format(evaluation.recall()));
        metrics.addCell("F1"); metrics.addCell(format(evaluation.f1()));
        metrics.addCell("ROC-AUC"); metrics.addCell(format(evaluation.rocAuc()));
        metrics.addCell("PR-AUC"); metrics.addCell(format(evaluation.prAuc()));
        metrics.addCell("CV mean ROC-AUC ± SD"); metrics.addCell(format(evaluation.meanCvRocAuc()) + " ± " + format(evaluation.stdCvRocAuc()));
        metrics.addCell("CV mean F1 / recall"); metrics.addCell(format(evaluation.meanCvF1()) + " / " + format(evaluation.meanCvRecall()));
        document.add(new Paragraph("Evaluation metrics", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        document.add(metrics);
        PdfPTable confusion = table("Actual \\ Predicted", "Predicted 0", "Predicted 1");
        addRow(confusion, "Actual 0", Integer.toString(evaluation.confusionMatrix()[0][0]), Integer.toString(evaluation.confusionMatrix()[0][1]));
        addRow(confusion, "Actual 1", Integer.toString(evaluation.confusionMatrix()[1][0]), Integer.toString(evaluation.confusionMatrix()[1][1]));
        document.add(confusion);
        document.add(new Paragraph("Limitations", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        for (String limitation : List.of(
                "Synthetic data does not establish clinical validity or effectiveness.",
                "COC measures provider concentration/dispersion and does not capture every dimension of continuity.",
                "Model associations are not causal; TreeSHAP values describe model contributions.",
                "Real data may have missingness, bias, and dataset shift; thresholds require appropriate validation.",
                DISCLAIMER)) document.add(new Paragraph("• " + limitation));
        document.close();
        return output.toByteArray();
    }

    private static PdfPTable table(String... headers) {
        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE)));
            cell.setBackgroundColor(new Color(36, 92, 102));
            table.addCell(cell);
        }
        table.setHeaderRows(1);
        return table;
    }

    private static void addRow(PdfPTable table, String... values) {
        for (String value : values) addCell(table, value);
    }

    private static void addCell(PdfPTable table, String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value == null ? "Not available" : value, FontFactory.getFont(FontFactory.HELVETICA, 9)));
        cell.setPadding(5);
        table.addCell(cell);
    }

    private static String format(double value) { return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.4f", value) : "Not available"; }
}
