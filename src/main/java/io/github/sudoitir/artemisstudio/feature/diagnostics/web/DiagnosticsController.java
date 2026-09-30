package io.github.sudoitir.artemisstudio.feature.diagnostics.web;

import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService;
import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService.Section;
import io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsService.Snapshot;
import io.github.sudoitir.artemisstudio.feature.diagnostics.web.DiagnosticsViews.BundleView;
import io.github.sudoitir.artemisstudio.feature.diagnostics.web.DiagnosticsViews.DownloadRequest;
import io.github.sudoitir.artemisstudio.feature.diagnostics.web.DiagnosticsViews.SummaryView;
import io.github.sudoitir.artemisstudio.kernel.core.Branding;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The bug-report summary for any signed-in user, and the support bundle for an administrator (diagnostics spec):
 * prepare a snapshot to preview, then download the kept sections of it as a zip.
 */
@RestController
@RequiredArgsConstructor
public class DiagnosticsController {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final String FILE_PREFIX =
            Branding.PRODUCT_NAME.toLowerCase(Locale.ROOT).replace(' ', '-');

    private final DiagnosticsService diagnostics;

    @GetMapping("/diagnostics/summary")
    public SummaryView summary() {
        return SummaryView.of(diagnostics.summary());
    }

    @PostMapping("/admin/diagnostics/bundles")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleView prepare() {
        return BundleView.of(diagnostics.prepare());
    }

    @PostMapping(value = "/admin/diagnostics/bundles/{id}/download", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID id, @Valid @RequestBody DownloadRequest request) {
        Snapshot bundle = diagnostics.take(id, request.sections());
        String fileName = "%s-diagnostics-%s.zip".formatted(FILE_PREFIX, STAMP.format(bundle.createdAt()));
        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (Section section : bundle.sections()) {
                    ZipEntry entry = new ZipEntry(section.fileName());
                    entry.setTime(bundle.createdAt().toEpochMilli());
                    zip.putNextEntry(entry);
                    zip.write(section.content().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName)
                                .build()
                                .toString())
                .body(body);
    }
}
