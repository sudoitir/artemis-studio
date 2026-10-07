package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.gate.DisplayRow;
import io.github.sudoitir.artemisstudio.kernel.gate.Effect;
import io.github.sudoitir.artemisstudio.kernel.gate.ExecutionMode;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationScope;
import io.github.sudoitir.artemisstudio.kernel.gate.Trait;
import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** {@code secrets.rotate}: re-wrap every stored secret under the newest key version. */
@Component
class RotateSecretsOperation implements GatedOperation<RotateSecrets> {

    static final String TYPE = "secrets.rotate";

    private final SecretRotationService service;
    private final SecretVault vault;

    RotateSecretsOperation(SecretRotationService service, SecretVault vault) {
        this.service = service;
        this.vault = vault;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public Class<RotateSecrets> paramsType() {
        return RotateSecrets.class;
    }

    @Override
    public Set<Trait> traits(RotateSecrets params) {
        return Set.of(Trait.SETTINGS);
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.ON_APPROVAL;
    }

    @Override
    public OperationScope scope(RotateSecrets params) {
        return OperationScope.GLOBAL;
    }

    @Override
    public String summary(RotateSecrets params) {
        return "Rotate the key that encrypts stored secrets";
    }

    @Override
    public List<DisplayRow> display(RotateSecrets params) {
        return List.of(new DisplayRow(
                "Key-encryption key", "version " + vault.currentKekVersion(), "the newest available version"));
    }

    @Override
    public Set<String> redactedPaths() {
        return Set.of();
    }

    /** A rotation approved against one key version must not run once the key has moved on. */
    @Override
    public Effect estimate(RotateSecrets params) {
        return new Effect(1, "rotations", "kek-" + vault.currentKekVersion(), null);
    }

    @Override
    public void replay(RotateSecrets params) {
        service.rotate();
    }
}
