package bank.internalgateway.gateway.web;

import bank.internalgateway.dsl.BenchmarkModuleCompiler;
import bank.internalgateway.dsl.BenchmarkRouteRegistry;
import bank.internalgateway.gateway.config.GatewayProperties;
import bank.internalgateway.gateway.config.ServiceUrlResolver;
import bank.internalgateway.gateway.identity.IdentityEnvelopeService;
import bank.internalgateway.gateway.observability.RequestTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {IngressController.class, CapabilityController.class})
@Import(CustomGatewayHttpParityTest.TestBeans.class)
class CustomGatewayHttpParityTest {

    private static final String ENVELOPE_SECRET = "poc-envelope-secret-min-32-chars!!";
    private static final Path DSL_DIR;
    private static MockWebServer backend;

    static {
        try {
            DSL_DIR = Files.createTempDirectory("custom-offers-dsl");
            Files.writeString(
                    DSL_DIR.resolve(BenchmarkModuleCompiler.OFFERS_DSL_FILE),
                    validOffersDsl());
            backend = new MockWebServer();
            backend.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    if (path != null && path.startsWith("/internal/capabilities/organizations/")) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""
                                        {"organizationId":"org-demo-001","displayName":"Demo Organization LLC","legalForm":"LLC"}
                                        """);
                    }
                    if ("/internal/v1/offers/fixed".equals(path)) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""
                                        {"productCode":"FIXED-DEP-12","rate":0.125,"termMonths":12,"currency":"RUB","minAmount":100000,"status":"AVAILABLE"}
                                        """);
                    }
                    if ("/internal/v1/offers/search".equals(path) || "/internal/v1/offers/search-v2".equals(path)) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""
                                        {"organizationDisplayName":"Demo Organization LLC","accountId":"acc-demo-001","offers":[]}
                                        """);
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            backend.start();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    BenchmarkRouteRegistry benchmarkRouteRegistry;

    @BeforeEach
    void drain() throws InterruptedException {
        while (backend.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard leftovers
        }
    }

    @AfterAll
    static void stopBackend() throws IOException {
        backend.shutdown();
    }

    @Test
    void proxiesSearchWithJwtEnvelopeAndCompiledTargetPath() throws Exception {
        mockMvc.perform(post("/deposit-offers/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Demo-Subject-Id", "demo-user-001")
                        .header("X-Demo-Organization-Id", "org-demo-001")
                        .content("""
                                {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.accountId").value("acc-demo-001"));

        RecordedRequest recorded = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/internal/v1/offers/search");
        assertThat(recorded.getHeader("X-Identity-Envelope")).isNotBlank();
        assertThat(recorded.getBody().readUtf8()).contains("\"accountId\":\"acc-demo-001\"");

        Claims claims = parseJwt(recorded.getHeader("X-Identity-Envelope"));
        assertThat(claims.getIssuer()).isEqualTo("internal-gateway");
        assertThat(claims.getSubject()).isEqualTo("demo-user-001");
        assertThat(claims.get("businessControlEvidenceId", String.class)).isEqualTo("poc-stub-passed");
    }

    @Test
    void enrichedLoopsOrgCapabilityAndMapsFixedOffer() throws Exception {
        mockMvc.perform(post("/deposit-offers/enriched")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Demo-Subject-Id", "demo-user-001")
                        .content("""
                                {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.organizationId").value("org-demo-001"))
                .andExpect(jsonPath("$.organizationDisplayName").value("Demo Organization LLC"))
                .andExpect(jsonPath("$.accountId").value("acc-demo-001"))
                .andExpect(jsonPath("$.amount").value(500000))
                .andExpect(jsonPath("$.termMonths").value(12))
                .andExpect(jsonPath("$.offer.productCode").value("FIXED-DEP-12"))
                .andExpect(jsonPath("$.offer.rate").value(0.125))
                .andExpect(jsonPath("$.correlationId").isString());

        RecordedRequest orgCall = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(orgCall).isNotNull();
        assertThat(orgCall.getMethod()).isEqualTo("GET");
        assertThat(orgCall.getPath())
                .isEqualTo("/internal/capabilities/organizations/org-demo-001/display-info");

        RecordedRequest fixedCall = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(fixedCall).isNotNull();
        assertThat(fixedCall.getMethod()).isEqualTo("POST");
        assertThat(fixedCall.getPath()).isEqualTo("/internal/v1/offers/fixed");
        assertThat(fixedCall.getHeader("X-Identity-Envelope")).isNotBlank();
        assertThat(fixedCall.getBody().readUtf8()).contains("\"organizationDisplayName\":\"Demo Organization LLC\"");
    }

    @Test
    void capabilityEndpointsRenderFromDslTemplates() throws Exception {
        mockMvc.perform(get("/internal/capabilities/accounts/acc-demo-001/deposit-context")
                        .header("X-Correlation-Id", "corr-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc-demo-001"))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.debitAllowed").value(true))
                .andExpect(jsonPath("$.availableBalance").value(1500000.00));

        mockMvc.perform(get("/internal/capabilities/organizations/org-demo-001/display-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value("org-demo-001"))
                .andExpect(jsonPath("$.displayName").value("Demo Organization LLC"))
                .andExpect(jsonPath("$.legalForm").value("LLC"));
    }

    @Test
    void reloadChangesTargetPathAndKeepsLastKnownGoodOnInvalidDsl() throws Exception {
        Path dslFile = DSL_DIR.resolve(BenchmarkModuleCompiler.OFFERS_DSL_FILE);
        String original = Files.readString(dslFile);
        String updated = original.replace("/internal/v1/offers/search", "/internal/v1/offers/search-v2");
        Files.writeString(dslFile, updated);

        BenchmarkRouteRegistry.ReloadResult ok = benchmarkRouteRegistry.reload();
        assertThat(ok.success()).isTrue();
        assertThat(benchmarkRouteRegistry.currentModule()
                .requireIngressByPath("POST", "/deposit-offers/search").targetPath())
                .isEqualTo("/internal/v1/offers/search-v2");

        mockMvc.perform(post("/deposit-offers/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":1,"termMonths":1}
                                """))
                .andExpect(status().isOk());

        RecordedRequest recorded = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded.getPath()).isEqualTo("/internal/v1/offers/search-v2");

