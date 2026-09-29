package org.carecontinuum.service;

import org.carecontinuum.model.ContinuityMetrics;
import org.carecontinuum.model.Visit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calculates Bice–Boxerman, UPC, and sequential continuity by patient. */
@Service
public class ContinuityService {
    public double calculateCoc(List<Visit> visits) {
        List<String> providers = validProviders(visits);
        if (providers.size() < 2) return Double.NaN;
        Map<String, Integer> counts = new HashMap<>();
        providers.forEach(provider -> counts.merge(provider, 1, Integer::sum));
        int n = providers.size();
        double squares = counts.values().stream().mapToDouble(count -> count * count).sum();
        return Math.max(0.0, Math.min(1.0, (squares - n) / (n * (double) (n - 1))));
    }

    public Map<String, ContinuityMetrics> calculateAll(List<Visit> visits) {
        Map<String, List<Visit>> grouped = group(visits);
        Map<String, ContinuityMetrics> result = new LinkedHashMap<>();
        grouped.forEach((patientId, patientVisits) -> result.put(patientId, calculatePatient(patientId, patientVisits)));
        return result;
    }

    public ContinuityMetrics calculatePatient(String patientId, List<Visit> visits) {
        List<Visit> ordered = new ArrayList<>(visits);
        ordered.sort(Comparator.comparing(Visit::visitDate, Comparator.nullsLast(Comparator.naturalOrder())));
        List<String> providers = validProviders(ordered);
        int valid = providers.size();
        double coc = calculateCoc(ordered);
        int unique = (int) providers.stream().distinct().count();
        double upc = Double.NaN;
        double secon = Double.NaN;
        if (!providers.isEmpty()) {
            Map<String, Long> counts = providers.stream().collect(java.util.stream.Collectors.groupingBy(p -> p, java.util.stream.Collectors.counting()));
            upc = counts.values().stream().mapToLong(Long::longValue).max().orElse(0) / (double) valid;
        }
        if (valid >= 2) {
            int same = 0;
            for (int i = 1; i < valid; i++) if (providers.get(i).equals(providers.get(i - 1))) same++;
            secon = same / (double) (valid - 1);
        }
        return new ContinuityMetrics(patientId, visits.size(), valid, unique, coc, upc,
                Double.isFinite(coc) ? 1.0 - coc : Double.NaN, secon);
    }

    public static Map<String, List<Visit>> group(List<Visit> visits) {
        Map<String, List<Visit>> grouped = new LinkedHashMap<>();
        for (Visit visit : visits) if (!visit.patientId().isBlank()) grouped.computeIfAbsent(visit.patientId(), ignored -> new ArrayList<>()).add(visit);
        return grouped;
    }

    private static List<String> validProviders(List<Visit> visits) {
        return visits.stream().sorted(Comparator.comparing(Visit::visitDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Visit::providerId).filter(provider -> provider != null && !provider.isBlank()).toList();
    }

    public String classify(Double score, double low, double high) {
        if (score == null || !Double.isFinite(score)) return "Not calculable";
        if (score < low) return "Low continuity";
        if (score <= high) return "Moderate continuity";
        return "High continuity";
    }
}
