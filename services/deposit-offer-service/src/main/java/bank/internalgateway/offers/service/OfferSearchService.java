package bank.internalgateway.offers.service;

import bank.internalgateway.offers.domain.DepositProduct;
import bank.internalgateway.offers.domain.DepositProductRepository;
import bank.internalgateway.offers.domain.ProcessorOfferCatalogEntry;
import bank.internalgateway.offers.domain.ProcessorOfferCatalogRepository;
import bank.internalgateway.offers.dto.ProcessorOfferEventPayload;
import bank.internalgateway.offers.dto.SearchOffersRequest;
import bank.internalgateway.offers.dto.SearchOffersResponse;
import bank.internalgateway.offers.gateway.GatewayClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class OfferSearchService {

    private final DepositProductRepository productRepository;
    private final ProcessorOfferCatalogRepository processorCatalogRepository;
    private final GatewayClient gatewayClient;

    public OfferSearchService(
            DepositProductRepository productRepository,
            ProcessorOfferCatalogRepository processorCatalogRepository,
            GatewayClient gatewayClient) {
        this.productRepository = productRepository;
        this.processorCatalogRepository = processorCatalogRepository;
        this.gatewayClient = gatewayClient;
    }

    public SearchOffersResponse search(SearchOffersRequest request, String correlationId) {
        GatewayClient.AccountDepositContext accountContext =
                gatewayClient.fetchAccountDepositContext(request.accountId(), correlationId);
        String organizationDisplayName =
                gatewayClient.fetchOrganizationDisplayName(request.organizationId(), correlationId);

        if (!accountContext.debitAllowed()) {
            return emptyResponse(request, organizationDisplayName, accountContext.currency());
        }

        List<SearchOffersResponse.OfferResult> offers = new ArrayList<>();
        Instant expiresAt = Instant.now().plusSeconds(3600);

        for (ProcessorOfferCatalogEntry entry : processorCatalogRepository.findByActiveTrue()) {
            if (matches(entry, request, accountContext.currency())) {
                offers.add(new SearchOffersResponse.OfferResult(
                        entry.getProcessorOfferId(),
                        "processor-catalog",
                        entry.getProductCode(),
                        entry.getRate(),
                        entry.getTermMonths(),
                        entry.getMinAmount(),
                        entry.getMaxAmount(),
                        entry.getCurrency(),
                        expiresAt
                ));
            }
        }

        for (DepositProduct product : productRepository.findAll()) {
            if (!product.isActive()) {
                continue;
            }
            if (matches(product, request, accountContext.currency())) {
                offers.add(new SearchOffersResponse.OfferResult(
                        "local-" + product.getId(),
                        "local-catalog",
                        product.getId(),
                        product.getBaseRate(),
                        request.termMonths(),
                        product.getMinAmount(),
                        product.getMaxAmount(),
                        product.getCurrency(),
                        expiresAt
                ));
            }
        }

        offers.sort(Comparator.comparing(SearchOffersResponse.OfferResult::rate).reversed());

        return new SearchOffersResponse(
                request.organizationId(),
                request.accountId(),
                organizationDisplayName,
                accountContext.currency(),
                offers,
                Instant.now()
        );
    }

    @Transactional
    public void handleCreated(ProcessorOfferEventPayload payload) {
        Instant occurredAt = Instant.parse(payload.occurredAt());
        processorCatalogRepository.findById(payload.processorOfferId()).ifPresentOrElse(
                existing -> {
                    if (payload.processorOfferVersion() >= existing.getProcessorOfferVersion()) {
                        existing.applyUpdate(
                                payload.processorOfferVersion(),
                                payload.productCode(),
                                payload.rate(),
                                payload.termMonths(),
                                payload.minAmount(),
                                payload.maxAmount(),
                                payload.currency(),
                                true,
                                occurredAt,
                                Instant.now()
                        );
                    }
                },
                () -> processorCatalogRepository.save(new ProcessorOfferCatalogEntry(
                        payload.processorOfferId(),
                        payload.processorOfferVersion(),
                        payload.productCode(),
                        payload.rate(),
                        payload.termMonths(),
                        payload.minAmount(),
                        payload.maxAmount(),
                        payload.currency(),
                        true,
                        occurredAt,
                        Instant.now()
                ))
        );
    }

    @Transactional
    public void handleUpdated(ProcessorOfferEventPayload payload) {
        ProcessorOfferCatalogEntry entry = processorCatalogRepository.findById(payload.processorOfferId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown processor offer: " + payload.processorOfferId()));
        if (payload.processorOfferVersion() < entry.getProcessorOfferVersion()) {
            return;
        }
        entry.applyUpdate(
                payload.processorOfferVersion(),
                payload.productCode(),
                payload.rate(),
                payload.termMonths(),
                payload.minAmount(),
                payload.maxAmount(),
                payload.currency(),
                true,
                Instant.parse(payload.occurredAt()),
                Instant.now()
        );
    }

    @Transactional
    public void handleClosed(ProcessorOfferEventPayload payload) {
        processorCatalogRepository.findById(payload.processorOfferId()).ifPresent(entry -> {
            entry.applyUpdate(
                    payload.processorOfferVersion(),
                    entry.getProductCode(),
                    entry.getRate(),
                    entry.getTermMonths(),
                    entry.getMinAmount(),
                    entry.getMaxAmount(),
                    entry.getCurrency(),
                    false,
                    Instant.parse(payload.occurredAt()),
                    Instant.now()
            );
        });
    }

    private boolean matches(ProcessorOfferCatalogEntry entry, SearchOffersRequest request, String accountCurrency) {
        return entry.getCurrency().equals(accountCurrency)
                && request.amount().compareTo(entry.getMinAmount()) >= 0
                && request.amount().compareTo(entry.getMaxAmount()) <= 0
                && request.termMonths() == entry.getTermMonths();
    }

    private boolean matches(DepositProduct product, SearchOffersRequest request, String accountCurrency) {
        return product.getCurrency().equals(accountCurrency)
                && request.amount().compareTo(product.getMinAmount()) >= 0
                && request.amount().compareTo(product.getMaxAmount()) <= 0
                && request.termMonths() >= product.getMinTermMonths()
                && request.termMonths() <= product.getMaxTermMonths();
    }

    private SearchOffersResponse emptyResponse(SearchOffersRequest request, String organizationDisplayName, String currency) {
        return new SearchOffersResponse(
                request.organizationId(),
                request.accountId(),
                organizationDisplayName,
                currency,
                List.of(),
                Instant.now()
        );
    }
}
