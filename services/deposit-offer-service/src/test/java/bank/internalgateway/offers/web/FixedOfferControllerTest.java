package bank.internalgateway.offers.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FixedOfferControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FixedOfferController()).build();
    }

    @Test
    void fixedRequiresIdentityEnvelope() throws Exception {
        mockMvc.perform(post("/internal/v1/offers/fixed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fixedReturnsConstantOfferWithEnvelope() throws Exception {
        mockMvc.perform(post("/internal/v1/offers/fixed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Identity-Envelope", "demo-envelope")
                        .header("X-Correlation-Id", "corr-1")
                        .content("""
                                {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12,"organizationDisplayName":"Demo Organization LLC"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("FIXED-DEP-12"))
                .andExpect(jsonPath("$.rate").value(0.125))
                .andExpect(jsonPath("$.termMonths").value(12))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.minAmount").value(100000))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }
}
