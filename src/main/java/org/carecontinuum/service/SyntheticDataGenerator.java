package org.carecontinuum.service;

import org.carecontinuum.model.Visit;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Generates clearly synthetic longitudinal trajectories for demonstrations. */
@Service
public class SyntheticDataGenerator {
    public static final String DATASET_LABEL = "SYNTHETIC DEMONSTRATION DATA — NOT REAL PATIENT DATA";
    private static final String[] SPECIALTIES = {"Primary Care", "Cardiology", "Endocrinology", "Pulmonology", "Behavioral Health"};
    private static final String[] REGIONS = {"North", "South", "East", "West", "Central"};

    public List<Visit> generate(int patientCount, long seed) {
        if (patientCount < 5) throw new IllegalArgumentException("At least five synthetic patients are required for the demo scenarios.");
        Random random = new Random(seed);
        List<Visit> visits = new ArrayList<>();
        LocalDate origin = LocalDate.of(2023, 1, 1);
        for (int patientIndex = 0; patientIndex < patientCount; patientIndex++) {
            String patientId = patientIndex < 5 ? String.format("DEMO-%03d", patientIndex + 1) : String.format("SYN-%05d", patientIndex - 4);
            double switchiness = patientIndex == 0 ? 0.02 : patientIndex == 1 ? 0.45 : patientIndex == 2 ? 0.94 : patientIndex == 3 ? 0.55 : patientIndex == 4 ? 1.0 : betaLike(random);
            double utilization = patientIndex == 3 ? 0.96 : patientIndex == 0 ? 0.10 : patientIndex == 1 ? 0.25 : patientIndex == 2 ? 0.58 : patientIndex == 4 ? 0.38 : random.nextDouble() * 0.75;
            int n = 2 + random.nextInt(14);
            if (patientIndex == 0) n = 6;
            if (patientIndex == 1) n = 7;
            if (patientIndex == 2) n = 8;
            if (patientIndex == 3) n = 9;
            if (patientIndex == 4) n = 7;
            int age = 18 + random.nextInt(72);
            int chronic = Math.min(6, (int) Math.round(random.nextDouble() * 3 + age / 45.0 + utilization));
            int diabetes = random.nextDouble() < 0.15 + chronic * 0.08 ? 1 : 0;
            int hypertension = random.nextDouble() < 0.18 + age / 160.0 + chronic * 0.05 ? 1 : 0;
            int cardiovascular = random.nextDouble() < 0.06 + age / 220.0 + chronic * 0.025 ? 1 : 0;
            int respiratory = random.nextDouble() < 0.12 + chronic * 0.035 ? 1 : 0;
            int mentalHealth = random.nextDouble() < 0.20 + utilization * 0.10 ? 1 : 0;
            int medications = Math.max(0, (int) Math.round(1 + chronic * 1.4 + random.nextGaussian()));
            int hospitalizations = Math.max(0, (int) Math.round(utilization * 1.7 + random.nextGaussian() * 0.6));
            int emergency = Math.max(0, (int) Math.round(utilization * 2.0 + random.nextGaussian()));
            if (patientIndex == 3) emergency = Math.max(3, emergency);
            int outpatient = n + random.nextInt(5);
            int labs = Math.max(0, (int) Math.round(chronic * 0.6 + random.nextDouble() * 3));
            int delay = Math.max(0, (int) Math.round(12 + switchiness * 45 + utilization * 10 + random.nextGaussian() * 12));
            int missed = Math.max(0, (int) Math.round(switchiness * 1.8 + utilization * 0.6 + random.nextDouble()));
            int caregiver = Math.max(0, (int) Math.round(switchiness * 2 + random.nextDouble()));
            if (patientIndex == 2) { delay = 75; missed = Math.max(3, missed); caregiver = Math.max(3, caregiver); }
            int access = random.nextDouble() < 0.12 + switchiness * 0.18 ? 1 : 0;
            int priorEvent = random.nextDouble() < 0.07 + utilization * 0.16 + chronic * 0.02 ? 1 : 0;
            String[] providerPattern = providerPattern(patientIndex, n, switchiness, random);
            double logit = -2.7 + chronic * 0.20 + emergency * 0.22 + hospitalizations * 0.16 + missed * 0.23 + caregiver * 0.28 + delay * 0.018 + switchiness * 0.40 + access * 0.22 + priorEvent * 0.25;
            int outcome = random.nextDouble() < 1.0 / (1.0 + Math.exp(-logit)) ? 1 : 0;
            LocalDate date = origin.plusDays(random.nextInt(100));
            for (int visitIndex = 0; visitIndex < n; visitIndex++) {
                if (visitIndex > 0) {
                    int gap = patientIndex == 2 ? 30 : patientIndex == 4 ? new int[]{29, 30, 30, 30, 20, 40}[visitIndex - 1] : patientIndex == 3 ? new int[]{30, 30, 31, 30, 10, 20, 35, 40}[visitIndex - 1] : 18 + random.nextInt(44);
                    date = date.plusDays(gap);
                }
                String specialty = patientIndex == 3 && (visitIndex == 4 || visitIndex == 5) ? "Emergency Medicine" : SPECIALTIES[random.nextInt(SPECIALTIES.length)];
                String provider = patientIndex == 3 && (visitIndex == 4 || visitIndex == 5) ? "PRV-090" : providerPattern[visitIndex];
                Map<String, String> values = new LinkedHashMap<>();
                values.put("patient_id", patientId);
                values.put("visit_id", patientId + "-V" + String.format("%02d", visitIndex + 1));
                values.put("visit_date", date.toString());
                values.put("provider_id", provider);
                values.put("provider_specialty", specialty);
                values.put("facility_id", String.format("FAC-%02d", 1 + random.nextInt(15)));
                values.put("age", Integer.toString(age));
                values.put("sex", random.nextBoolean() ? "Female" : "Male");
                values.put("region", REGIONS[random.nextInt(REGIONS.length)]);
                values.put("chronic_condition_count", Integer.toString(chronic));
                values.put("diabetes", Integer.toString(diabetes));
                values.put("hypertension", Integer.toString(hypertension));
                values.put("cardiovascular_condition", Integer.toString(cardiovascular));
                values.put("respiratory_condition", Integer.toString(respiratory));
                values.put("mental_health_condition", Integer.toString(mentalHealth));
                values.put("medication_count", Integer.toString(medications));
                values.put("previous_hospitalizations", Integer.toString(hospitalizations));
                values.put("emergency_visits", Integer.toString(emergency));
                values.put("outpatient_visits", Integer.toString(outpatient));
                values.put("lab_abnormality_count", Integer.toString(labs));
                values.put("followup_delay_days", Integer.toString(delay));
                values.put("missed_appointments", Integer.toString(missed));
                values.put("caregiver_changes", Integer.toString(caregiver));
                values.put("hospital_distance_km", String.format(java.util.Locale.ROOT, "%.1f", 2 + random.nextDouble() * 80));
                values.put("insurance_or_access_indicator", Integer.toString(access));
                values.put("previous_adverse_event", Integer.toString(priorEvent));
                values.put("adverse_outcome", Integer.toString(outcome));
                values.put("visit_type", specialty.equals("Emergency Medicine") ? "Emergency" : "Outpatient");
                visits.add(new Visit(values));
            }
        }
        return visits;
    }

