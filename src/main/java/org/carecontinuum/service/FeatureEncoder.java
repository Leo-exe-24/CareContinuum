package org.carecontinuum.service;

import org.carecontinuum.model.PatientFeatures;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Train-only median imputation, numeric scaling, and categorical one-hot encoding. */
public final class FeatureEncoder implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private final List<String> inputFeatures;
    private final List<String> numericFeatures = new ArrayList<>();
    private final List<String> categoricalFeatures = new ArrayList<>();
    private final Map<String, Double> medians = new LinkedHashMap<>();
    private final Map<String, Double> means = new LinkedHashMap<>();
    private final Map<String, Double> scales = new LinkedHashMap<>();
    private final Map<String, List<String>> categories = new LinkedHashMap<>();
    private final List<String> transformedNames = new ArrayList<>();

    public FeatureEncoder(List<String> inputFeatures) { this.inputFeatures = List.copyOf(inputFeatures); }

    public void fit(List<PatientFeatures> trainingRows) {
        for (String feature : inputFeatures) {
            if (DataCatalog.NUMERIC_FEATURES.contains(feature)) {
                numericFeatures.add(feature);
                List<Double> values = trainingRows.stream().map(row -> asNumber(row.value(feature))).filter(value -> value != null).sorted().toList();
                double median = values.isEmpty() ? 0.0 : median(values);
                double mean = values.stream().mapToDouble(value -> value).average().orElse(median);
                double variance = values.isEmpty() ? 0.0 : values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0.0);
                double scale = Math.sqrt(variance);
                medians.put(feature, median);
                means.put(feature, mean);
                scales.put(feature, scale < 1.0e-9 ? 1.0 : scale);
                transformedNames.add(feature);
            } else {
                categoricalFeatures.add(feature);
                TreeSet<String> found = new TreeSet<>();
                trainingRows.stream().map(row -> asString(row.value(feature))).forEach(found::add);
                if (found.isEmpty()) found.add("Unknown");
                List<String> values = List.copyOf(found);
                categories.put(feature, values);
                values.forEach(value -> transformedNames.add(feature + "=" + value));
            }
        }
    }

    public float[] transform(PatientFeatures row) {
        List<Float> result = new ArrayList<>(transformedNames.size());
        for (String feature : numericFeatures) {
            double value = asNumber(row.value(feature)) == null ? medians.get(feature) : asNumber(row.value(feature));
            result.add((float) ((value - means.get(feature)) / scales.get(feature)));
        }
        for (String feature : categoricalFeatures) {
            String value = asString(row.value(feature));
            for (String option : categories.get(feature)) result.add(option.equals(value) ? 1.0f : 0.0f);
        }
        float[] values = new float[result.size()];
        for (int index = 0; index < result.size(); index++) values[index] = result.get(index);
        return values;
    }

    public float[] transform(List<PatientFeatures> rows) {
        int columns = transformedNames.size();
        float[] result = new float[rows.size() * columns];
        for (int row = 0; row < rows.size(); row++) System.arraycopy(transform(rows.get(row)), 0, result, row * columns, columns);
        return result;
    }

    public List<String> inputFeatures() { return inputFeatures; }
    public List<String> transformedNames() { return List.copyOf(transformedNames); }
    public int transformedFeatureCount() { return transformedNames.size(); }

    private static Double asNumber(Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return number.doubleValue();
        if (value != null) try { return Double.valueOf(value.toString()); } catch (NumberFormatException ignored) { }
        return null;
    }

    private static String asString(Object value) { return value == null || value.toString().isBlank() ? "Unknown" : value.toString(); }

    private static double median(List<Double> values) {
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle);
    }
}
