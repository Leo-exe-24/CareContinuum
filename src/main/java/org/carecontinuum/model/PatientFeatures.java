package org.carecontinuum.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Aggregated one-row-per-patient inputs and separate research target. */
public final class PatientFeatures {
    private final String patientId;
    private final LinkedHashMap<String, Object> values;
    private final Integer target;

    public PatientFeatures(String patientId, Map<String, Object> values, Integer target) {
        this.patientId = patientId;
        this.values = new LinkedHashMap<>(values);
        this.target = target;
    }

    public String patientId() { return patientId; }
    public Map<String, Object> values() { return Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    public Object value(String key) { return values.get(key); }
    public Integer target() { return target; }
    public String getPatientId() { return patientId; }
    public Map<String, Object> getValues() { return values(); }
    public Integer getTarget() { return target; }
}
