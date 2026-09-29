package org.carecontinuum.service;

import org.carecontinuum.model.ModelRun;
import org.carecontinuum.model.PatientFeatures;
import org.carecontinuum.model.ValidationReport;
import org.carecontinuum.model.Visit;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.util.List;

/** Per-browser session data; uploaded records are not written to disk or logs. */
@Component
@SessionScope
public class Workspace {
    private List<Visit> visits = List.of();
    private List<PatientFeatures> patients = List.of();
    private ValidationReport validation;
    private ModelRun model;
    private String datasetLabel = "No dataset loaded";
    private String modelMessage = "";
    private CsvDataService.RawTable pendingUpload;

    public List<Visit> visits() { return visits; }
    public List<PatientFeatures> patients() { return patients; }
    public ValidationReport validation() { return validation; }
    public ModelRun model() { return model; }
    public String datasetLabel() { return datasetLabel; }
    public String modelMessage() { return modelMessage; }
    public CsvDataService.RawTable pendingUpload() { return pendingUpload; }
    public boolean loaded() { return !visits.isEmpty(); }
    public boolean modelReady() { return model != null; }
    public List<Visit> getVisits() { return visits; }
    public List<PatientFeatures> getPatients() { return patients; }
    public ValidationReport getValidation() { return validation; }
    public ModelRun getModel() { return model; }
    public String getDatasetLabel() { return datasetLabel; }
    public String getModelMessage() { return modelMessage; }
    public boolean isLoaded() { return loaded(); }
    public boolean isModelReady() { return modelReady(); }

    public void setVisits(List<Visit> visits) { this.visits = List.copyOf(visits); }
    public void setPatients(List<PatientFeatures> patients) { this.patients = List.copyOf(patients); }
    public void setValidation(ValidationReport validation) { this.validation = validation; }
    public void setModel(ModelRun model) { this.model = model; }
    public void setDatasetLabel(String datasetLabel) { this.datasetLabel = datasetLabel; }
    public void setModelMessage(String modelMessage) { this.modelMessage = modelMessage; }
    public void setPendingUpload(CsvDataService.RawTable pendingUpload) { this.pendingUpload = pendingUpload; }
}
