package bank.internalgateway.offers.test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TestFaultFilter extends OncePerRequestFilter {

    private final TestFaultState faultState;

    public TestFaultFilter(TestFaultState faultState) {
        this.faultState = faultState;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal/v1/events/deposit-processor/")
                && faultState.shouldFail()) {
            response.setStatus(faultState.failStatus());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"injected-fault\",\"status\":" + faultState.failStatus() + "}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
