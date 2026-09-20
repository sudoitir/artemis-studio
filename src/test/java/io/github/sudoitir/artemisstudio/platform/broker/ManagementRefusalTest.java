package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManagementRefusalTest {

    /** The text Artemis 2.44 answers {@code destroyQueue} with while a consumer is attached (notes §17 Q1). */
    private static final String HAS_CONSUMERS = "java.lang.IllegalStateException : AMQ229025: Cannot delete queue DST"
            + " on binding DST - it has consumers = org.apache.activemq.artemis.core.postoffice.impl.LocalQueueBinding";

    /** What Jolokia answers for a queue destroyed since the listing was scraped. */
    private static final String MBEAN_GONE = "javax.management.InstanceNotFoundException :"
            + " org.apache.activemq.artemis:broker=\"primary\",component=addresses,address=\"SCRATCH\","
            + "subcomponent=queues,routing-type=\"anycast\",queue=\"SCRATCH\"";

    @Test
    void aQueueThatIsNoLongerOnTheNodeSaysSoRatherThanRaisingAJmxClassName() {
        ManagementRefusal refusal = ManagementRefusal.classify(MBEAN_GONE, "pause");

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(ManagementRefusal.Kind.ALREADY);
        assertThat(refusal.getMessage()).contains("no longer on this node");
    }

    @Test
    void aQueueWithConsumersIsItsOwnRefusalAndNamesTheWayThrough() {
        ManagementRefusal refusal = ManagementRefusal.classify(HAS_CONSUMERS, "destroyQueue");

        assertThat(refusal).isNotNull();
        assertThat(refusal.kind()).isEqualTo(ManagementRefusal.Kind.HAS_CONSUMERS);
        assertThat(refusal.getMessage()).contains("consumers").contains("disconnectConsumers");
    }
}
