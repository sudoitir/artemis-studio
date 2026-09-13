package io.github.sudoitir.artemisstudio.kernel.plugin.api;

import java.util.List;

/**
 * Every module built into this installation, enabled or not. Published once, by
 * the composition root; the kernel never lists modules itself.
 */
public record InstalledFeatures(List<FeatureDescriptor> descriptors) {

    public InstalledFeatures {
        descriptors = List.copyOf(descriptors);
    }
}
