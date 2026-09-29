# CareContinuum

**A Java research prototype for exploring continuity of care and patient-level outcome prediction from longitudinal visit data.**

CareContinuum is a local web application. It loads synthetic visit records or a user-provided CSV/Excel file, calculates continuity measures, trains an XGBoost model, shows model explanations, and exports charts and reports.

> **Research prototype — not for clinical use.** Predictions and continuity indicators are experimental research outputs. They are not diagnoses, treatment recommendations, or evidence of clinical effectiveness.

## Features

- Responsive browser dashboard built with Java 21, Spring Boot, and Thymeleaf.
- Continuity measures: Bice–Boxerman Continuity of Care (COC), Usual Provider of Care (UPC), and Sequential Continuity (SECON).
- CSV, XLS, and XLSX upload with explicit column mapping and data validation.
- Patient-level feature aggregation and XGBoost binary classification.
- Hold-out evaluation metrics, confusion matrix, ROC and precision–recall plots.
- TreeSHAP feature contributions for model-level and individual explanations. Explanations describe model behavior; they do not establish causation.
- Continuity-gap signals with configurable prototype thresholds.
- CSV exports and patient/model PDF reports.
- Bundled local Chart.js asset; charts do not require a third-party CDN.
- Synthetic demo data: 2,000 fictional patients and 17,030 visits. No real patient records are included.

## Requirements

- JDK 21 or newer
- Apache Maven 3.9 or newer
- Internet access for Maven to download dependencies the first time you build

Check Java and Maven in PowerShell, Command Prompt, or a terminal:

```shell
java -version
mvn -version
```

## Run locally

1. Download and extract this repository, or clone it from GitHub.
2. Open a terminal in the project folder—the folder containing `pom.xml`.
3. Run the application with Maven:

```shell
mvn -f pom.xml org.springframework.boot:spring-boot-maven-plugin:3.5.16:run
```

4. Open [http://localhost:8080](http://localhost:8080) and select **Load synthetic demo**.
5. Stop the server by pressing `Ctrl+C` in the terminal.

The first demo model fit can take a little while, especially while native machine-learning libraries start up. The first Maven run also downloads the project dependencies.

The command above uses the full Spring Boot Maven plugin coordinates. This avoids the `No plugin found for prefix 'spring-boot'` message that can occur when Maven is not resolving the project from its `pom.xml`. If you are already in the project folder, the shorter `mvn spring-boot:run` command should also work.

## Run tests and build a JAR

Run the automated tests:

```shell
mvn -f pom.xml test
```

Build a runnable JAR:

```shell
mvn -f pom.xml package
```

Start the packaged JAR:

```shell
java -jar target/carecontinuum-1.0.0.jar
```

The JAR is created under `target/`. The `.gitignore` excludes `target/`, so compiled output is not uploaded with the source. Maven can recreate it on another computer.

## Upload a dataset

1. Open **Data Explorer** and choose a CSV or Excel workbook.
2. Map the source columns to the fields used by the app. The app will not invent patient or provider identifiers.
3. Review the validation summary for missing values, duplicate rows, invalid dates/outcomes, implausible ages, missing providers, and date range.
4. Explore the records and continuity metrics. To train the binary model, map a consistent outcome column to `adverse_outcome` using values such as `0`/`1`, `no`/`yes`, or `false`/`true`.

Continuity calculations require `patient_id`, `visit_date`, and `provider_id`. A patient needs at least two valid provider-linked visits for COC. For model training, each patient's visits must have a consistent outcome label; unlabeled or inconsistent patients are excluded.

Uploaded records are kept in the current server-side browser session while the app runs. This prototype has no database or multi-user account system. Do not upload real or identifiable patient data.

## How the analysis works

### Continuity measures

For valid provider-linked visits, Bice–Boxerman COC is calculated as:

```text
COC = (sum(provider_visit_count²) - N) / (N × (N - 1))
```

Here, `N` is the number of valid provider-linked visits. UPC is the largest provider's share of visits. SECON is the proportion of consecutive valid visit pairs with the same provider. These measures describe visit patterns; they do not measure every aspect of care quality. Display thresholds are configurable in `src/main/resources/application.yml` and are not clinical guidelines.

### Machine-learning model

The application aggregates visits into one row per patient before making a fixed-seed, stratified train/test split. This helps prevent visits from the same patient appearing in both partitions. Numeric medians, scaling values, and categorical levels are learned from training data only. The outcome label is not used as an input feature. Class weighting is calculated from the training partition; the application does not use SMOTE. Optional tuning uses a small bounded configuration search with cross-validation on training data.

The dashboard reports hold-out accuracy, precision, recall, F1, ROC-AUC, average precision, a confusion matrix, ROC points, and precision–recall points. Score bands are display labels, not calibrated or clinically validated risk categories.

TreeSHAP shows how the trained model's input features contribute to an output. Those contributions explain the model, not the causes of an outcome. The demo dataset's labels and relationships are invented, so demo metrics are not medical evidence.

## Project structure

```text
src/main/java/org/carecontinuum/
  model/       Domain records and patient features
  service/     Data validation, continuity, feature engineering, model, reports
  web/         Web controllers and page/download routes
src/main/resources/
  data/synthetic/  Bundled synthetic demo CSV
  templates/       Thymeleaf HTML templates
  static/          CSS, JavaScript, and local Chart.js
src/test/java/       Unit and model smoke tests
models/               Saved model files (created when requested)
reports/              Generated PDF reports (created when requested)
pom.xml                Maven project and dependency configuration
README.md              Project guide
```

## Limitations and data safety

- This is a research/hackathon prototype with no clinical validation or regulatory approval.
- The demo dataset and its correlations are invented and should not be treated as medical evidence.
- A random patient-level split does not establish performance on future time periods or other health systems.
- COC measures provider dispersion, not every dimension of continuity.
- The local single-user session design does not provide production authentication, authorization, encryption, audit logging, or privacy controls.
- Only use synthetic data while exploring this prototype. Do not use it to make patient-care decisions.

## License
 Copyright © 2026 Mohammad Liyakat. All rights reserved.
This repository is provided for viewing purposes only. Unauthorized copying, modification, distribution, or use of this code is prohibited.
This repository does not currently include a license for reuse. Public visibility on GitHub does not itself grant permission to reuse the project. Review third-party dependency licenses before redistribution or deployment.
