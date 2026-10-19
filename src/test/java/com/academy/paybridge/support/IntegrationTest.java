package com.academy.paybridge.support;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.customer.service.AuthService;
import com.academy.paybridge.customer.service.CustomerService;
import com.academy.paybridge.funding.domain.FundingDirection;
import com.academy.paybridge.funding.service.FundingService;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real PostgreSQL database (row locks, constraints and Flyway migrations are part of what is
 * being tested). Defaults to the local paybridge_test database; override with TEST_DB_URL.
 *
 * <p>After every test it asserts the system-wide double-entry invariant: the balances of all accounts
 * (customer wallets and GL accounts together) sum to exactly zero, because money only ever moves between
 * accounts through balanced postings.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/paybridge_test}",
        "spring.jpa.show-sql=false",
        "paybridge.payments.provider=simulated",
        "paybridge.transfer.reconciliation.enabled=false",
        "paybridge.funding.reconciliation.enabled=false",
        "paybridge.security.jwt.secret=test-only-secret-that-is-at-least-32-bytes-long!!",
        "paybridge.compliance.velocity.max-transfers=1000",
        "paybridge.settlement.nightly.enabled=false",
        "paybridge.fx.feed.enabled=false",
        "paybridge.kyc.provider=simulated",
        "paybridge.compliance.ofac.enabled=false"
})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Sup3r-secret-pass";

    @Autowired protected AuthService authService;
    @Autowired protected CustomerService customerService;
    @Autowired protected FundingService fundingService;
    @Autowired protected AccountApi accountApi;
    @Autowired protected JdbcTemplate jdbc;

    private AuthenticatedUser admin;

    @AfterEach
    void ledgerStillBalances() {
        // Each currency balances on its own: a USD posting can never be "balanced" by an NGN one.
        jdbc.queryForList("select currency, sum(balance) as total from accounts group by currency").forEach(row ->
                assertThat((BigDecimal) row.get("total"))
                        .as("sum of all %s account balances (double-entry invariant)", row.get("currency"))
                        .isEqualByComparingTo("0"));
        jdbc.queryForList("""
                select currency,
                       coalesce(sum(case when entry_type = 'DEBIT' then amount end), 0) as debits,
                       coalesce(sum(case when entry_type = 'CREDIT' then amount end), 0) as credits
                from ledger_entries group by currency""").forEach(row ->
                assertThat((BigDecimal) row.get("debits"))
                        .as("%s ledger debits == credits", row.get("currency"))
                        .isEqualByComparingTo((BigDecimal) row.get("credits")));
    }

    /** A registered tier-1 customer with an empty NGN wallet. */
    protected TestCustomer customer() {
        String email = "c-" + UUID.randomUUID() + "@test.local";
        AuthService.Registration r = authService.register(email, "Test " + email.substring(2, 10), PASSWORD);
        return new TestCustomer(new AuthenticatedUser(r.customer().id(), email, Role.CUSTOMER),
                r.wallet().walletNumber(), email);
    }

    protected TestCustomer customer(String fundedWith) {
        TestCustomer c = customer();
        fund(c.wallet(), fundedWith);
        return c;
    }

    protected TestCustomer customerAtTier(int tier, String fundedWith) {
        TestCustomer c = customer();
        customerService.changeKycTier(c.id(), tier, "test", admin().customerId());
        if (new BigDecimal(fundedWith).signum() > 0) fund(c.wallet(), fundedWith);
        return c;
    }

    /** Funds a wallet the only legitimate way an admin can: a ledgered adjustment. */
    protected void fund(String walletNumber, String amount) {
        fundingService.adjust(admin(), walletNumber, FundingDirection.CREDIT, new BigDecimal(amount),
                "test funding", UUID.randomUUID().toString());
    }

    protected AuthenticatedUser admin() {
        if (admin == null) {
            String email = "admin-" + UUID.randomUUID() + "@test.local";
            AuthService.Registration r = authService.create(email, "Test Admin", PASSWORD, Role.ADMIN);
            admin = new AuthenticatedUser(r.customer().id(), email, Role.ADMIN);
        }
        return admin;
    }

    protected BigDecimal balance(String walletNumber) {
        return accountApi.getByWalletNumber(walletNumber).balance();
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    public record TestCustomer(AuthenticatedUser user, String wallet, String email) {
        public UUID id() { return user.customerId(); }
    }
}
