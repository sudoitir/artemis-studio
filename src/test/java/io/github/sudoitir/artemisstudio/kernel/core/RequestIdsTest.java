package io.github.sudoitir.artemisstudio.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestIdsTest {

    @Test
    void keepsAPlainCallerId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "req-42.a_b");
        assertThat(RequestIds.of(request)).isEqualTo("req-42.a_b");
    }

    @Test
    void replacesAnIdThatCouldForgeALogLine() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIds.HEADER, "abc\r\nINFO forged");
        assertThat(RequestIds.of(request)).doesNotContain("forged").hasSize(36);
    }

    @Test
    void mintsOneWhenThereIsNone() {
        assertThat(RequestIds.of(new MockHttpServletRequest())).hasSize(36);
        assertThat(RequestIds.of(null)).hasSize(36);
    }
}
