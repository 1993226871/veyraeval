package io.agenteval.reporting;

import io.agenteval.security.CurrentActor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public final class ReportComparisonController {
    private final ComparisonService comparisons;
    private final ReportExporter exporter;
    private final ReportService reports;
    private final CurrentActor current;
    public ReportComparisonController(ComparisonService comparisons, ReportExporter exporter, ReportService reports, CurrentActor current) { this.comparisons=comparisons; this.exporter=exporter; this.reports=reports; this.current=current; }
    @GetMapping("/comparison") public ComparisonReport compare(Authentication auth, @RequestParam UUID left, @RequestParam UUID right) { return comparisons.compare(current.require(auth),left,right); }
    public record TrialPages(ReportService.Page left, ReportService.Page right) {}

    @GetMapping("/comparison/trials")
    public TrialPages comparisonTrials(Authentication auth, @RequestParam UUID left, @RequestParam UUID right,
                                      @RequestParam(required=false) String leftCursor,
                                      @RequestParam(required=false) String rightCursor) {
        var actor = current.require(auth);
        boolean firstPage = leftCursor == null && rightCursor == null;
        var empty = new ReportService.Page(java.util.List.of(), null);
        return new TrialPages(
            firstPage || leftCursor != null ? reports.trials(actor, left, leftCursor, 25) : empty,
            firstPage || rightCursor != null ? reports.trials(actor, right, rightCursor, 25) : empty);
    }

    @GetMapping("/experiments/{id}/export") public ResponseEntity<ReportExporter.Export> export(Authentication auth,@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=agenteval-"+id+".json").body(exporter.export(current.require(auth),id));
    }
}
