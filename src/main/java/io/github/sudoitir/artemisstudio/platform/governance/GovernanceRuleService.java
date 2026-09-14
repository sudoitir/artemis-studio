package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.platform.governance.internal.persistence.GovernanceRuleEntity;
import io.github.sudoitir.artemisstudio.platform.governance.internal.persistence.GovernanceRuleRepository;
import io.github.sudoitir.artemisstudio.platform.governance.web.GovernanceRuleViews.RuleRequest;
import io.github.sudoitir.artemisstudio.platform.governance.web.GovernanceRuleViews.RuleView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Masking-rule administration (data-governance spec). Every write is audited in its own transaction
 * and bumps the policy version there too, so a stored row can tell it was masked under an older policy.
 */
@Service
@RequiredArgsConstructor
public class GovernanceRuleService {

    static final String TARGET_TYPE = "GOVERNANCE_RULE";

    private final GovernanceRuleRepository rules;
    private final AuditService audit;
    private final ActorResolver actors;
    private final ApplicationEventPublisher events;

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.governance.GovernancePermissions).GOVERNANCE_READ)")
    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderByBuiltinDescCreatedAtAsc().stream()
                .map(GovernanceRuleService::toView)
                .toList();
    }

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.governance.GovernancePermissions).GOVERNANCE_WRITE)")
    @Transactional
    public RuleView create(RuleRequest request) {
        AuditEvent event = begin("CREATE_GOVERNANCE_RULE", request.selector(), describe(request));
        GovernanceRuleEntity entity = new GovernanceRuleEntity(
                blankToNull(request.addressPattern()),
                request.target(),
                request.selector().trim(),
                request.dataClass(),
                request.action(),
                false);
        entity.setEnabled(request.enabled());
        GovernanceRuleEntity saved = rules.saveAndFlush(entity);
        changed();
        audit.succeed(event, 1);
        return toView(saved);
    }

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.governance.GovernancePermissions).GOVERNANCE_WRITE)")
    @Transactional
    public RuleView update(UUID id, RuleRequest request) {
        GovernanceRuleEntity entity = require(id);
        if (entity.isBuiltin() && !sameFacts(entity, request)) {
            throw new ConflictException(
                    "builtin-rule-immutable",
                    "Built-in rule '" + entity.getSelector() + "' can only be enabled or disabled.");
        }
        Map<String, Object> params = describe(request);
        params.put("wasEnabled", entity.isEnabled());
        String action = entity.isEnabled() && !request.enabled()
                ? "DISABLE_GOVERNANCE_RULE"
                : !entity.isEnabled() && request.enabled() ? "ENABLE_GOVERNANCE_RULE" : "UPDATE_GOVERNANCE_RULE";
        AuditEvent event = begin(action, entity.getSelector(), params);
        if (!entity.isBuiltin()) {
            entity.setAddressPattern(blankToNull(request.addressPattern()));
            entity.setTarget(request.target());
            entity.setSelector(request.selector().trim());
            entity.setDataClass(request.dataClass());
            entity.setAction(request.action());
        }
        entity.setEnabled(request.enabled());
        GovernanceRuleEntity saved = rules.saveAndFlush(entity);
        changed();
        audit.succeed(event, 1);
        return toView(saved);
    }

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.governance.GovernancePermissions).GOVERNANCE_WRITE)")
    @Transactional
    public void delete(UUID id) {
        GovernanceRuleEntity entity = require(id);
        if (entity.isBuiltin()) {
            throw new ConflictException(
                    "builtin-rule-not-deletable",
                    "Built-in rule '" + entity.getSelector() + "' cannot be deleted. Disable it instead.");
        }
        AuditEvent event = begin(
                "DELETE_GOVERNANCE_RULE",
                entity.getSelector(),
                Map.of("target", entity.getTarget(), "dataClass", entity.getDataClass()));
        rules.delete(entity);
        rules.flush();
        changed();
        audit.succeed(event, 1);
    }

    /** Create a rule on behalf of another governance action (confirming a finding), already audited by it. */
    GovernanceRuleEntity createUnaudited(GovernanceRuleEntity entity) {
        GovernanceRuleEntity saved = rules.saveAndFlush(entity);
        changed();
        return saved;
    }

    private void changed() {
        rules.bumpPolicyVersion();
        events.publishEvent(new PolicyStore.PolicyChanged());
    }

    private AuditEvent begin(String action, String targetName, Map<String, ?> params) {
        return audit.begin(actors.resolve(), action, TARGET_TYPE, targetName, null, null, params, false);
    }

    private GovernanceRuleEntity require(UUID id) {
        return rules.findById(id).orElseThrow(() -> new NotFoundException("governance rule", id));
    }

    private static boolean sameFacts(GovernanceRuleEntity e, RuleRequest r) {
        return java.util.Objects.equals(e.getAddressPattern(), blankToNull(r.addressPattern()))
                && e.getTarget().equals(r.target())
                && e.getSelector().equals(r.selector().trim())
                && e.getDataClass().equals(r.dataClass())
                && java.util.Objects.equals(e.getAction(), r.action());
    }

    private static Map<String, Object> describe(RuleRequest r) {
        Map<String, Object> params = new HashMap<>();
        params.put("target", r.target());
        params.put("dataClass", r.dataClass());
        params.put("enabled", r.enabled());
        if (r.addressPattern() != null) {
            params.put("addressPattern", r.addressPattern());
        }
        if (r.action() != null) {
            params.put("action", r.action());
        }
        return params;
    }

    static RuleView toView(GovernanceRuleEntity e) {
        DataClass dataClass = DataClass.valueOf(e.getDataClass());
        return new RuleView(
                e.getId(),
                e.getAddressPattern(),
                e.getTarget(),
                e.getSelector(),
                dataClass.name(),
                dataClass.label(),
                e.getAction(),
                e.isException()
                        ? Action.CLEAR.name()
                        : dataClass.defaultAction().name(),
                e.isBuiltin(),
                e.isEnabled(),
                e.isException(),
                e.getUpdatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
