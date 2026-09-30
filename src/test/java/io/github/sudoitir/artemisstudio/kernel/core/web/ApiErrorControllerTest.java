package io.github.sudoitir.artemisstudio.kernel.core.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** What a failure that escaped MVC becomes: a problem whose type follows the status, never the cause (api-contract spec). */
class ApiErrorControllerTest {

    private static final String TYPE = "https://artemis-studio.dev/problems/";

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ApiErrorController()).build();

    private void expect(MockHttpServletRequestBuilder request, int status, String slug) throws Exception {
        mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(TYPE + slug))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    void anUnknownAddressIsNotFound() throws Exception {
        expect(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404), 404, "not-found");
    }

    @Test
    void anyOtherClientErrorIsBadRequest() throws Exception {
        expect(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400), 400, "bad-request");
        expect(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 413), 413, "bad-request");
    }

    @Test
    void aServerErrorIsInternalError() throws Exception {
        expect(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500), 500, "internal-error");
        expect(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503), 503, "internal-error");
    }

    @Test
    void aMissingOrUnknownCodeIsInternalError() throws Exception {
        expect(get("/error"), 500, "internal-error");
        expect(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 999), 500, "internal-error");
    }

    @Test
    void itAnswersWhateverMethodFailed() throws Exception {
        expect(post("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404), 404, "not-found");
    }
}
