package org.carecontinuum.service;

import org.carecontinuum.model.GapAlert;
import org.carecontinuum.model.PatientFeatures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Configurable rule-based potential continuity-gap detector. */
@Service
public class GapDetectionService {
    private final double highFragmentation;
    private final int switchThreshold;
    private final double lowCoc;
    private final int followupDays;
    private final int missedThreshold;
    private final int caregiverThreshold;
    private final int emergencyIncrease;

    public GapDetectionService(@Value("${carecontinuum.gap.high-fragmentation:0.60}") double highFragmentation,
                               @Value("${carecontinuum.gap.provider-switch-count:3}") int switchThreshold,
                               @Value("${carecontinuum.gap.low-coc:0.30}") double lowCoc,
                               @Value("${carecontinuum.gap.long-followup-days:60}") int followupDays,
                               @Value("${carecontinuum.gap.missed-appointments:3}") int missedThreshold,
                               @Value("${carecontinuum.gap.caregiver-changes:3}") int caregiverThreshold,
                               @Value("${carecontinuum.gap.emergency-increase:2}") int emergencyIncrease) {
        this.highFragmentation = highFragmentation; this.switchThreshold = switchThreshold; this.lowCoc = lowCoc;
        this.followupDays = followupDays; this.missedThreshold = missedThreshold; this.caregiverThreshold = caregiverThreshold; this.emergencyIncrease = emergencyIncrease;
    }

    public List<GapAlert> detect(PatientFeatures patient) {
        Map<String, Object> values = patient.values();
        List<GapAlert> alerts = new ArrayList<>();
        double fragmentation = number(values, "provider_fragmentation", Double.NaN);
        double coc = number(values, "coc_score", Double.NaN);
        int switches = (int) number(values, "provider_switch_count", 0);
        double delay = Math.max(number(values, "maximum_followup_delay", 0), number(values, "followup_delay_days", 0));
        int missed = (int) number(values, "missed_appointments", 0);
        int caregivers = (int) number(values, "caregiver_changes", 0);
        double recent = number(values, "recent_emergency_visits", 0), prior = number(values, "prior_90day_emergency_visits", 0);
        add(alerts, Double.isFinite(fragmentation) && fragmentation >= highFragmentation, "high_fragmentation", "Potentially high provider fragmentation detected.", "Provider fragmentation = " + round(fragmentation), "Moderate", "Consider reviewing whether recent visits can be coordinated across the patient's care network.");
        add(alerts, switches >= switchThreshold, "provider_switching", "Frequent provider switching detected.", "Provider switches = " + switches, "Moderate", "Consider reviewing recent care transitions and ensuring relevant information is available to the current care team.");
        add(alerts, Double.isFinite(coc) && coc < lowCoc, "low_coc", "Low continuity score detected using prototype thresholds.", "COC = " + round(coc), "Moderate", "Consider reviewing whether the patient has an identifiable primary point of contact.");
        add(alerts, delay >= followupDays, "followup_delay", "A long follow-up interval was detected.", "Maximum interval/follow-up delay = " + Math.round(delay) + " days", "Moderate", "Consider reviewing whether follow-up scheduling or access barriers contributed to the delay.");
        add(alerts, missed >= missedThreshold, "missed_appointments", "Multiple missed appointments were recorded.", "Missed appointments = " + missed, "Moderate", "Consider reviewing appointment access, reminders, and scheduling preferences with the patient.");
        add(alerts, caregivers >= caregiverThreshold, "caregiver_changes", "Multiple caregiver changes were recorded.", "Caregiver changes = " + caregivers, "Low", "Consider ensuring care instructions and recent clinical information are consistently communicated across caregivers.");
        add(alerts, recent - prior >= emergencyIncrease, "emergency_increase", "A sudden increase in recent emergency visits was detected.", "Recent 90-day emergency visits = " + (int) recent + "; prior 90-day = " + (int) prior, "High", "Consider reviewing the recent change in emergency visit patterns with the care team.");
        return alerts;
    }

    private static void add(List<GapAlert> alerts, boolean condition, String code, String reason, String metric, String severity, String action) {
        if (condition) alerts.add(new GapAlert(code, reason, metric, severity, action));
    }
    private static double number(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(value.toString()); } catch (RuntimeException ignored) { return fallback; }
    }
    private static String round(double value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }
}
