package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerDto;
import com.academy.paybridge.customer.api.CustomerStatus;
import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.customer.repository.RefreshTokenRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class CustomerService implements CustomerApi {

    private static final Logger log = LogManager.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public CustomerService(CustomerRepository customerRepository, RefreshTokenRepository refreshTokenRepository) {
        this.customerRepository = customerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDto getById(UUID customerId) {
        return toDto(find(customerId));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDto requireActive(UUID customerId) {
        Customer c = find(customerId);
        if (c.getStatus() != CustomerStatus.ACTIVE) {
            throw new PayBridgeException("Your profile is suspended. Please contact support.", HttpStatus.FORBIDDEN);
        }
        return toDto(c);
    }

    @Transactional(readOnly = true)
    public Page<CustomerDto> list(int page, int size) {
        return customerRepository.findAll(PageRequest.of(page, Math.min(size, 200), Sort.by("createdAt").descending()))
                .map(CustomerService::toDto);
    }

    /**
     * Placeholder for the KYC workflow: today an admin sets the tier after verifying BVN/NIN/address
     * out of band. The KYC module will call this once it verifies documents itself.
     */
    @Transactional
    public CustomerDto changeKycTier(UUID customerId, int tier, String reason, UUID changedBy) {
        requireReason(reason);
        Customer c = customerRepository.lockById(customerId)
                .orElseThrow(() -> PayBridgeException.notFound("Customer not found: " + customerId));
        int old = c.getKycTier();
        try {
            c.changeKycTier(tier);
        } catch (IllegalArgumentException ex) {
            throw PayBridgeException.badRequest(ex.getMessage());
        }
        log.info("AUDIT customer {} KYC tier {} -> {} by {}: {}", customerId, old, tier, changedBy, reason);
        return toDto(c);
    }

    @Transactional
    public CustomerDto changeStatus(UUID customerId, CustomerStatus status, String reason, UUID changedBy) {
        requireReason(reason);
        if (customerId.equals(changedBy) && status == CustomerStatus.SUSPENDED) {
            throw PayBridgeException.badRequest("You cannot suspend yourself");
        }
        Customer c = customerRepository.lockById(customerId)
                .orElseThrow(() -> PayBridgeException.notFound("Customer not found: " + customerId));
        CustomerStatus old = c.getStatus();
        c.changeStatus(status);
        if (status == CustomerStatus.SUSPENDED) {
            refreshTokenRepository.revokeAllForCustomer(customerId, Instant.now());
        }
        log.info("AUDIT customer {} status {} -> {} by {}: {}", customerId, old, status, changedBy, reason);
        return toDto(c);
    }

    private Customer find(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> PayBridgeException.notFound("Customer not found: " + customerId));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw PayBridgeException.badRequest("A reason is required");
        }
    }

    static CustomerDto toDto(Customer c) {
        return new CustomerDto(c.getId(), c.getEmail(), c.getFullName(), c.getRole(), c.getStatus(),
                c.getKycTier(), c.getCreatedAt());
    }
}
