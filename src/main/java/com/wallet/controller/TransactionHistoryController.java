package com.wallet.controller;

import com.wallet.dto.request.TransactionHistoryFilter;
import com.wallet.dto.response.PageResponse;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.entity.TransactionType;
import com.wallet.service.TransactionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/**
 * This class is separate from the wallet CRUD controller on purpose, so both developers can work on
 * {@code /api/v1/wallets/**} without touching the same file.
 * Only {@code GET /api/v1/wallets/{walletId}/transactions} is mapped here.
 */
@Tag(name = "Transaction history", description = "Filtered, paginated history of a wallet")
@RestController
@RequiredArgsConstructor
public class TransactionHistoryController {

    private final TransactionHistoryService transactionHistoryService;

    @Operation(summary = "Transactions where the wallet is the source or the target",
            description = "All filters are optional. The date range is inclusive. tagIds matches ANY of the given tags. "
                    + "Sortable by createdAt, amount, id, type, status (default createdAt,desc).")
    @GetMapping("/api/v1/wallets/{walletId}/transactions")
    public PageResponse<TransactionResponse> getHistory(
            @PathVariable Long walletId,
            @Parameter(description = "ISO-8601 instant, e.g. 2026-01-01T00:00:00Z")
            @RequestParam(required = false) Instant fromDate,
            @Parameter(description = "ISO-8601 instant, e.g. 2026-12-31T23:59:59Z")
            @RequestParam(required = false) Instant toDate,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Set<Long> tagIds,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return transactionHistoryService.getHistory(
                walletId, new TransactionHistoryFilter(fromDate, toDate, type, categoryId, tagIds), pageable);
    }
}
