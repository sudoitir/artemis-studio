package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.persist.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyRequest;
import io.github.sudoitir.artemisstudio.service.BrokerConfigApplyService;
import io.github.sudoitir.artemisstudio.service.BrokerConfigDriftService;
import io.github.sudoitir.artemisstudio.service.BrokerConfigRecommendationService;
import io.github.sudoitir.artemisstudio.service.BrokerConfigService;
import io.github.sudoitir.artemisstudio.service.BrokerConfigService.Source;
import io.github.sudoitir.artemisstudio.service.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigRequests.ApplyRequest;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigRequests.ConfigureRequest;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigRequests.DeclareRecommendedRequest;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigRequests.SaveDeclarationRequest;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.AdoptionView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.ApplyDetailView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.ApplyHistoryView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.ApplyOutcomeView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.CatalogueView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.DeclarationView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.DriftReportView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.ImportResultView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.NodeApplyView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.PlanView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.RecommendationsView;
import io.github.sudoitir.artemisstudio.web.dto.BrokerConfigViews.RevisionView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A cluster's declared configuration: what it should be, what each node is, and
 * the apply that closes the gap (ADR-0067).
 *
 * <p>Reads need {@code cluster:read}. Editing the declaration — saving a revision,
 * importing XML, adopting, changing the mode — needs {@code config:write}. Writing
 * to a broker needs {@code config:apply}; it is a separate authority because a
 * declaration is harmless until something applies it.
 *
 * <p>Every apply takes {@code ?dryRun=true} and returns the plan without touching a
 * broker; the real run must echo that plan's hash and acknowledge its High hazards,
 * so what the operator confirmed is what runs.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/config")
@RequiredArgsConstructor
public class BrokerConfigController {

    private final BrokerConfigService config;
    private final BrokerConfigDriftService drift;
    private final BrokerConfigApplyService apply;
    private final BrokerConfigRecommendationService recommendations;
    private final ClusterAccessGuard clusterAccess;

    // ---- declaration ------------------------------------------------------

    @GetMapping
    public DeclarationView get(@PathVariable UUID clusterId) {
        return DeclarationView.of(config.get(clusterId));
    }

    /** Save a new revision; {@code 409 stale-revision} when someone saved first. */
    @PutMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationView save(@PathVariable UUID clusterId, @Valid @RequestBody SaveDeclarationRequest request) {
        return DeclarationView.of(config.save(
                clusterId,
                request.document().toDocument(),
                request.expectedRevision(),
                request.note(),
                request.source() == null ? Source.EDIT : Source.valueOf(request.source()),
                request.confirm()));
    }

    @PatchMapping("/mode")
    public DeclarationView configure(@PathVariable UUID clusterId, @Valid @RequestBody ConfigureRequest request) {
        return DeclarationView.of(config.configure(
                clusterId,
                ApplyMode.valueOf(request.applyMode()),
                request.reportUndeclared(),
                request.undeclaredExclusions()));
    }

    @GetMapping("/revisions")
    public List<RevisionView> revisions(@PathVariable UUID clusterId) {
        return config.revisions(clusterId).stream().map(RevisionView::of).toList();
    }

    @GetMapping("/revisions/{number}")
    public RevisionView revision(@PathVariable UUID clusterId, @PathVariable int number) {
        return RevisionView.of(config.revision(clusterId, number));
    }

    /** Parse a {@code broker.xml} or fragment into a declaration. Nothing is saved. */
    @PostMapping(
            value = "/import-xml",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ImportResultView importXml(@PathVariable UUID clusterId, @RequestBody String xml) {
        return ImportResultView.of(config.importXml(clusterId, xml));
    }

    /** The declaration as a {@code <core>} fragment, for a config-managed cluster. */
    @GetMapping(value = "/export-xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String exportXml(@PathVariable UUID clusterId, @RequestParam(required = false) Integer revision) {
        return config.exportXml(clusterId, revision);
    }

    /** Build a declaration from what the live nodes are running. Nothing is saved. */
    @PostMapping("/adopt")
    public AdoptionView adopt(@PathVariable UUID clusterId) {
        return AdoptionView.of(config.adopt(clusterId));
    }

    // ---- recommendations (ADR-0068) ---------------------------------------

    /** What the capability probe suggests declaring, and what still needs broker.xml. */
    @GetMapping("/recommendations")
    public RecommendationsView recommendations(@PathVariable UUID clusterId) {
        return RecommendationsView.of(recommendations.recommend(clusterId));
    }

    /**
     * Declare the appliable recommendations as a new revision. Nothing is written to
     * a broker: the caller opens the plan and applies it through the ordinary gates.
     */
    @PostMapping("/recommendations/declare")
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationView declareRecommended(
            @PathVariable UUID clusterId, @RequestBody(required = false) DeclareRecommendedRequest request) {
        DeclareRecommendedRequest body = request == null ? new DeclareRecommendedRequest(null, null) : request;
        return DeclarationView.of(recommendations.declare(clusterId, body.capabilities(), body.roles()));
    }

    /** Static, but cluster-addressed: an ungranted caller must not learn the cluster exists. */
    @GetMapping("/catalogue")
    public CatalogueView catalogue(@PathVariable UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return CatalogueView.current();
    }

    // ---- drift ------------------------------------------------------------

    @GetMapping("/drift")
    public DriftReportView drift(@PathVariable UUID clusterId) {
        return DriftReportView.of(config.get(clusterId));
    }

    /** Evaluate every live node now. A read of the brokers; nothing is changed. */
    @PostMapping("/drift/evaluate")
    public DriftReportView evaluate(@PathVariable UUID clusterId) {
        return DriftReportView.of(drift.evaluate(clusterId));
    }

    // ---- apply ------------------------------------------------------------

    /**
     * Apply the declaration, canary first. With {@code ?dryRun=true} the plan is
     * returned with its hazards and hash and no broker is written; the real run
     * refuses when the plan changed since the preview, when a High hazard is not
     * acknowledged, or when another apply holds the cluster.
     */
    @PostMapping("/apply")
    public ApplyOutcomeView apply(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestBody(required = false) ApplyRequest request) {
        ApplyRequest body = request == null ? new ApplyRequest(null, null, null, null, null, null) : request;
        BrokerConfigApplyRequest command = new BrokerConfigApplyRequest(
                body.revision(),
                body.nodeIds(),
                body.canaryNodeId(),
                Boolean.TRUE.equals(body.removeUndeclared()),
                body.acknowledgedHazards(),
                body.expectedPlanHash(),
                override);
        return ApplyOutcomeView.of(dryRun ? apply.plan(clusterId, command) : apply.apply(clusterId, command));
    }

    @GetMapping("/applies")
    public List<ApplyHistoryView> applies(@PathVariable UUID clusterId, @RequestParam(defaultValue = "50") int limit) {
        return apply.history(clusterId, limit).stream()
                .map(ApplyHistoryView::of)
                .toList();
    }

    @GetMapping("/applies/{id}")
    public ApplyDetailView applyDetail(@PathVariable UUID clusterId, @PathVariable long id) {
        BrokerConfigApplyService.Detail d =
                apply.detail(clusterId, id).orElseThrow(() -> new NotFoundException("apply", id));
        return new ApplyDetailView(
                ApplyHistoryView.of(d.apply()),
                PlanView.of(d.plan()),
                d.nodes().stream().map(NodeApplyView::of).toList());
    }
}
