package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** Tiered broker polling, queue snapshots and metric samples. Module descriptor (ADR-0070). */
public final class ScrapeModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("scrape")
            .title("Scraping")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .build();

    private ScrapeModule() {}
}
