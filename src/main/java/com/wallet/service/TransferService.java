package com.wallet.service;

import com.wallet.dto.request.TransferRequest;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.entity.Category;
import com.wallet.entity.Tag;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.exception.CurrencyMismatchException;
import com.wallet.exception.DuplicateIdempotencyKeyException;
import com.wallet.exception.InsufficientFundsException;
import com.wallet.exception.InvalidTransferException;
import com.wallet.exception.ResourceNotFoundException;
import com.wallet.exception.WalletNotActiveException;
import com.wallet.mapper.TransactionMapper;
import com.wallet.repository.CategoryRepository;
import com.wallet.repository.TagRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Atomic wallet-to-wallet transfer.
 * <ul>
 *     <li><b>Isolation:</b> both wallet rows are locked with {@code SELECT ... FOR UPDATE} for the whole
 *     transaction. Balances are read only after the lock, so they are always the latest committed values.</li>
 *     <li><b>Deadlock prevention:</b> the rows are always locked in ascending id order, whatever the transfer
 *     direction. Two opposing transfers (A&rarr;B and B&rarr;A) therefore queue on the same first lock instead of
 *     each holding one lock and waiting for the other.</li>
 *     <li><b>Idempotency:</b> a cheap pre-check, then the {@code UNIQUE} constraint as the real guard for
 *     concurrent requests with the same key (see {@link #saveAndFlush}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    /** Name of the UNIQUE constraint on {@code transactions.idempotency_key} (see V1__init_schema.sql). */
    static final String IDEMPOTENCY_KEY_CONSTRAINT = "uq_transactions_idempotency_key";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final int AMOUNT_SCALE = 4;

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final TransactionMapper transactionMapper;

    @Transactional
    public TransactionResponse transfer(TransferRequest request, String idempotencyKey) {
        // 1. Fast checks, no database access.
        Long fromId = request.fromWalletId();
        Long toId = request.toWalletId();
        if (fromId == null || toId == null) {
            throw new InvalidTransferException("Both fromWalletId and toWalletId are required");
        }
        if (fromId.equals(toId)) {
            throw new InvalidTransferException("Source and target wallet must be different");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransferException("Idempotency-Key is required");
        }
        BigDecimal amount = normalizeAmount(request.amount());

        // 2. Idempotency pre-check. It is not race-free on its own; the UNIQUE constraint handles races.
        if (transactionRepository.existsByIdempotencyKey(idempotencyKey)) {
            throw new DuplicateIdempotencyKeyException(idempotencyKey);
        }

        // 3. Deadlock prevention: always lock the smaller id first.
        //    No wallet may be loaded before this point in the transaction, otherwise Hibernate would hand back
        //    the already-managed (stale) instance instead of the locked row.
        Long firstId = Math.min(fromId, toId);
        Long secondId = Math.max(fromId, toId);
        Wallet first = lockWallet(firstId);
        Wallet second = lockWallet(secondId);
        Wallet from = fromId.equals(firstId) ? first : second;
        Wallet to = fromId.equals(firstId) ? second : first;

        // 4. Business validation on the locked, up-to-date rows.
        requireActive(from);
        requireActive(to);
        if (from.getCurrency() != to.getCurrency()) {
            throw new CurrencyMismatchException(from.getCurrency(), to.getCurrency());
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(from.getId());
        }

        // 5. Optional relations.
        Category category = resolveCategory(request.categoryId());
        Set<Tag> tags = resolveTags(request.tagIds());

        // 6. Execute.
        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));

        Transaction transaction = new Transaction();
        transaction.setSourceWallet(from);
        transaction.setTargetWallet(to);
        transaction.setCategory(category);
        transaction.setTags(tags);
        transaction.setAmount(amount);
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setDescription(request.description());

        // 7. Map inside the transaction (open-in-view is off).
        return transactionMapper.toResponse(saveAndFlush(transaction, idempotencyKey));
    }

    /**
     * Two requests with the same key can both pass the pre-check. The second INSERT then violates
     * {@value #IDEMPOTENCY_KEY_CONSTRAINT}. PostgreSQL makes it wait until the first transaction commits,
     * then raises 23505. {@code saveAndFlush} forces that error to happen here rather than at commit time, so it
     * can be translated into {@link DuplicateIdempotencyKeyException} (409). Throwing still rolls back the whole
     * transaction, including the balance changes. Other integrity errors are re-thrown unchanged.
     */
    private Transaction saveAndFlush(Transaction transaction, String idempotencyKey) {
        try {
            return transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            if (isIdempotencyKeyViolation(ex)) {
                throw new DuplicateIdempotencyKeyException(idempotencyKey, ex);
            }
            throw ex;
        }
    }

    static boolean isIdempotencyKeyViolation(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve
                    && IDEMPOTENCY_KEY_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sql
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(IDEMPOTENCY_KEY_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > AMOUNT_SCALE) {
            throw new InvalidTransferException("Amount must have at most %d decimal places".formatted(AMOUNT_SCALE));
        }
        return amount.setScale(AMOUNT_SCALE);
    }

    private Wallet lockWallet(Long id) {
        return walletRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", id));
    }

    private static void requireActive(Wallet wallet) {
        if (!wallet.isActive()) {
            throw new WalletNotActiveException(wallet.getId(), wallet.getStatus());
        }
    }

    private Category resolveCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));
    }

    private Set<Tag> resolveTags(Set<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return new HashSet<>();
        }
        // Not tagIds.contains(null): immutable sets (Set.of) throw NPE on that call.
        if (tagIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidTransferException("tagIds must not contain null");
        }
        List<Tag> found = tagRepository.findAllById(tagIds);
        if (found.size() < tagIds.size()) {
            Set<Long> missing = new TreeSet<>(tagIds);
            found.forEach(tag -> missing.remove(tag.getId()));
            throw new ResourceNotFoundException("Tag(s) not found: " + missing);
        }
        return new HashSet<>(found);
    }
}
