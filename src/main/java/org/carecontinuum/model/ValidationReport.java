package org.carecontinuum.model;

import java.util.List;
import java.util.Map;

/** Dataset validation summary and row-level issues suitable for CSV download. */
public record ValidationReport(
        int totalRows,
        int uniquePatients,
        int uniqueProviders,
        String dateRange,
        int missingValues,
        int duplicateRows,
        int invalidRecords,
        List<String> missingColumns,
        List<Map<String, Object>> issues
) {}
