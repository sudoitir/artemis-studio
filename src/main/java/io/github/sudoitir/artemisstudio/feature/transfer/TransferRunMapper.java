package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.Finding;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferEnd;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferSelection;
import io.github.sudoitir.artemisstudio.kernel.core.CentralMapperConfig;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Transfer run rows → their view (ADR-0014). The JSON columns are parsed, and the derived figures computed, by the caller. */
@Mapper(config = CentralMapperConfig.class)
public interface TransferRunMapper {

    /** What the view states that no column holds as such. */
    record Derived(
            List<String> notes,
            long held,
            Double messagesPerSecond,
            String stagingQueue,
            long cap,
            boolean overCap,
            boolean resumable,
            boolean returnable) {}

    @Mapping(target = "source", source = "from")
    @Mapping(target = "target", source = "to")
    @Mapping(target = "selection", source = "chosen")
    @Mapping(target = "findings", source = "found")
    @Mapping(target = "notes", source = "derived.notes")
    @Mapping(target = "held", source = "derived.held")
    @Mapping(target = "messagesPerSecond", source = "derived.messagesPerSecond")
    @Mapping(target = "stagingQueue", source = "derived.stagingQueue")
    @Mapping(target = "cap", source = "derived.cap")
    @Mapping(target = "overCap", source = "derived.overCap")
    @Mapping(target = "resumable", source = "derived.resumable")
    @Mapping(target = "returnable", source = "derived.returnable")
    TransferRunView toView(
            TransferRunEntity run,
            TransferEnd from,
            TransferEnd to,
            TransferSelection chosen,
            List<Finding> found,
            Derived derived);
}
