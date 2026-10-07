package io.github.sudoitir.artemisstudio.kernel.gate;

import java.util.List;

/**
 * What a module that cannot depend on the approval engine may ask about open held operations, such as a screen that
 * marks the values a waiting request would change. The engine implements it.
 */
public interface HeldOperationQueries {

    /** The open requests (held, approved or running) of one operation type, newest first. */
    List<HeldOperationView> openByType(String type);
}
