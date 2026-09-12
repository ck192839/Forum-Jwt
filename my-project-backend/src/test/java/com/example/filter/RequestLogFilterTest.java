package com.example.filter;

import com.example.utils.SnowflakeIdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RequestLogFilterTest {

    @Test
    void continuesBufferingOrdinaryApiResponses() throws Exception {
        RequestLogFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/forum/types");
        request.setServletPath("/api/forum/types");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletResponse> passedResponse = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> passedResponse.set(res));

        assertTrue(passedResponse.get() instanceof ContentCachingResponseWrapper);
    }

    private static RequestLogFilter filter() {
        RequestLogFilter filter = new RequestLogFilter();
        ReflectionTestUtils.setField(filter, "generator", mock(SnowflakeIdGenerator.class));
        return filter;
    }
}
