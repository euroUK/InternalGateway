package bank.internalgateway.offers.test;

import bank.internalgateway.offers.config.OfferServiceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "offer-service.fault", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TestFaultFilter extends OncePerRequestFilter {

    private final TestFaultState faultState;
    private final String pathPrefix;

    public TestFaultFilter(TestFaultState faultState, OfferServiceProperties properties) {
        this.faultState = faultState;
        OfferServiceProperties.Fault fault = properties.fault();
        this.pathPrefix = fault != null && fault.pathPrefix() != null
                ? fault.pathPrefix()
                : "/internal/v1/events/deposit-processor/";
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith(pathPrefix) && faultState.shouldFail()) {
            response.setStatus(faultState.failStatus());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"injected-fault\",\"status\":" + faultState.failStatus() + "}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