        Files.writeString(dslFile, "metadata:\n  name: broken\nroutes: []\n");
        BenchmarkRouteRegistry.ReloadResult rejected = benchmarkRouteRegistry.reload();
        assertThat(rejected.success()).isFalse();
        assertThat(benchmarkRouteRegistry.currentModule()
                .requireIngressByPath("POST", "/deposit-offers/search").targetPath())
                .isEqualTo("/internal/v1/offers/search-v2");

        Files.writeString(dslFile, original);
        assertThat(benchmarkRouteRegistry.reload().success()).isTrue();
    }

    private static Claims parseJwt(String token) {
        SecretKey key = Keys.hmacShaKeyFor(ENVELOPE_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    private static String validOffersDsl() {
        return """
                metadata:
                  name: deposit-offers
                  version: 1.0.0
                identity:
                  forwardedEnvelope:
                    issuer: internal-gateway
                    ttl: 30s
                    claims: [subjectId, organizationId, correlationId, operationId, businessControlEvidenceId]
                routes:
                  - id: search-deposit-offers
                    request: { method: POST, path: /deposit-offers/search }
                    identityContext: bankUser
                    validation:
                      businessControl: { effect: stub, evidenceId: poc-stub-passed }
                    target:
                      service: deposit-offer-service
                      method: POST
                      path: /internal/v1/offers/search
                  - id: enriched-deposit-offers
                    request: { method: POST, path: /deposit-offers/enriched }
                    identityContext: bankUser
                    validation:
                      businessControl: { effect: stub, evidenceId: poc-stub-passed }
                    adapter:
                      capability: organization-display-info
                    target:
                      service: deposit-offer-service
                      method: POST
                      path: /internal/v1/offers/fixed
                    responseMapping:
                      organizationId: request.organizationId
                      organizationDisplayName: adapter.displayName
                      accountId: request.accountId
                      amount: request.amount
                      termMonths: request.termMonths
                      offer: target
                      correlationId: envelope.correlationId
                capabilities:
                  - id: account-deposit-context
                    request:
                      method: GET
                      path: /internal/capabilities/accounts/{accountId}/deposit-context
                    executionMode: static-stub
                    responseTemplate:
                      accountId: "{accountId}"
                      currency: RUB
                      availableBalance: 1500000.00
                      debitAllowed: true
                      organizationId: org-demo-001
                      snapshotAt: "{now}"
                    targetService: account-context-provider
                    targetUrl: stub://account-lite-or-core
                  - id: organization-display-info
                    request:
                      method: GET
                      path: /internal/capabilities/organizations/{organizationId}/display-info
                    executionMode: static-stub
                    responseTemplate:
                      organizationId: "{organizationId}"
                      displayName: Demo Organization LLC
                      legalForm: LLC
                    targetService: organization-directory-provider
                    targetUrl: stub://organization-lite-or-core
                """;
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        GatewayProperties gatewayProperties() {
            String backendUrl = backend.url("/").toString().replaceAll("/$", "");
            return new GatewayProperties(
                    DSL_DIR.toAbsolutePath().toString(),
                    ENVELOPE_SECRET,
                    "http://localhost:3000",
                    Map.of("deposit-offer-service", backendUrl),
                    backendUrl,
                    new GatewayProperties.Envelope("internal-gateway", 30, "poc-stub-passed"),
                    new GatewayProperties.Dedup(100, Duration.ofDays(1)),
                    new GatewayProperties.Resilience(new GatewayProperties.Resilience.Backoff(100, 50)),
                    new GatewayProperties.Observability(100, 10),
                    new GatewayProperties.Kafka(List.of("test")),
                    new GatewayProperties.TestHarness("test", "/fault", 503, "/demo")
            );
        }

        @Bean
        BenchmarkRouteRegistry benchmarkRouteRegistry(GatewayProperties properties) throws IOException {
            BenchmarkRouteRegistry registry = new BenchmarkRouteRegistry(Path.of(properties.dslPath()));
            registry.loadInitial();
            return registry;
        }

        @Bean
        ServiceUrlResolver serviceUrlResolver(GatewayProperties properties) {
            return new ServiceUrlResolver(properties);
        }

        @Bean
        RequestTraceService requestTraceService(GatewayProperties properties) {
            return new RequestTraceService(properties);
        }

        @Bean
        IdentityEnvelopeService identityEnvelopeService(
                GatewayProperties properties,
                BenchmarkRouteRegistry registry) {
            return new IdentityEnvelopeService(properties, registry);
        }

        @Bean
        RestClient restClient() {
            return RestClient.builder().build();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
