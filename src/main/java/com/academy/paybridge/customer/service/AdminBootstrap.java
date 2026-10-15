package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.shared.security.Role;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first operations admin from PAYBRIDGE_BOOTSTRAP_ADMIN_EMAIL / _PASSWORD if no admin exists yet.
 * There are no hard-coded credentials anywhere in the codebase.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(AdminBootstrap.class);

    private final AuthService authService;
    private final CustomerRepository customerRepository;
    private final String email;
    private final String password;
    private final String fullName;

    AdminBootstrap(AuthService authService,
                   CustomerRepository customerRepository,
                   @Value("${paybridge.bootstrap.admin.email:}") String email,
                   @Value("${paybridge.bootstrap.admin.password:}") String password,
                   @Value("${paybridge.bootstrap.admin.full-name:PayBridge Operations}") String fullName) {
        this.authService = authService;
        this.customerRepository = customerRepository;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (customerRepository.existsByRole(Role.ADMIN)) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            log.warn("No admin user exists. Set PAYBRIDGE_BOOTSTRAP_ADMIN_EMAIL and PAYBRIDGE_BOOTSTRAP_ADMIN_PASSWORD "
                    + "to create one on startup.");
            return;
        }
        authService.create(email, fullName, password, Role.ADMIN);
        log.info("Bootstrap admin {} created. Remove PAYBRIDGE_BOOTSTRAP_ADMIN_PASSWORD from the environment.", email);
    }
}
