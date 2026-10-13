package com.rydex.payment.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    Optional<Refund> findByProviderRef(String providerRef);
}
