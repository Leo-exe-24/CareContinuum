package org.carecontinuum.service;

import org.carecontinuum.model.ValidationReport;
import org.carecontinuum.model.Visit;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks schema, dates, duplicate records, target labels, and numeric ranges. */
@Service
public class ValidationService {
    public ValidationReport validate(List<Visit> visits) {
        if (visits == null || visits.isEmpty()) throw new IllegalArgumentException("The dataset is empty.");
        Set<String> available = new HashSet<>();
        visits.forEach(visit -> available.addAll(visit.values().keySet()));
        List<String> missingColumns = new ArrayList<>();
        for (String required : DataCatalog.REQUIRED_CONTINUITY) if (!available.contains(required)) missingColumns.add(required);
        if (!available.contains("adverse_outcome")) missingColumns.add("adverse_outcome (model target)");
        for (String expected : DataCatalog.EXPECTED_COLUMNS) {
            if (!available.contains(expected) && !DataCatalog.REQUIRED_CONTINUITY.contains(expected) && !expected.equals("adverse_outcome")) missingColumns.add(expected);
        }
        Set<String> patients = new HashSet<>(), providers = new HashSet<>(), visitIds = new HashSet<>(), rowSignatures = new HashSet<>();
        Map<String, Integer> patientCounts = new HashMap<>();
        List<Map<String, Object>> issues = new ArrayList<>();
        int missing = 0, duplicateRows = 0, invalidRows = 0;
        LocalDate minDate = null, maxDate = null;
        for (int index = 0; index < visits.size(); index++) {
            Visit visit = visits.get(index);
            String patient = visit.patientId();
            if (!patient.isBlank()) { patients.add(patient); patientCounts.merge(patient, 1, Integer::sum); }
            if (!visit.providerId().isBlank()) providers.add(visit.providerId());
            LocalDate date = visit.visitDate();
            if (date != null) {
                if (minDate == null || date.isBefore(minDate)) minDate = date;
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date;
            }
            visit.values().values().forEach(value -> { if (value == null || value.isBlank()) { /* counted below */ } });
            for (String value : visit.values().values()) if (value == null || value.isBlank()) missing++;
            List<String> reasons = new ArrayList<>();
            if (!rowSignatures.add(visit.values().toString())) { duplicateRows++; reasons.add("Duplicate row"); }
            if (!visit.visitId().isBlank() && !visitIds.add(visit.visitId())) reasons.add("Duplicate visit ID");
            if (visit.get("visit_date").isBlank() || date == null) reasons.add("Invalid or missing visit date");
            if (visit.patientId().isBlank()) reasons.add("Missing patient ID");
            if (visit.providerId().isBlank()) reasons.add("Missing provider ID");
            Double age = visit.number("age");
            if (age != null && (age < 0 || age > 120)) reasons.add("Impossible age");
            for (Map.Entry<String, String> entry : visit.values().entrySet()) {
                if (entry.getKey().equals("adverse_outcome")) continue;
                try { if (Double.parseDouble(entry.getValue()) < 0) reasons.add("Negative numerical value: " + entry.getKey()); }
                catch (RuntimeException ignored) { }
            }
            String outcome = visit.get("adverse_outcome");
            if (!outcome.isBlank() && !Set.of("0", "1", "0.0", "1.0", "false", "true", "no", "yes").contains(outcome.toLowerCase())) reasons.add("Invalid outcome value");
            if (!reasons.isEmpty()) {
                invalidRows++;
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("row_index", index + 1);
                issue.put("issues", String.join("; ", reasons));
                issues.add(issue);
            }
        }
        long insufficient = patientCounts.values().stream().filter(count -> count < 2).count();
        issues.add(Map.of("row_index", "—", "issues", "Patients with fewer than 2 visits: " + insufficient));
        String dateRange = minDate == null ? "Unavailable" : minDate + " to " + maxDate;
        return new ValidationReport(visits.size(), patients.size(), providers.size(), dateRange, missing,
                duplicateRows, invalidRows, missingColumns, issues);
    }
}
