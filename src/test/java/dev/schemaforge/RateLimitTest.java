package dev.schemaforge;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class RateLimitTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void bucketIsPerIdentityAndReturnsRetryAfter() throws Exception {
        var filter = new SecurityConfig.RateFilter(1);
        FilterChain chain = (request,response) -> ((jakarta.servlet.http.HttpServletResponse)response).setStatus(204);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("one",null,List.of()));
        var first = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/v1/users"),first,chain);
        assertThat(first.getStatus()).isEqualTo(204);
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/v1/users"),second,chain);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("60");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("two",null,List.of()));
        var third = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/v1/users"),third,chain);
        assertThat(third.getStatus()).isEqualTo(204);
    }
}
