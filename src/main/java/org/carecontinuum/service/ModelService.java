package org.carecontinuum.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.carecontinuum.model.ModelEvaluation;
import org.carecontinuum.model.ModelRun;
import org.carecontinuum.model.PatientFeatures;
import org.carecontinuum.model.PredictionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** XGBoost training, patient-level splitting, evaluation, TreeSHAP, and persistence. */
@Service
public class ModelService {
    private final ObjectMapper mapper;
    private final Path modelDirectory;
    private final int seed;
    private final double configuredTestSize;
    private final int configuredFolds;
    private final double riskLow;
    private final double riskHigh;
    private final int maxDepth;
    private final double learningRate;
    private final int estimators;
    private final double subsample;
    private final double columnSample;
    private final int minChildWeight;

    public ModelService(ObjectMapper mapper,
                        @Value("${carecontinuum.model-dir:models}") String modelDirectory,
                        @Value("${carecontinuum.random-seed:42}") int seed,
                        @Value("${carecontinuum.test-size:0.20}") double testSize,
                        @Value("${carecontinuum.cv-folds:5}") int folds,
                        @Value("${carecontinuum.risk-low-threshold:0.33}") double riskLow,
                        @Value("${carecontinuum.risk-high-threshold:0.66}") double riskHigh,
                        @Value("${carecontinuum.training.max-depth:4}") int maxDepth,
                        @Value("${carecontinuum.training.learning-rate:0.05}") double learningRate,
                        @Value("${carecontinuum.training.estimators:160}") int estimators,
                        @Value("${carecontinuum.training.subsample:0.85}") double subsample,
                        @Value("${carecontinuum.training.column-sample:0.85}") double columnSample,
                        @Value("${carecontinuum.training.min-child-weight:2}") int minChildWeight) {
        this.mapper = mapper;
        this.modelDirectory = Path.of(modelDirectory);
        this.seed = seed;
        this.configuredTestSize = testSize;
        this.configuredFolds = folds;
        this.riskLow = riskLow;
        this.riskHigh = riskHigh;
        this.maxDepth = maxDepth;
        this.learningRate = learningRate;
        this.estimators = estimators;
        this.subsample = subsample;
        this.columnSample = columnSample;
        this.minChildWeight = minChildWeight;
    }

