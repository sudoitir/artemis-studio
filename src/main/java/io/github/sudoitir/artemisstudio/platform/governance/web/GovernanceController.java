package io.github.sudoitir.artemisstudio.platform.governance.web;

import io.github.sudoitir.artemisstudio.platform.governance.GovernanceRuleService;
import io.github.sudoitir.artemisstudio.platform.governance.web.GovernanceRuleViews.RuleRequest;
import io.github.sudoitir.artemisstudio.platform.governance.web.GovernanceRuleViews.RuleView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The content policy's administration surface (data-governance spec). Permissions are checked in the services. */
@RestController
@RequestMapping("/api/v1/governance")
@RequiredArgsConstructor
public class GovernanceController {

    private final GovernanceRuleService rules;

    @GetMapping("/rules")
    public List<RuleView> rules() {
        return rules.list();
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView create(@Valid @RequestBody RuleRequest request) {
        return rules.create(request);
    }

    @PutMapping("/rules/{ruleId}")
    public RuleView update(@PathVariable UUID ruleId, @Valid @RequestBody RuleRequest request) {
        return rules.update(ruleId, request);
    }

    @DeleteMapping("/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ruleId) {
        rules.delete(ruleId);
    }
}
