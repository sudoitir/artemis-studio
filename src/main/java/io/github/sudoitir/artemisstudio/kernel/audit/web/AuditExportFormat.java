package io.github.sudoitir.artemisstudio.kernel.audit.web;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditQueryService;
import io.github.sudoitir.artemisstudio.kernel.audit.web.AuditViews.AuditExportRow;
import io.github.sudoitir.artemisstudio.kernel.core.HeavyRequests;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SequenceWriter;
import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvSchema;

/** The files an audit export can be: one row per audit event, in the same columns in each. */
enum AuditExportFormat {
    CSV("text/csv"),
    JSON("application/json"),
    NDJSON("application/x-ndjson");

    private static final String PARAMS = "params";
    private static final String OUTCOME_DETAIL = "outcomeDetail";

    private static final List<String> COLUMNS = List.of(
            "id",
            "ts",
            "user",
            "action",
            "targetType",
            "targetName",
            "cluster",
            "outcome",
            "affectedCount",
            "error",
            "dryRun",
            "requestId",
            "parentId",
            "sourceIp",
            PARAMS,
            OUTCOME_DETAIL);

    private static final CsvMapper CSV_MAPPER = CsvMapper.builder().build();
    private static final CsvSchema CSV_SCHEMA = CsvSchema.builder()
            .addColumns(COLUMNS, CsvSchema.ColumnType.STRING)
            .setUseHeader(true)
            .build();

    /** A row of rows being written to one response. Closing it finishes the file. */
    interface Sink extends AutoCloseable, AuditQueryService.ExportSink {

        @Override
        void close();

        @Override
        default void finish() {
            close();
        }
    }

    private final String contentType;

    AuditExportFormat(String contentType) {
        this.contentType = contentType;
    }

    String contentType() {
        return contentType;
    }

    String extension() {
        return name().toLowerCase(Locale.ROOT);
    }

    static AuditExportFormat parse(String value) {
        return Arrays.stream(values())
                .filter(f -> f.extension().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown export format '" + value + "'; use csv, json or ndjson."));
    }

    Sink open(OutputStream out, ObjectMapper mapper) {
        return switch (this) {
            case CSV -> sink(CSV_MAPPER.writer(CSV_SCHEMA).writeValues(out), row -> csv(row), bound -> csvMark(bound));
            case JSON ->
                sink(mapper.writer().writeValuesAsArray(out), row -> json(row, mapper), AuditExportFormat::jsonMark);
            case NDJSON ->
                sink(
                        mapper.writer().withRootValueSeparator("\n").writeValues(out),
                        row -> json(row, mapper),
                        AuditExportFormat::jsonMark);
        };
    }

    /** The mark that ends a file that stopped at a bound: an element of its own, not a row of the trail. */
    private static Map<String, Object> jsonMark(HeavyRequests.Bound bound) {
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("truncated", true);
        mark.put("bound", bound.name());
        return mark;
    }

    private static Map<String, String> csvMark(HeavyRequests.Bound bound) {
        Map<String, String> mark = new LinkedHashMap<>();
        mark.put("id", "# incomplete: the " + bound.name() + " bound was reached; narrow the time range");
        return mark;
    }

    private static Sink sink(
            SequenceWriter writer,
            java.util.function.Function<AuditExportRow, Object> columns,
            java.util.function.Function<HeavyRequests.Bound, Object> mark) {
        return new Sink() {
            @Override
            public void write(AuditExportRow row) {
                writer.write(columns.apply(row));
            }

            @Override
            public void truncated(HeavyRequests.Bound bound) {
                writer.write(mark.apply(bound));
            }

            @Override
            public void close() {
                writer.close();
            }
        };
    }

    /** Nested JSON for the two JSON formats. */
    private static Map<String, Object> json(AuditExportRow row, ObjectMapper mapper) {
        Map<String, Object> columns = columns(row);
        columns.put(PARAMS, tree(row.event().params(), mapper));
        columns.put(OUTCOME_DETAIL, tree(row.outcomeDetail(), mapper));
        return columns;
    }

    private static JsonNode tree(String json, ObjectMapper mapper) {
        return json == null ? null : mapper.readTree(json);
    }

    /** Text cells for CSV, with anything a spreadsheet would run as a formula neutralised. */
    private static Map<String, String> csv(AuditExportRow row) {
        Map<String, String> cells = new LinkedHashMap<>();
        columns(row)
                .forEach((column, value) -> cells.put(column, value == null ? null : neutralised(value.toString())));
        return cells;
    }

    /** A leading quote makes a spreadsheet show the cell as text. */
    private static String neutralised(String cell) {
        return !cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0 ? "'" + cell : cell;
    }

    private static Map<String, Object> columns(AuditExportRow row) {
        var e = row.event();
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("id", e.id());
        columns.put("ts", e.ts().toString());
        columns.put("user", e.username());
        columns.put("action", e.action());
        columns.put("targetType", e.targetType());
        columns.put("targetName", e.targetName());
        columns.put("cluster", e.clusterName());
        columns.put("outcome", e.outcome());
        columns.put("affectedCount", e.affectedCount());
        columns.put("error", e.error());
        columns.put("dryRun", e.dryRun());
        columns.put("requestId", e.requestId());
        columns.put("parentId", e.parentId());
        columns.put("sourceIp", e.sourceIp());
        columns.put(PARAMS, e.params());
        columns.put(OUTCOME_DETAIL, row.outcomeDetail());
        return columns;
    }
}
