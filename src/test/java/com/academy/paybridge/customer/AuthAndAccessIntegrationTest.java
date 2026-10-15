package com.academy.paybridge.customer;

import com.academy.paybridge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end over HTTP: real tokens through the real security filter chain. */
class AuthAndAccessIntegrationTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void registerLoginAndUseTheApi() throws Exception {
        String email = "http-" + UUID.randomUUID() + "@test.local";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","fullName":"Ada Obi","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.wallet.balance").value(0.0))
                .andExpect(jsonPath("$.customer.kycTier").value(1));

        String token = login(email, PASSWORD).path("accessToken").asString();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void protectedEndpointsRejectMissingOrForgedTokens() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/transfers/internal").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customersCannotUseAdminEndpoints() throws Exception {
        TestCustomer c = customer();
        String token = login(c.email(), PASSWORD).path("accessToken").asString();
        mvc.perform(get("/api/v1/admin/customers").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/customers/" + c.id() + "/kyc-tier").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"tier":3,"reason":"self-upgrade"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminsCanUseAdminEndpoints() throws Exception {
        admin();
        String token = login(admin().email(), PASSWORD).path("accessToken").asString();
        mvc.perform(get("/api/v1/admin/customers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        TestCustomer c = customer();
        for (int i = 0; i < 5; i++) {
            loginRaw(c.email(), "wrong-password-1").andExpect(status().isUnauthorized());
        }
        // Even the right password is refused while locked.
        loginRaw(c.email(), PASSWORD).andExpect(status().isLocked());
    }

    @Test
    void unknownEmailGetsTheSameAnswerAsAWrongPassword() throws Exception {
        loginRaw("nobody-" + UUID.randomUUID() + "@test.local", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"));
    }

    @Test
    void refreshTokensRotateAndReuseRevokesTheSession() throws Exception {
        TestCustomer c = customer();
        String first = login(c.email(), PASSWORD).path("refreshToken").asString();

        JsonNode rotated = refresh(first).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(json::readTree);
        String second = rotated.path("refreshToken").asString();
        assertThat(second).isNotEqualTo(first);

        // Someone replays the old token: rejected, and the legitimate new one dies with it.
        refresh(first).andExpect(status().isUnauthorized());
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void transferEndpointRequiresIdempotencyKeyAndEnforcesOwnership() throws Exception {
        TestCustomer victim = customer("1000.00");
        TestCustomer attacker = customer();
        String token = login(attacker.email(), PASSWORD).path("accessToken").asString();
        String body = """
                {"sourceWalletNumber":"%s","destinationWalletNumber":"%s","amount":"100.00","narration":"x"}"""
                .formatted(victim.wallet(), attacker.wallet());

        mvc.perform(post("/api/v1/transfers/internal").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing required header: Idempotency-Key"));

        mvc.perform(post("/api/v1/transfers/internal").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/accounts/" + victim.wallet()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(balance(victim.wallet())).isEqualByComparingTo("1000.00");
    }

    @Test
    void httpTransfersReturn201And202() throws Exception {
        TestCustomer a = customer("1000.00");
        TestCustomer b = customer();
        String token = login(a.email(), PASSWORD).path("accessToken").asString();

        mvc.perform(post("/api/v1/transfers/internal").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletNumber":"%s","destinationWalletNumber":"%s","amount":"10.00","narration":"lunch"}"""
                                .formatted(a.wallet(), b.wallet())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mvc.perform(post("/api/v1/transfers/external").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletNumber":"%s","beneficiaryAccountNumber":"0123456785",
                                 "beneficiaryBankCode":"058","amount":"150.00","narration":"rent"}""".formatted(a.wallet())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.fee").value(10.0));
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult r = loginRaw(email, password).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions loginRaw(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}""".formatted(refreshToken)));
    }
}
