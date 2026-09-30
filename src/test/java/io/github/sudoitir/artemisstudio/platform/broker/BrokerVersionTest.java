package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerVersion.Support;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BrokerVersionTest {

    @Test
    void parsesTheBrokerVersionAttribute() {
        assertThat(BrokerVersion.parse("2.44.0")).contains(new BrokerVersion(2, 44, 0));
        assertThat(BrokerVersion.parse("2.45.0-SNAPSHOT")).contains(new BrokerVersion(2, 45, 0));
        assertThat(BrokerVersion.parse(" 2.32 ")).contains(new BrokerVersion(2, 32, 0));
    }

    @Test
    void anUnreadableVersionIsUnknownNotAnError() {
        assertThat(BrokerVersion.parse(null)).isEmpty();
        assertThat(BrokerVersion.parse("")).isEmpty();
        assertThat(BrokerVersion.parse("unknown")).isEmpty();
    }

    @Test
    void comparesNumericallyNotAsText() {
        assertThat(new BrokerVersion(2, 9, 0)).isLessThan(new BrokerVersion(2, 10, 0));
        assertThat(new BrokerVersion(2, 38, 0)).isGreaterThan(new BrokerVersion(2, 37, 9));
        assertThat(new BrokerVersion(2, 38, 0).atLeast(new BrokerVersion(2, 38, 0)))
                .isTrue();
    }

    @Test
    void classifiesAgainstTheSupportedRange() {
        assertThat(BrokerVersion.support("2.31.2")).isEqualTo(Support.BELOW_MINIMUM);
        assertThat(BrokerVersion.support(BrokerVersion.MINIMUM.toString())).isEqualTo(Support.SUPPORTED);
        assertThat(BrokerVersion.support(BrokerVersion.LATEST_TESTED.toString()))
                .isEqualTo(Support.SUPPORTED);
        assertThat(BrokerVersion.support("99.0.0")).isEqualTo(Support.NEWER_THAN_TESTED);
        assertThat(BrokerVersion.support(null)).isEqualTo(Support.UNKNOWN);
    }

    /** When the range moves, the pipeline moves with it: CI tests exactly the two ends. */
    @Test
    void ciTestsBothEndsOfTheSupportedRange() throws Exception {
        String ci = Files.readString(Path.of(".github/workflows/ci.yml"));
        var m = Pattern.compile("artemis: \\[(.*)]").matcher(ci);
        assertThat(m.find()).as("the backend job's artemis matrix").isTrue();
        assertThat(m.group(1).split(","))
                .extracting(image -> image.replaceAll("[\" ]", "").replaceAll(".*:", ""))
                .containsExactly(BrokerVersion.MINIMUM.toString(), BrokerVersion.LATEST_TESTED.toString());
    }
}
