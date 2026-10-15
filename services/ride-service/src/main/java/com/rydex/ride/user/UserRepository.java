package com.rydex.ride.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** {@code emailHash}: {@code FieldCipher.emailIndex(email)}. */
    Optional<User> findByEmailHash(String emailHash);

    boolean existsByEmailHash(String emailHash);
}
