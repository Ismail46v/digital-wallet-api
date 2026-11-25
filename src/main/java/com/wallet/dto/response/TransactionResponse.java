package com.wallet.dto.response;

import com.wallet.entity.Currency;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Shared response for every transaction type (TRANSFER, DEPOSIT, WITHDRAWAL).
 * {@code sourceWalletId} is null for DEPOSIT and {@code targetWalletId} is null for WITHDRAWAL.
 * {@code tags} are sorted by name.
 */
public record TransactionResponse(
        Long id,
        Long sourceWalletId,
        Long targetWalletId,
        BigDecimal amount,
        Currency currency,
        TransactionType type,
        TransactionStatus status,
        Long categoryId,
        String categoryName,
        List<TagResponse> tags,
        String idempotencyKey,
        String description,
        Instant createdAt) {
}
