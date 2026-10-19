package com.academy.paybridge.transfer;

import com.academy.paybridge.funding.client.CollectionGateway;
import com.academy.paybridge.funding.client.PaystackCollectionClient;
import com.academy.paybridge.funding.web.FundingSimulationController;
import com.academy.paybridge.shared.paystack.PaystackWebhookController;
import com.academy.paybridge.transfer.client.PaystackTransferClient;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.web.TransferSimulationController;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** The default (production) wiring: real Paystack clients, signed webhook, no simulation endpoints. */
@SpringBootTest(properties = {
        "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/paybridge_test}",
        "paybridge.payments.provider=paystack", // pinned: a developer's .env may select the simulated provider
        "paybridge.paystack.secret-key=sk_test_wiring_only",
        "paybridge.settlement.nightly.enabled=false",
        "paybridge.fx.feed.enabled=false",
        "paybridge.compliance.ofac.enabled=false",
        "paybridge.security.jwt.secret=test-only-secret-that-is-at-least-32-bytes-long!!",
        "paybridge.transfer.reconciliation.enabled=false",
        "paybridge.funding.reconciliation.enabled=false"
})
class PaystackWiringTest {

    @Autowired ApplicationContext context;
    @Autowired TransferGateway transferGateway;
    @Autowired CollectionGateway collectionGateway;

    @Test
    void defaultsToPaystackWithResilienceProxyAndSignedWebhook() {
        assertThat(AopUtils.getTargetClass(transferGateway)).isEqualTo(PaystackTransferClient.class);
        assertThat(AopUtils.isAopProxy(transferGateway)).as("@Retry/@CircuitBreaker must be applied").isTrue();
        assertThat(AopUtils.getTargetClass(collectionGateway)).isEqualTo(PaystackCollectionClient.class);
        assertThat(context.getBeansOfType(PaystackWebhookController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TransferSimulationController.class)).isEmpty();
        assertThat(context.getBeansOfType(FundingSimulationController.class)).isEmpty();
    }
}
