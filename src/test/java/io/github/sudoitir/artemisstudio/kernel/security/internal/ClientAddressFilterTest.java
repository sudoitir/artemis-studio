package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientAddressFilterTest {

    @ParameterizedTest
    @CsvSource({
        "0:0:0:0:0:0:0:1, '::1'",
        "0:0:0:0:0:0:0:0, '::'",
        "2001:DB8:0:0:1:0:0:1, '2001:db8::1:0:0:1'",
        "2001:0db8:0:0:0:0:0:5, '2001:db8::5'",
        "fe80:0:0:0:0:0:0:0, 'fe80::'",
        "1:0:2:0:3:0:4:0, '1:0:2:0:3:0:4:0'",
        "::ffff:198.51.100.7, '198.51.100.7'",
        "198.51.100.7, '198.51.100.7'",
        "not:valid:zzz, 'not:valid:zzz'",
        "abc:def, 'abc:def'",
    })
    void writesAnAddressInItsShortestForm(String given, String expected) {
        assertThat(ClientAddressFilter.normalise(given)).isEqualTo(expected);
    }

    @Test
    void everyReaderOfTheRequestSeesTheNormalisedAddress() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        MockFilterChain chain = new MockFilterChain();

        new ClientAddressFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(((HttpServletRequest) chain.getRequest()).getRemoteAddr()).isEqualTo("::1");
    }
}
