package org.carecontinuum.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One longitudinal visit row; extra source columns are retained as values. */
public final class Visit {
    private final Map<String, String> values;

    public Visit(Map<String, String> values) {
        this.values = new LinkedHashMap<>();
        values.forEach((key, value) -> this.values.put(key.trim().toLowerCase(), value == null ? "" : value.trim()));
    }

    public Map<String, String> values() { return Map.copyOf(values); }
    public String get(String name) { return values.getOrDefault(name.toLowerCase(), ""); }
    public String patientId() { return get("patient_id"); }
    public String visitId() { return get("visit_id"); }
    public String providerId() { return get("provider_id"); }
    public String visitType() { return get("visit_type"); }
    public String getPatientId() { return patientId(); }
    public String getVisitId() { return visitId(); }
    public String getProviderId() { return providerId(); }
    public String getVisitType() { return visitType(); }
    public String getVisitDate() { return get("visit_date"); }

    public LocalDate visitDate() {
        String raw = get("visit_date");
        if (raw.isBlank()) return null;
        String isoDate = raw.substring(0, Math.min(raw.length(), 10));
        try { return LocalDate.parse(isoDate); }
        catch (RuntimeException ignored) { }
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ofPattern("M/d/uuuu"), DateTimeFormatter.ofPattern("M/d/uu"), DateTimeFormatter.ofPattern("d-M-uuuu"))) {
            try { return LocalDate.parse(raw, formatter); }
            catch (RuntimeException ignored) { }
        }
        try { return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate(); }
        catch (RuntimeException ignored) { return null; }
    }

    public Double number(String name) {
        String raw = get(name);
        if (raw.isBlank()) return null;
        try { return Double.valueOf(raw); }
        catch (NumberFormatException ignored) { return null; }
    }
}
