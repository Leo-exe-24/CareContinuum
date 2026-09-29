package org.carecontinuum.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.carecontinuum.model.Visit;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CSV/XLSX readers, explicit canonical mapping, and UTF-8 CSV exports. */
@Service
public class CsvDataService {
    public record RawTable(List<String> headers, List<Map<String, String>> rows) {}

    public RawTable read(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return readExcel(file.getInputStream());
        if (!name.endsWith(".csv")) throw new IllegalArgumentException("Supported uploads are CSV and Excel (.xlsx or .xls).");
        return readCsv(file.getInputStream());
    }

    public RawTable read(InputStream input) throws Exception { return readCsv(input); }

    public List<Visit> map(RawTable table, Map<String, String> mapping) {
        List<Visit> result = new ArrayList<>();
        for (Map<String, String> source : table.rows()) {
            Map<String, String> canonical = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) canonical.put(entry.getKey(), source.getOrDefault(entry.getValue(), ""));
            }
            result.add(new Visit(canonical));
        }
        return result;
    }

    public byte[] writeCsv(List<String> headers, List<? extends List<?>> rows) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(output, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            printer.printRecord(headers);
            for (List<?> row : rows) printer.printRecord(row.stream().map(CsvDataService::safeCsvValue).toList());
        }
        return output.toByteArray();
    }

    private RawTable readCsv(InputStream input) throws Exception {
        try (PushbackInputStream cleaned = new PushbackInputStream(input, 3)) {
            byte[] prefix = new byte[3];
            int count = cleaned.read(prefix);
            boolean hasUtf8Bom = count == 3 && (prefix[0] & 0xFF) == 0xEF && (prefix[1] & 0xFF) == 0xBB && (prefix[2] & 0xFF) == 0xBF;
            if (!hasUtf8Bom && count > 0) cleaned.unread(prefix, 0, count);
            try (CSVParser parser = CSVParser.parse(cleaned, StandardCharsets.UTF_8,
                    CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get())) {
                List<String> headers = parser.getHeaderNames();
                List<Map<String, String>> rows = new ArrayList<>();
                for (CSVRecord record : parser) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String header : headers) row.put(header, record.get(header));
                    rows.add(row);
                }
                if (headers.isEmpty()) throw new IllegalArgumentException("The uploaded CSV has no header row.");
                return new RawTable(headers, rows);
            }
        }
    }

    private RawTable readExcel(InputStream input) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("The workbook has no sheets.");
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) throw new IllegalArgumentException("The first worksheet is empty.");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> headers = new ArrayList<>();
            for (int col = 0; col < headerRow.getLastCellNum(); col++) headers.add(formatter.formatCellValue(headerRow.getCell(col)).trim());
            List<Map<String, String>> rows = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row dataRow = sheet.getRow(rowIndex);
                if (dataRow == null) continue;
                Map<String, String> row = new LinkedHashMap<>();
                boolean empty = true;
                for (int col = 0; col < headers.size(); col++) {
                    String value = formatter.formatCellValue(dataRow.getCell(col)).trim();
                    row.put(headers.get(col), value);
                    if (!value.isBlank()) empty = false;
                }
                if (!empty) rows.add(row);
            }
            return new RawTable(headers, rows);
        }
    }

    private static Object safeCsvValue(Object value) {
        if (!(value instanceof String text) || text.isEmpty()) return value;
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' ? "'" + text : value;
    }
}