    public ModelRun train(List<PatientFeatures> source, String mode, double testSize, int folds, List<String> requestedFeatures) throws XGBoostError {
        if (testSize == 0.0) testSize = configuredTestSize;
        if (folds == 0) folds = configuredFolds;
        if (testSize < 0.10 || testSize > 0.40) throw new IllegalArgumentException("Test fraction must be between 0.10 and 0.40.");
        if (folds < 2 || folds > 5) throw new IllegalArgumentException("Cross-validation folds must be between 2 and 5.");
        List<PatientFeatures> rows = source.stream().filter(row -> row.target() != null).toList();
        if (rows.size() < 30) throw new IllegalArgumentException("At least 30 labeled patients are required for model training.");
        List<String> selected = requestedFeatures == null || requestedFeatures.isEmpty()
                ? DataCatalog.NUMERIC_FEATURES.stream().filter(feature -> rows.stream().anyMatch(row -> row.value(feature) != null)).collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                : new ArrayList<>(requestedFeatures);
        selected.addAll(DataCatalog.CATEGORICAL_FEATURES.stream().filter(feature -> (requestedFeatures == null || requestedFeatures.isEmpty()) && rows.stream().anyMatch(row -> row.value(feature) != null)).toList());
        for (String feature : selected) {
            if (feature.equals("patient_id") || feature.equals("adverse_outcome") || !DataCatalog.NUMERIC_FEATURES.contains(feature) && !DataCatalog.CATEGORICAL_FEATURES.contains(feature)) {
                throw new IllegalArgumentException("Unsafe or unavailable input feature: " + feature);
            }
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("Choose at least one valid input feature.");
        Split split = stratifiedSplit(rows, testSize);
        int positives = (int) split.train().stream().filter(row -> row.target() == 1).count();
        int negatives = split.train().size() - positives;
        if (positives < 2 || negatives < 2) throw new IllegalArgumentException("At least two training patients in each outcome class are required.");
        double scalePosWeight = negatives / (double) positives;
        if (scalePosWeight < 1.5) scalePosWeight = 1.0;
        TrainingParameters configured = new TrainingParameters(maxDepth, learningRate, estimators, subsample, columnSample, minChildWeight, scalePosWeight);
        TrainingParameters parameters = mode.equalsIgnoreCase("tuned") ? tune(split.train(), selected, configured) : configured;

        FeatureEncoder encoder = new FeatureEncoder(selected);
        encoder.fit(split.train());
        Booster booster = fit(split.train(), encoder, parameters);
        double[] probabilities = predictProbabilities(booster, encoder, split.test());
        BinaryMetrics holdout = evaluate(split.test(), probabilities);
        int cvFolds = Math.max(2, Math.min(folds > 0 ? folds : configuredFolds,
                Math.min((int) split.train().stream().filter(row -> row.target() == 1).count(), (int) split.train().stream().filter(row -> row.target() == 0).count())));
        CrossValidation cv = crossValidate(split.train(), selected, parameters, scalePosWeight, cvFolds);
        Map<String, Object> parameterMap = parameters.asMap();
        parameterMap.put("random_state", seed);
        parameterMap.put("test_size", testSize);
        parameterMap.put("scale_pos_weight", scalePosWeight);
        ModelEvaluation evaluation = new ModelEvaluation(holdout.accuracy(), holdout.precision(), holdout.recall(), holdout.f1(), holdout.rocAuc(), holdout.prAuc(), holdout.confusion(), holdout.rocCurve(), holdout.prCurve(), cvFolds, cv.meanAuc(), cv.stdAuc(), cv.meanF1(), cv.meanRecall(), (int) rows.stream().filter(row -> row.target() == 1).count(), (int) rows.stream().filter(row -> row.target() == 0).count(), split.train().size(), split.test().size(), mode, parameterMap);
        return new ModelRun(booster, encoder, List.copyOf(selected), evaluation, Instant.now());
    }

    public PredictionResult predict(ModelRun model, PatientFeatures patient) throws XGBoostError {
        float[] row = model.encoder().transform(patient);
        DMatrix matrix = new DMatrix(row, 1, row.length, Float.NaN);
        try {
            float[][] raw = model.booster().predict(matrix);
            double probability = raw[0].length == 1 ? raw[0][0] : raw[0][1];
            float[][] shap = model.booster().predictContrib(matrix, 0);
            List<PredictionResult.Contribution> contributions = new ArrayList<>();
            List<String> names = model.encoder().transformedNames();
            for (int index = 0; index < names.size() && index < shap[0].length - 1; index++) {
                double value = shap[0][index];
                contributions.add(new PredictionResult.Contribution(names.get(index), value, value >= 0 ? "Increases model score" : "Decreases model score"));
            }
            contributions.sort(Comparator.comparingDouble(item -> -Math.abs(item.value())));
            String category = probability < riskLow ? "Lower predicted risk" : probability <= riskHigh ? "Intermediate predicted risk" : "Higher predicted risk";
            return new PredictionResult(probability, category, contributions.stream().limit(12).toList());
        } finally { matrix.dispose(); }
    }

    public Map<String, Double> globalImportance(ModelRun model, List<PatientFeatures> rows, int maximumRows) throws XGBoostError {
        List<PatientFeatures> sample = new ArrayList<>(rows);
        Collections.shuffle(sample, new Random(seed));
        sample = sample.stream().limit(Math.max(1, maximumRows)).toList();
        if (sample.isEmpty()) return Map.of();
        DMatrix matrix = matrix(sample, model.encoder(), false);
        try {
            float[][] values = model.booster().predictContrib(matrix, 0);
            Map<String, Double> importance = new LinkedHashMap<>();
            List<String> names = model.encoder().transformedNames();
            for (int column = 0; column < names.size(); column++) {
                double mean = 0;
                for (float[] value : values) mean += Math.abs(value[column]);
                importance.put(names.get(column), mean / values.length);
            }
            return importance.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        } finally { matrix.dispose(); }
    }

    public Map<String, Double> scoreCohort(ModelRun model, List<PatientFeatures> rows) throws XGBoostError {
        List<PatientFeatures> ordered = List.copyOf(rows);
        double[] probabilities = predictProbabilities(model.booster(), model.encoder(), ordered);
        Map<String, Double> result = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) result.put(ordered.get(index).patientId(), probabilities[index]);
        return result;
    }

