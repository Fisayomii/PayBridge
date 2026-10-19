package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.client.IdentityVerifier;
import com.academy.paybridge.customer.client.IdentityVerifier.BvnRecord;
import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.domain.KycVerification;
import com.academy.paybridge.customer.domain.KycVerification.Status;
import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.customer.repository.KycVerificationRepository;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.provider.GatewayRejectedException;
import com.academy.paybridge.shared.security.AuthenticatedUser;
import com.academy.paybridge.shared.security.Role;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Self-service KYC: a tier-1 customer submits their BVN; the identity provider returns the name on it; if that
 * name matches the profile, the customer moves to tier 2 (CBN tiered KYC: BVN-verified), which raises their
 * limits. The provider is called outside any transaction; only the outcome is written.
 */
@Service
public class KycService {

    private static final Logger log = LogManager.getLogger(KycService.class);
    private static final String BVN = "BVN";
    private static final int BVN_VERIFIED_TIER = 2;
    private static final int MAX_FAILED_PER_DAY = 5;

    private final IdentityVerifier verifier;
    private final CustomerRepository customerRepository;
    private final KycVerificationRepository verificationRepository;
    private final CustomerService customerService;
    private final TransactionTemplate tx;
    private final byte[] hashKey;

    public KycService(IdentityVerifier verifier, CustomerRepository customerRepository,
                      KycVerificationRepository verificationRepository, CustomerService customerService,
                      TransactionTemplate tx, @Value("${paybridge.kyc.hash-key}") String hashKey) {
        if (hashKey == null || hashKey.length() < 32) {
            throw new IllegalStateException("paybridge.kyc.hash-key must be at least 32 characters");
        }
        this.verifier = verifier;
        this.customerRepository = customerRepository;
        this.verificationRepository = verificationRepository;
        this.customerService = customerService;
        this.tx = tx;
        this.hashKey = hashKey.getBytes(StandardCharsets.UTF_8);
    }

    public VerificationResult verifyBvn(AuthenticatedUser user, String rawBvn) {
        if (user.role() != Role.CUSTOMER) {
            throw PayBridgeException.badRequest("Only customers verify a BVN");
        }
        String bvn = rawBvn == null ? "" : rawBvn.trim();
        if (!bvn.matches("\\d{11}")) {
            throw PayBridgeException.badRequest("A BVN is exactly 11 digits");
        }
        Customer customer = customerRepository.findById(user.customerId())
                .orElseThrow(() -> PayBridgeException.notFound("Customer not found"));
        if (verificationRepository.existsByCustomerIdAndIdTypeAndStatus(customer.getId(), BVN, Status.VERIFIED)) {
            throw PayBridgeException.conflict("Your BVN is already verified");
        }
        long recentFailures = verificationRepository.countByCustomerIdAndStatusAndCreatedAtAfter(
                customer.getId(), Status.FAILED, Instant.now().minus(Duration.ofDays(1)));
        if (recentFailures >= MAX_FAILED_PER_DAY) {
            throw new PayBridgeException("Too many failed verification attempts today. Try again tomorrow or contact support.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        String hash = hmac(bvn);
        if (verificationRepository.existsByIdTypeAndIdNumberHashAndStatus(BVN, hash, Status.VERIFIED)) {
            return record(customer, bvn, hash, Status.FAILED, "This BVN is already linked to another PayBridge profile");
        }

        BvnRecord identity;
        try {
            identity = verifier.lookupBvn(bvn); // GatewayUnavailableException propagates as 503; nothing recorded
        } catch (GatewayRejectedException ex) {
            return record(customer, bvn, hash, Status.FAILED, "BVN not found by " + verifier.providerName());
        }
        if (!namesMatch(identity, customer.getFullName())) {
            return record(customer, bvn, hash, Status.FAILED, "The name on this BVN does not match your profile name");
        }
        try {
            return record(customer, bvn, hash, Status.VERIFIED, null);
        } catch (DataIntegrityViolationException ex) {
            // Another profile verified the same BVN a moment ago.
            return record(customer, bvn, hash, Status.FAILED, "This BVN is already linked to another PayBridge profile");
        }
    }

    public List<VerificationResult> history(AuthenticatedUser user) {
        return verificationRepository.findByCustomerIdOrderByCreatedAtDesc(user.customerId()).stream()
                .map(KycService::toResult).toList();
    }

    private VerificationResult record(Customer customer, String bvn, String hash, Status status, String reason) {
        return tx.execute(s -> {
            int before = customerRepository.findById(customer.getId()).orElseThrow().getKycTier();
            int after = before;
            if (status == Status.VERIFIED && before < BVN_VERIFIED_TIER) {
                after = customerService.changeKycTier(customer.getId(), BVN_VERIFIED_TIER,
                        "BVN verified via " + verifier.providerName(), customer.getId()).kycTier();
            }
            KycVerification v = verificationRepository.saveAndFlush(new KycVerification(customer.getId(), BVN,
                    mask(bvn), hash, verifier.providerName(), status, reason, before, after));
            log.info("AUDIT KYC {} for customer {} via {} ({} -> tier {}){}", status, customer.getId(),
                    verifier.providerName(), before, after, reason == null ? "" : ": " + reason);
            return toResult(v);
        });
    }

    /** The BVN's first and last names must both appear in the profile name, in any order and case. */
    static boolean namesMatch(BvnRecord identity, String profileName) {
        Set<String> profile = words(profileName);
        Set<String> first = words(identity.firstName());
        Set<String> last = words(identity.lastName());
        return !first.isEmpty() && !last.isEmpty() && profile.containsAll(first) && profile.containsAll(last);
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toUpperCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    private static String mask(String bvn) {
        return bvn.substring(0, 3) + "*****" + bvn.substring(8);
    }

    /** Keyed hash: an 11-digit number is easy to brute-force from a plain SHA-256. */
    private String hmac(String bvn) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bvn.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static VerificationResult toResult(KycVerification v) {
        return new VerificationResult(v.getIdType(), v.getIdNumberMask(), v.getProvider(), v.getStatus().name(),
                v.getFailureReason(), v.getTierBefore(), v.getTierAfter(), v.getCreatedAt());
    }

    public record VerificationResult(String idType, String idNumber, String provider, String status,
                                     String failureReason, int tierBefore, int tierAfter, Instant verifiedAt) {}
}
