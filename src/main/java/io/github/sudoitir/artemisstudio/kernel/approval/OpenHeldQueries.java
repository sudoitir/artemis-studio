package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperationQueries;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperationView;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link HeldOperationQueries} over the held-operation store. */
@Component
class OpenHeldQueries implements HeldOperationQueries {

    /** More open requests of one type than this are not marked; one requester may hold far fewer. */
    static final int LIMIT = 500;

    private final HeldStore store;

    OpenHeldQueries(HeldStore store) {
        this.store = store;
    }

    @Override
    public List<HeldOperationView> openByType(String type) {
        return store.openByType(type, LIMIT).stream().map(HeldRow::view).toList();
    }
}