    public void save(ModelRun model, String datasetLabel, int visits) throws Exception {
        Files.createDirectories(modelDirectory);
        model.booster().saveModel(modelDirectory.resolve("xgboost_model.ubj").toString());
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(modelDirectory.resolve("preprocessor.ser")))) { output.writeObject(model.encoder()); }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model_name", "XGBoost4J Booster");
        metadata.put("training_timestamp_utc", model.trainedAt().toString());
        metadata.put("input_features", model.inputFeatures());
        metadata.put("transformed_feature_names", model.encoder().transformedNames());
        metadata.put("evaluation_metrics", model.evaluation());
        metadata.put("dataset_label", datasetLabel);
        metadata.put("visit_count", visits);
        metadata.put("disclaimer", "Research prototype; dataset-specific metrics do not establish clinical validity.");
        mapper.writerWithDefaultPrettyPrinter().writeValue(modelDirectory.resolve("model_metadata.json").toFile(), metadata);
    }

    public ModelRun loadSaved() throws Exception {
        Path modelPath = modelDirectory.resolve("xgboost_model.ubj");
        Path encoderPath = modelDirectory.resolve("preprocessor.ser");
        if (!Files.exists(modelPath) || !Files.exists(encoderPath)) throw new IllegalStateException("Model has not been trained and saved yet.");
        Booster booster = XGBoost.loadModel(modelPath.toString());
        FeatureEncoder encoder;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(encoderPath))) { encoder = (FeatureEncoder) input.readObject(); }
        ModelEvaluation evaluation = null;
        Path metadataPath = modelDirectory.resolve("model_metadata.json");
        if (Files.exists(metadataPath)) {
            var metadata = mapper.readTree(metadataPath.toFile());
            if (metadata.hasNonNull("evaluation_metrics")) evaluation = mapper.treeToValue(metadata.get("evaluation_metrics"), ModelEvaluation.class);
        }
        return new ModelRun(booster, encoder, encoder.inputFeatures(), evaluation, Instant.now());
    }

    public List<String> defaultFeatureChoices(List<PatientFeatures> rows) {
        List<String> result = new ArrayList<>();
        for (String name : DataCatalog.NUMERIC_FEATURES) if (rows.stream().anyMatch(row -> row.value(name) != null)) result.add(name);
        for (String name : DataCatalog.CATEGORICAL_FEATURES) if (rows.stream().anyMatch(row -> row.value(name) != null)) result.add(name);
        return result;
    }

    private TrainingParameters tune(List<PatientFeatures> rows, List<String> selected, TrainingParameters base) throws XGBoostError {
        List<TrainingParameters> candidates = List.of(
                new TrainingParameters(Math.max(2, base.maxDepth() - 1), base.learningRate(), Math.max(60, base.rounds() * 3 / 5), base.subsample(), base.colsample(), base.minChildWeight(), base.scalePosWeight()),
                base,
                new TrainingParameters(Math.min(8, base.maxDepth() + 1), base.learningRate() * 0.6, base.rounds(), Math.max(0.65, base.subsample() - 0.05), base.colsample(), base.minChildWeight() + 1, base.scalePosWeight()),
                new TrainingParameters(base.maxDepth(), base.learningRate() * 1.5, Math.max(60, base.rounds() * 2 / 3), Math.min(1.0, base.subsample() + 0.05), Math.max(0.65, base.colsample() - 0.05), Math.max(1, base.minChildWeight() - 1), base.scalePosWeight()));
        int folds = Math.min(3, Math.min((int) rows.stream().filter(row -> row.target() == 1).count(), (int) rows.stream().filter(row -> row.target() == 0).count()));
        TrainingParameters best = candidates.getFirst();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (TrainingParameters candidate : candidates) {
            CrossValidation cv = crossValidate(rows, selected, candidate, candidate.scalePosWeight(), folds);
            if (cv.meanAuc() > bestScore) { best = candidate; bestScore = cv.meanAuc(); }
        }
        return best;
    }

    private CrossValidation crossValidate(List<PatientFeatures> rows, List<String> selected, TrainingParameters parameters, double weight, int folds) throws XGBoostError {
        if (folds < 2) return new CrossValidation(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        List<List<PatientFeatures>> partitions = stratifiedFolds(rows, folds);
        List<Double> aucs = new ArrayList<>(), f1s = new ArrayList<>(), recalls = new ArrayList<>();
        for (int fold = 0; fold < folds; fold++) {
            List<PatientFeatures> valid = partitions.get(fold);
            List<PatientFeatures> train = new ArrayList<>();
            for (int other = 0; other < folds; other++) if (other != fold) train.addAll(partitions.get(other));
            FeatureEncoder encoder = new FeatureEncoder(selected);
            encoder.fit(train);
            Booster booster = fit(train, encoder, parameters);
            BinaryMetrics metrics = evaluate(valid, predictProbabilities(booster, encoder, valid));
            aucs.add(metrics.rocAuc()); f1s.add(metrics.f1()); recalls.add(metrics.recall());
            booster.dispose();
        }
        return new CrossValidation(mean(aucs), std(aucs), mean(f1s), mean(recalls));
    }

    private Booster fit(List<PatientFeatures> rows, FeatureEncoder encoder, TrainingParameters parameters) throws XGBoostError {
        DMatrix matrix = matrix(rows, encoder, true);
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("objective", "binary:logistic"); config.put("eval_metric", "logloss"); config.put("tree_method", "hist");
            config.put("max_depth", parameters.maxDepth()); config.put("eta", parameters.learningRate());
            config.put("subsample", parameters.subsample()); config.put("colsample_bytree", parameters.colsample());
            config.put("min_child_weight", parameters.minChildWeight()); config.put("lambda", 1.0);
            config.put("scale_pos_weight", parameters.scalePosWeight()); config.put("seed", seed); config.put("nthread", 4);
            return XGBoost.train(matrix, config, parameters.rounds(), Map.of(), null, null, null, 0);
        } finally { matrix.dispose(); }
    }

    private static DMatrix matrix(List<PatientFeatures> rows, FeatureEncoder encoder, boolean labels) throws XGBoostError {
        int columns = encoder.transformedFeatureCount();
        float[] matrix = encoder.transform(rows);
        DMatrix result = new DMatrix(matrix, rows.size(), columns, Float.NaN);
        if (labels) result.setLabel(rows.stream().mapToDouble(row -> row.target()).collect(() -> new FloatList(rows.size()), FloatList::add, FloatList::combine).toArray());
        return result;
    }

    private static double[] predictProbabilities(Booster model, FeatureEncoder encoder, List<PatientFeatures> rows) throws XGBoostError {
        DMatrix matrix = matrix(rows, encoder, false);
        try {
            float[][] raw = model.predict(matrix);
            double[] result = new double[raw.length];
            for (int index = 0; index < raw.length; index++) result[index] = raw[index].length == 1 ? raw[index][0] : raw[index][1];
            return result;
        } finally { matrix.dispose(); }
    }

    private Split stratifiedSplit(List<PatientFeatures> rows, double testSize) {
        List<PatientFeatures> negatives = new ArrayList<>(), positives = new ArrayList<>();
        rows.forEach(row -> (row.target() == 1 ? positives : negatives).add(row));
        Collections.shuffle(negatives, new Random(seed)); Collections.shuffle(positives, new Random(seed + 1L));
        List<PatientFeatures> train = new ArrayList<>(), test = new ArrayList<>();
        splitClass(negatives, testSize, train, test); splitClass(positives, testSize, train, test);
        Collections.shuffle(train, new Random(seed + 2L)); Collections.shuffle(test, new Random(seed + 3L));
        return new Split(train, test);
    }

    private static void splitClass(List<PatientFeatures> group, double testSize, List<PatientFeatures> train, List<PatientFeatures> test) {
        int testCount = Math.max(1, Math.min(group.size() - 1, (int) Math.round(group.size() * testSize)));
        test.addAll(group.subList(0, testCount)); train.addAll(group.subList(testCount, group.size()));
    }

    private List<List<PatientFeatures>> stratifiedFolds(List<PatientFeatures> rows, int folds) {
        List<List<PatientFeatures>> parts = new ArrayList<>();
        for (int index = 0; index < folds; index++) parts.add(new ArrayList<>());
        List<PatientFeatures> zero = new ArrayList<>(), one = new ArrayList<>();
        rows.forEach(row -> (row.target() == 1 ? one : zero).add(row));
        Collections.shuffle(zero, new Random(seed + folds)); Collections.shuffle(one, new Random(seed + folds + 1L));
        for (int i = 0; i < zero.size(); i++) parts.get(i % folds).add(zero.get(i));
        for (int i = 0; i < one.size(); i++) parts.get(i % folds).add(one.get(i));
        return parts;
    }

    private static BinaryMetrics evaluate(List<PatientFeatures> rows, double[] probability) {
        int[][] confusion = new int[2][2];
        int tp = 0, fp = 0, fn = 0;
        double correct = 0;
        for (int i = 0; i < rows.size(); i++) {
            int actual = rows.get(i).target(), predicted = probability[i] >= 0.5 ? 1 : 0;
            confusion[actual][predicted]++;
            if (actual == predicted) correct++;
            if (actual == 1 && predicted == 1) tp++;
            else if (actual == 0 && predicted == 1) fp++;
            else if (actual == 1) fn++;
            else { /* The confusion matrix already records true negatives. */ }
        }
        double precision = tp / (double) Math.max(1, tp + fp), recall = tp / (double) Math.max(1, tp + fn);
        double f1 = 2 * precision * recall / Math.max(1.0e-12, precision + recall);
        List<List<Double>> roc = new ArrayList<>(), pr = new ArrayList<>();
        long positives = rows.stream().filter(row -> row.target() == 1).count();
        long negatives = rows.size() - positives;
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < probability.length; index++) order.add(index);
        order.sort(Comparator.comparingDouble((Integer index) -> probability[index]).reversed());
        int truePositive = 0, falsePositive = 0;
        roc.add(List.of(0.0, 0.0));
        pr.add(List.of(0.0, positives == 0 ? 1.0 : 0.0));
        int cursor = 0;
        while (cursor < order.size()) {
            double threshold = probability[order.get(cursor)];
            int end = cursor;
            while (end < order.size() && Double.compare(probability[order.get(end)], threshold) == 0) {
                if (rows.get(order.get(end)).target() == 1) truePositive++;
                else falsePositive++;
                end++;
            }
            double tpr = truePositive / (double) Math.max(1L, positives);
            double fpr = falsePositive / (double) Math.max(1L, negatives);
            roc.add(List.of(fpr, tpr));
            pr.add(List.of(tpr, truePositive / (double) Math.max(1, truePositive + falsePositive)));
            cursor = end;
        }
        return new BinaryMetrics(correct / Math.max(1, rows.size()), precision, recall, f1, auc(rows, probability), averagePrecision(rows, probability), confusion, roc, pr);
    }

    private static double auc(List<PatientFeatures> rows, double[] scores) {
        long positives = rows.stream().filter(row -> row.target() == 1).count(), negatives = rows.size() - positives;
        if (positives == 0 || negatives == 0) return Double.NaN;
        double wins = 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).target() == 1) for (int j = 0; j < rows.size(); j++) if (rows.get(j).target() == 0) wins += scores[i] > scores[j] ? 1 : scores[i] == scores[j] ? 0.5 : 0;
        return wins / (positives * (double) negatives);
    }

    private static double averagePrecision(List<PatientFeatures> rows, double[] scores) {
        int positives = (int) rows.stream().filter(row -> row.target() == 1).count();
        if (positives == 0) return Double.NaN;
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < scores.length; index++) order.add(index);
        order.sort(Comparator.comparingDouble((Integer index) -> scores[index]).reversed());
        int seen = 0, trueSeen = 0;
        double total = 0;
        for (int index : order) {
            seen++;
            if (rows.get(index).target() == 1) { trueSeen++; total += trueSeen / (double) seen; }
        }
        return total / positives;
    }

    private static double mean(List<Double> values) { return values.stream().mapToDouble(value -> value).average().orElse(Double.NaN); }
    private static double std(List<Double> values) { double mean = mean(values); return Math.sqrt(values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum() / Math.max(1, values.size() - 1)); }

    private record Split(List<PatientFeatures> train, List<PatientFeatures> test) {}
    private record CrossValidation(double meanAuc, double stdAuc, double meanF1, double meanRecall) {}
    private record BinaryMetrics(double accuracy, double precision, double recall, double f1, double rocAuc, double prAuc, int[][] confusion, List<List<Double>> rocCurve, List<List<Double>> prCurve) {}
    private record TrainingParameters(int maxDepth, double learningRate, int rounds, double subsample, double colsample, int minChildWeight, double scalePosWeight) {
        Map<String, Object> asMap() { Map<String, Object> result = new LinkedHashMap<>(); result.put("max_depth", maxDepth); result.put("learning_rate", learningRate); result.put("n_estimators", rounds); result.put("subsample", subsample); result.put("colsample_bytree", colsample); result.put("min_child_weight", minChildWeight); return result; }
    }
    private static final class FloatList {
        private final float[] values; private int index;
        FloatList(int size) { values = new float[size]; }
        void add(double value) { values[index++] = (float) value; }
        void combine(FloatList other) { for (int i = 0; i < other.index; i++) add(other.values[i]); }
        float[] toArray() { return values; }
    }
}
