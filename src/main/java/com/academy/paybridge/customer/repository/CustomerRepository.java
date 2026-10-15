package com.academy.paybridge.customer.repository;

import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.shared.security.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByEmail(String lowerCaseEmail);

    boolean existsByEmail(String lowerCaseEmail);

    boolean existsByRole(Role role);

    /** Serialises concurrent login attempts so the failed-attempt counter cannot be raced past the lockout. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.email = :email")
    Optional<Customer> lockByEmail(@Param("email") String lowerCaseEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> lockById(@Param("id") UUID id);
}
