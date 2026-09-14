package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.platform.governance.internal.persistence.GovernanceRuleEntity;
import io.github.sudoitir.artemisstudio.platform.governance.internal.persistence.GovernanceRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Holds the current {@link PolicySnapshot}. A rule write drops it after commit on this instance;
 * the refresh job reloads it when another instance moved the version.
 */
@Component
@RequiredArgsConstructor
class PolicyStore {

    /** Published by a rule write, inside its transaction. */
    record PolicyChanged() {}

    private final GovernanceRuleRepository rules;
    private volatile PolicySnapshot current;

    PolicySnapshot current() {
        PolicySnapshot snapshot = current;
        return snapshot != null ? snapshot : reload();
    }

    synchronized PolicySnapshot reload() {
        current = PolicySnapshot.of(
                rules.policyVersion(),
                rules.findAll().stream().map(PolicyStore::toRule).toList());
        return current;
    }

    /** Cheap version probe; the rules are only re-read when the version moved. */
    void refreshIfStale() {
        PolicySnapshot snapshot = current;
        if (snapshot == null || snapshot.version() != rules.policyVersion()) {
            reload();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChange(PolicyChanged ignored) {
        current = null;
    }

    static Rule toRule(GovernanceRuleEntity e) {
        return new Rule(
                e.getId(),
                e.getAddressPattern(),
                RuleTarget.valueOf(e.getTarget()),
                e.getSelector(),
                DataClass.valueOf(e.getDataClass()),
                e.getAction() == null ? null : Action.valueOf(e.getAction()),
                e.isBuiltin(),
                e.isEnabled(),
                e.isException());
    }
}
