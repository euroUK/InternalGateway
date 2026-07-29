package bank.internalgateway.scg;

import bank.internalgateway.dsl.BenchmarkModuleCompiler;
import bank.internalgateway.scg.filter.BenchmarkIngressGatewayFilterFactory;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScgGatewayParityTest {

    private static final String ENVELOPE_SECRET = "poc-envelope-secret-min-32-chars!!";
    private static final Path DSL_DIR;
    private static MockWebServer backend;
    private static final AtomicReference<String> searchTargetPath =
            new AtomicReference<>("/internal/v1/offers/search");

    static {
        try {
            DSL_DIR = Files.createTempDirectory("scg-offers-dsl");
            Files.writeString(
                    DSL_DIR.resolve(BenchmarkModuleCompiler.OFFERS_DSL_FILE),
                    validOffersDsl("/internal/v1/offers/search"));
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
                    if (path != null && path.equals(searchTargetPath.get())) {
                        if ("503".equals(request.getHeader("X-Test-Force-Status"))) {
                            return new MockResponse()
                                    .setResponseCode(503)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("{\"error\":\"unavailable\"}");
                        }
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
    WebTestClient webTestClient;

    @BeforeEach
    void drainBackendQueue() throws InterruptedException {
        while (backend.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard leftover requests from previous tests / failed assertions
        }
    }

    @AfterAll
    static void stopBackend() throws IOException {
        backend.shutdown();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String backendUrl = backend.url("/").toString().replaceAll("/$", "");
        registry.add("scg.deposit-offer-service-url", () -> backendUrl);
        registry.add("scg.self-base-url", () -> backendUrl);
        registry.add("scg.envelope-secret", () -> ENVELOPE_SECRET);
        registry.add("scg.dsl-path", () -> DSL_DIR.toAbsolutePath().toString());
    }

    @Test
    @Order(1)
    void proxiesSearchWithJwtEnvelopeCorrelationAndPathRewrite() throws Exception {
        webTestClient.post()
                .uri("/deposit-offers/search")
                .contentType(MediaType.APPLICATION_JSON)
                .header(BenchmarkIngressGatewayFilterFactory.DEMO_SUBJECT_ID, "demo-user-001")
                .header(BenchmarkIngressGatewayFilterFactory.DEMO_ORGANIZATION_ID, "org-demo-001")
                .bodyValue("""
                        {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID)
                .expectBody()
                .jsonPath("$.accountId").isEqualTo("acc-demo-001")
                .jsonPath("$.offers").isArray();

        RecordedRequest recorded = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/internal/v1/offers/search");
        assertThat(recorded.getHeader(BenchmarkIngressGatewayFilterFactory.IDENTITY_ENVELOPE)).isNotBlank();
        assertThat(recorded.getHeader(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID)).isNotBlank();
        assertThat(recorded.getBody().readUtf8()).contains("\"accountId\":\"acc-demo-001\"");

        Claims claims = parseJwt(recorded.getHeader(BenchmarkIngressGatewayFilterFactory.IDENTITY_ENVELOPE));
        assertThat(claims.getIssuer()).isEqualTo("internal-gateway");
        assertThat(claims.getSubject()).isEqualTo("demo-user-001");
        assertThat(claims.get("organizationId", String.class)).isEqualTo("org-demo-001");
        assertThat(claims.get("correlationId", String.class))
                .isEqualTo(recorded.getHeader(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID));
        assertThat(claims.get("operationId", String.class)).isNotBlank();
        assertThat(claims.get("businessControlEvidenceId", String.class)).isEqualTo("poc-stub-passed");
    }

    @Test
    @Order(2)
    void fallsBackOrganizationIdFromBodyWhenHeaderMissing() throws Exception {
        webTestClient.post()
                .uri("/deposit-offers/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"organizationId":"org-from-body","accountId":"acc-demo-001","amount":1000,"termMonths":6}
                        """)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        Claims claims = parseJwt(recorded.getHeader(BenchmarkIngressGatewayFilterFactory.IDENTITY_ENVELOPE));
        assertThat(claims.get("organizationId", String.class)).isEqualTo("org-from-body");
        assertThat(claims.getSubject()).isEqualTo("demo-user-001");
    }

    @Test
    @Order(3)
    void propagatesBackendErrorStatus() throws Exception {
        webTestClient.post()
                .uri("/deposit-offers/search")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Test-Force-Status", "503")
                .bodyValue("""
                        {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                        """)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().exists(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID);
    }

    @Test
    @Order(4)
    void enrichedLoopsOrgCapabilityAndMapsFixedOffer() throws Exception {
        webTestClient.post()
                .uri("/deposit-offers/enriched")
                .contentType(MediaType.APPLICATION_JSON)
                .header(BenchmarkIngressGatewayFilterFactory.DEMO_SUBJECT_ID, "demo-user-001")
                .bodyValue("""
                        {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID)
                .expectBody()
                .jsonPath("$.organizationDisplayName").isEqualTo("Demo Organization LLC")
                .jsonPath("$.accountId").isEqualTo("acc-demo-001")
                .jsonPath("$.offer.productCode").isEqualTo("FIXED-DEP-12")
                .jsonPath("$.offer.rate").isEqualTo(0.125)
                .jsonPath("$.correlationId").isNotEmpty();

        RecordedRequest orgCall = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(orgCall.getMethod()).isEqualTo("GET");
        assertThat(orgCall.getPath())
                .isEqualTo("/internal/capabilities/organizations/org-demo-001/display-info");

        RecordedRequest fixedCall = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(fixedCall.getMethod()).isEqualTo("POST");
        assertThat(fixedCall.getPath()).isEqualTo("/internal/v1/offers/fixed");
        assertThat(fixedCall.getHeader(BenchmarkIngressGatewayFilterFactory.IDENTITY_ENVELOPE)).isNotBlank();
        assertThat(fixedCall.getBody().readUtf8()).contains("organizationDisplayName");
    }

    @Test
    @Order(5)
    void capabilityEndpointsMatchCustomGatewayContract() {
        webTestClient.get()
                .uri("/internal/capabilities/accounts/acc-demo-001/deposit-context")
                .header(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID, "corr-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accountId").isEqualTo("acc-demo-001")
                .jsonPath("$.currency").isEqualTo("RUB")
                .jsonPath("$.debitAllowed").isEqualTo(true)
                .jsonPath("$.availableBalance").isEqualTo(1500000.00);

        webTestClient.get()
                .uri("/internal/capabilities/organizations/org-demo-001/display-info")
                .header(BenchmarkIngressGatewayFilterFactory.CORRELATION_ID, "corr-2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.organizationId").isEqualTo("org-demo-001")
                .jsonPath("$.displayName").isEqualTo("Demo Organization LLC")
                .jsonPath("$.legalForm").isEqualTo("LLC");
    }

    @Test
    @Order(6)
    void adminStatsAndDslConfigEndpointsAreAvailable() {
        webTestClient.get()
                .uri("/internal/admin/stats")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalRecorded").exists()
                .jsonPath("$.bufferedEntries").exists();

        webTestClient.get()
                .uri("/internal/admin/dsl/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.moduleName").isEqualTo("deposit-offers")
                .jsonPath("$.routeCount").isEqualTo(2)
                .jsonPath("$.capabilityCount").isEqualTo(2);
    }

    @Test
    @Order(7)
    void reloadAppliesNewTargetPathAndRejectsInvalidDsl() throws Exception {
        Files.writeString(
                DSL_DIR.resolve(BenchmarkModuleCompiler.OFFERS_DSL_FILE),
                validOffersDsl("/internal/v1/offers/search-v2"));
        searchTargetPath.set("/internal/v1/offers/search-v2");

        webTestClient.post()
                .uri("/internal/admin/dsl/reload")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.config.ingressRoutes[0].targetPath").isEqualTo("/internal/v1/offers/search-v2");

        Thread.sleep(500);

        webTestClient.post()
                .uri("/deposit-offers/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                        """)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/internal/v1/offers/search-v2");

        Files.writeString(DSL_DIR.resolve(BenchmarkModuleCompiler.OFFERS_DSL_FILE), """
                metadata:
                  name: broken
                routes: []
                """);

        webTestClient.post()
                .uri("/internal/admin/dsl/reload")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.config.ingressRoutes[0].targetPath").isEqualTo("/internal/v1/offers/search-v2");

        webTestClient.post()
                .uri("/deposit-offers/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"organizationId":"org-demo-001","accountId":"acc-demo-001","amount":500000,"termMonths":12}
                        """)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest stillActive = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(stillActive).isNotNull();
        assertThat(stillActive.getPath()).isEqualTo("/internal/v1/offers/search-v2");
    }

    private static Claims parseJwt(String token) {
        SecretKey key = Keys.hmacShaKeyFor(ENVELOPE_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static String validOffersDsl(String targetPath) {
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
                    request:
                      method: POST
                      path: /deposit-offers/search
                    identityContext: bankUser
                    validation:
                      businessControl:
                        effect: stub
                        evidenceId: poc-stub-passed
                    target:
                      service: deposit-offer-service
                      method: POST
                      path: %s
                  - id: enriched-deposit-offers
                    request:
                      method: POST
                      path: /deposit-offers/enriched
                    identityContext: bankUser
                    validation:
                      businessControl:
                        effect: stub
                        evidenceId: poc-stub-passed
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
                """.formatted(targetPath);
    }
}
