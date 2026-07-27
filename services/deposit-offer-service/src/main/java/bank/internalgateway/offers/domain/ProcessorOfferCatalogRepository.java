package bank.internalgateway.offers.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessorOfferCatalogRepository extends JpaRepository<ProcessorOfferCatalogEntry, String> {

    List<ProcessorOfferCatalogEntry> findByActiveTrue();
}
