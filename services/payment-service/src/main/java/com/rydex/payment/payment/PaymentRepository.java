package com.rydex.payment.payment;

import com.rydex.events.PaymentPayload.Provider;
import com.rydex.events.PaymentPayload.Status;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByRideId(UUID rideId);

    boolean existsByRideId(UUID rideId);

    Optional<Payment> findByProviderAndProviderRef(Provider provider, String providerRef);

    Page<Payment> findByRiderIdOrderByCreatedAtDesc(UUID riderId, Pageable pageable);

    Page<Payment> findByDriverIdOrderByCreatedAtDesc(UUID driverId, Pageable pageable);

    Page<Payment> findByStatusOrderByCreatedAtDesc(Status status, Pageable pageable);

    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
