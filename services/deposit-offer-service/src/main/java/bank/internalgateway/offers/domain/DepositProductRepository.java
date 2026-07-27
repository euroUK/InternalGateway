package bank.internalgateway.offers.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositProductRepository extends JpaRepository<DepositProduct, String> {
}
