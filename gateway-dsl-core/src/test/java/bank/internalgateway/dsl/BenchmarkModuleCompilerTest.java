package bank.internalgateway.dsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkModuleCompilerTest {

    @TempDir
    Path tempDir;

    @Test
    void compilesOffersDslAndRendersCapabilityTemplates() throws Exception {
        Path dsl = writeValidDsl();
        CompiledBenchmarkModule module = BenchmarkModuleCompiler.compileFromDirectory(dsl.getParent());

        assertThat(module.moduleName()).isEqualTo("deposit-offers");
        assertThat(module.ingressRoutes()).hasSize(2);
        assertThat(module.capabilities()).hasSize(2);

        CompiledBenchmarkModule.CompiledIngressRoute route = module.requireIngressByPath("POST", "/deposit-offers/search");
        assertThat(route.targetService()).isEqualTo("deposit-offer-service");
        assertThat(route.targetPath()).isEqualTo("/internal/v1/offers/search");
        assertThat(route.businessControlStub()).isTrue();
        assertThat(route.isEnriched()).isFalse();

        CompiledBenchmarkModule.CompiledIngressRoute enriched =
                module.requireIngressByPath("POST", "/deposit-offers/enriched");
        assertThat(enriched.isEnriched()).isTrue();
        assertThat(enriched.adapter().capabilityId()).isEqualTo("organization-display-info");
        assertThat(enriched.adapter().pathTemplate())
                .isEqualTo("/internal/capabilities/organizations/{organizationId}/display-info");
        assertThat(enriched.targetPath()).isEqualTo("/internal/v1/offers/fixed");
        assertThat(enriched.responseMapping()).containsEntry("organizationDisplayName", "adapter.displayName");
        assertThat(enriched.responseMapping()).containsEntry("offer", "target");

        Map<String, Object> account = StaticCapabilityRenderer.render(
                module.requireCapabilityByPath("/internal/capabilities/accounts/acc-1/deposit-context"),
                "/internal/capabilities/accounts/acc-1/deposit-context");
        assertThat(account.get("accountId")).isEqualTo("acc-1");
        assertThat(account.get("currency")).isEqualTo("RUB");
        assertThat(account.get("snapshotAt")).isInstanceOf(String.class);
    }

    @Test
    void reloadKeepsLastKnownGoodOnInvalidDsl() throws Exception {
        Path dslFile = writeValidDsl();
        BenchmarkRouteRegistry registry = new BenchmarkRouteRegistry(dslFile.getParent());
        registry.loadInitial();
        int version = registry.currentSnapshot().version();

        Files.writeString(dslFile, "metadata:\n  name: broken\nroutes: []\n");
        BenchmarkRouteRegistry.ReloadResult result = registry.reload();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(registry.currentSnapshot().version()).isEqualTo(version);
        assertThat(registry.currentModule().ingressRoutes()).hasSize(2);
    }

    @Test
    void rejectsDuplicateIngressRoutes() {
        Map<String, Object> root = new Yaml().load("""
                metadata:
                  name: deposit-offers
                routes:
                  - id: a
                    request:
                      method: POST
                      path: /deposit-offers/search
                    target:
                      service: deposit-offer-service
                      path: /internal/v1/offers/search
                  - id: b
                    request:
                      method: POST
                      path: /deposit-offers/search
                    target:
                      service: deposit-offer-service
                      path: /internal/v1/offers/search
                capabilities:
                  - id: account-deposit-context
                    request:
                      method: GET
                      path: "/internal/capabilities/accounts/{accountId}/deposit-context"
                    executionMode: static-stub
                    responseTemplate:
                      accountId: "{accountId}"
                """);
        assertThatThrownBy(() -> BenchmarkModuleCompiler.compile(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate ingress route");
    }

    private Path writeValidDsl() throws Exception {
        Path file = tempDir.resolve(BenchmarkModuleCompiler.OFFERS_DSL_FILE);
        Files.writeString(file, """
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
                      path: /internal/v1/offers/search
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
                """);
        return file;
    }
}
