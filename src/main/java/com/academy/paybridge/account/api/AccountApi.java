package com.academy.paybridge.account.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AccountApi {
    AccountDto getByWalletNumber(String walletNumber);
    AccountDto getById(UUID accountId);
    List<AccountDto> getByCustomer(UUID customerId);

    /** Opens a customer wallet with a zero balance. Money only ever enters through a ledgered funding. */
    AccountDto openWallet(UUID customerId, String ownerName, String email, String currency);

    /**
     * Atomically moves {@code amount} from one account to another. Both rows are locked
     * (in a fixed order, so concurrent opposite-direction transfers cannot deadlock),
     * currencies are checked, a customer account can never go negative, and for
     * {@link MovementKind#CUSTOMER_INITIATED} movements account status (freeze / PND) is enforced.
     * Must be called inside the caller's transaction together with the ledger posting.
     */
    void moveFunds(UUID debitAccountId, UUID creditAccountId, BigDecimal amount, String currency, MovementKind kind);

    /**
     * Locks every account a transaction will touch, in one statement ordered by id, for the rest of the
     * caller's transaction. Taking the whole set up front (rather than one row now and more later) keeps a
     * single global lock order, so concurrent transactions cannot deadlock. Checks that depend on balances
     * or history (limits, velocity, max balance) must read the returned, locked values.
     */
    Map<UUID, AccountDto> lockForUpdate(Collection<UUID> accountIds);
}
