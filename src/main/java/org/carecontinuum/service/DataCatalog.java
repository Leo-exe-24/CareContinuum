package org.carecontinuum.service;

import java.util.List;

/** Canonical longitudinal visit schema used by mapping, validation, and export. */
public final class DataCatalog {
    private DataCatalog() {}

    public static final List<String> EXPECTED_COLUMNS = List.of(
            "patient_id", "visit_id", "visit_date", "provider_id", "provider_specialty", "facility_id",
            "age", "sex", "region", "chronic_condition_count", "diabetes", "hypertension",
            "cardiovascular_condition", "respiratory_condition", "mental_health_condition", "medication_count",
            "previous_hospitalizations", "emergency_visits", "outpatient_visits", "lab_abnormality_count",
            "followup_delay_days", "missed_appointments", "caregiver_changes", "hospital_distance_km",
            "insurance_or_access_indicator", "previous_adverse_event", "adverse_outcome", "visit_type");
    public static final List<String> REQUIRED_CONTINUITY = List.of("patient_id", "visit_date", "provider_id");
    public static final List<String> NUMERIC_FEATURES = List.of(
            "age", "chronic_condition_count", "diabetes", "hypertension", "cardiovascular_condition",
            "respiratory_condition", "mental_health_condition", "medication_count", "previous_hospitalizations",
            "emergency_visits", "outpatient_visits", "lab_abnormality_count", "followup_delay_days",
            "missed_appointments", "caregiver_changes", "hospital_distance_km", "insurance_or_access_indicator",
            "previous_adverse_event", "total_visits", "visit_frequency", "average_days_between_visits",
            "maximum_followup_delay", "provider_switch_count", "caregiver_change_rate", "recent_emergency_visits",
            "prior_90day_emergency_visits", "valid_provider_visits", "unique_providers", "coc_score",
            "usual_provider_share", "provider_fragmentation", "sequential_continuity");
    public static final List<String> CATEGORICAL_FEATURES = List.of("sex", "region");
}
