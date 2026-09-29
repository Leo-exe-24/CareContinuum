package org.carecontinuum.service;

import org.carecontinuum.model.ContinuityMetrics;
import org.carecontinuum.model.PatientFeatures;
import org.carecontinuum.model.Visit;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Aggregates visit histories into one model row per patient. */
@Service
public class FeatureEngineeringService {
    private final ContinuityService continuity;

    public FeatureEngineeringService(ContinuityService continuity) { this.continuity = continuity; }

    public List<PatientFeatures> build(List<Visit> visits) {
        Map<String, List<Visit>> grouped = ContinuityService.group(visits);
        Map<String, ContinuityMetrics> continuityRows = continuity.calculateAll(visits);
        List<PatientFeatures> result = new ArrayList<>();
        for (Map.Entry<String, List<Visit>> entry : grouped.entrySet()) {
            String patientId = entry.getKey();
            List<Visit> group = new ArrayList<>(entry.getValue());
            group.sort(Comparator.comparing(Visit::visitDate, Comparator.nullsLast(Comparator.naturalOrder())));
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (String feature : DataCatalog.NUMERIC_FEATURES) {
                if (List.of("total_visits", "visit_frequency", "average_days_between_visits", "maximum_followup_delay", "provider_switch_count", "caregiver_change_rate", "recent_emergency_visits", "prior_90day_emergency_visits", "valid_provider_visits", "unique_providers", "coc_score", "usual_provider_share", "provider_fragmentation", "sequential_continuity").contains(feature)) continue;
                List<Double> numbers = group.stream().map(visit -> visit.number(feature)).filter(Objects::nonNull).sorted().toList();
                if (!numbers.isEmpty()) values.put(feature, feature.equals("age") || feature.equals("hospital_distance_km") ? median(numbers) : numbers.get(numbers.size() - 1));
            }
            for (String category : DataCatalog.CATEGORICAL_FEATURES) {
                String value = mode(group.stream().map(visit -> visit.get(category)).filter(item -> !item.isBlank()).toList());
                if (!value.isBlank()) values.put(category, value);
            }
            int total = group.size();
            List<LocalDate> dates = group.stream().map(Visit::visitDate).filter(Objects::nonNull).sorted().toList();
            long spanDays = dates.size() >= 2 ? ChronoUnit.DAYS.between(dates.getFirst(), dates.getLast()) : 0;
            values.put("total_visits", total);
            values.put("visit_frequency", total / Math.max(spanDays / 365.25, 1.0 / 365.25));
            List<Long> intervals = new ArrayList<>();
            for (int index = 1; index < dates.size(); index++) intervals.add(ChronoUnit.DAYS.between(dates.get(index - 1), dates.get(index)));
            values.put("average_days_between_visits", intervals.isEmpty() ? null : intervals.stream().mapToLong(Long::longValue).average().orElse(Double.NaN));
            values.put("maximum_followup_delay", intervals.isEmpty() ? null : intervals.stream().mapToLong(Long::longValue).max().orElse(0));
            List<String> providers = group.stream().map(Visit::providerId).filter(provider -> !provider.isBlank()).toList();
            int switches = 0;
            for (int index = 1; index < providers.size(); index++) if (!providers.get(index).equals(providers.get(index - 1))) switches++;
            values.put("provider_switch_count", switches);
            double caregiverChanges = numberValue(values.get("caregiver_changes"));
            values.put("caregiver_change_rate", caregiverChanges / Math.max(total - 1, 1));
            LocalDate latest = dates.isEmpty() ? null : dates.getLast();
            long recentEmergency = latest == null ? 0 : group.stream().filter(visit -> visit.visitDate() != null && !visit.visitDate().isBefore(latest.minusDays(90)) && visit.visitType().toLowerCase().contains("emergency")).count();
            long priorEmergency = latest == null ? 0 : group.stream().filter(visit -> visit.visitDate() != null && visit.visitDate().isBefore(latest.minusDays(90)) && !visit.visitDate().isBefore(latest.minusDays(180)) && visit.visitType().toLowerCase().contains("emergency")).count();
            values.put("recent_emergency_visits", recentEmergency);
            values.put("prior_90day_emergency_visits", priorEmergency);
            ContinuityMetrics metrics = continuityRows.get(patientId);
            if (metrics != null) {
                values.put("valid_provider_visits", metrics.validProviderVisits());
                values.put("unique_providers", metrics.uniqueProviders());
                values.put("coc_score", finiteOrNull(metrics.cocScore()));
                values.put("usual_provider_share", finiteOrNull(metrics.usualProviderShare()));
                values.put("provider_fragmentation", finiteOrNull(metrics.providerFragmentation()));
                values.put("sequential_continuity", finiteOrNull(metrics.sequentialContinuity()));
            }
            Integer target = consistentTarget(group);
            result.add(new PatientFeatures(patientId, values, target));
        }
        result.sort(Comparator.comparing(PatientFeatures::patientId));
        return result;
    }

    private static Integer consistentTarget(List<Visit> visits) {
        Integer value = null;
        for (Visit visit : visits) {
            String raw = visit.get("adverse_outcome").toLowerCase();
            if (raw.isBlank()) return null;
            Integer next = switch (raw) { case "1", "1.0", "yes", "true" -> 1; case "0", "0.0", "no", "false" -> 0; default -> null; };
            if (next == null || (value != null && !value.equals(next))) return null;
            value = next;
        }
        return value;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2.0 : values.get(middle);
    }

    private static String mode(List<String> values) {
        if (values.isEmpty()) return "";
        Map<String, Integer> counts = new TreeMap<>();
        values.forEach(value -> counts.merge(value, 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    private static double numberValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return 0.0;
    }

    private static Double finiteOrNull(Double value) { return value != null && Double.isFinite(value) ? value : null; }
}