    private static double betaLike(Random random) { return Math.min(0.99, Math.max(0.02, (random.nextDouble() + random.nextDouble()) / 3.0)); }

    private static String[] providerPattern(int patientIndex, int n, double switchiness, Random random) {
        if (patientIndex == 0) return fill("PRV-001", n);
        if (patientIndex == 1) return new String[]{"PRV-002", "PRV-002", "PRV-002", "PRV-003", "PRV-003", "PRV-002", "PRV-003"};
        if (patientIndex == 2) return new String[]{"PRV-011", "PRV-023", "PRV-034", "PRV-048", "PRV-057", "PRV-068", "PRV-075", "PRV-083"};
        if (patientIndex == 3) return new String[]{"PRV-006", "PRV-006", "PRV-008", "PRV-009", "PRV-006", "PRV-008", "PRV-010", "PRV-006", "PRV-009"};
        if (patientIndex == 4) return new String[]{"PRV-012", "PRV-013", "PRV-012", "PRV-014", "PRV-013", "PRV-014", "PRV-012"};
        String primary = String.format("PRV-%03d", 1 + random.nextInt(90));
        String[] pattern = new String[n];
        for (int i = 0; i < n; i++) pattern[i] = i == 0 || random.nextDouble() > switchiness ? primary : String.format("PRV-%03d", 1 + random.nextInt(90));
        return pattern;
    }

    private static String[] fill(String value, int n) {
        String[] result = new String[n];
        java.util.Arrays.fill(result, value);
        return result;
    }
}
