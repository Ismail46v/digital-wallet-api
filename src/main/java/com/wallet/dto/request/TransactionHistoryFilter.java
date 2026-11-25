package com.wallet.dto.request;

import com.wallet.entity.TransactionType;

import java.time.Instant;
import java.util.Set;

/**
 * Optional filters for the wallet transaction history. A {@code null} field means "no filter".
 * The date range is inclusive on both ends. {@code tagIds} matches transactions that have ANY of the given tags.
 */
public record TransactionHistoryFilter(
        Instant fromDate,
        Instant toDate,
        TransactionType type,
        Long categoryId,
        Set<Long> tagIds) {

    public static TransactionHistoryFilter none() {
        return new TransactionHistoryFilter(null, null, null, null, null);
    }
}
