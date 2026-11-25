package com.wallet.service;

import com.wallet.dto.request.TransactionHistoryFilter;
import com.wallet.dto.response.PageResponse;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.entity.Transaction;
import com.wallet.exception.InvalidRequestException;
import com.wallet.exception.ResourceNotFoundException;
import com.wallet.mapper.TransactionMapper;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Filtered, paginated wallet history using a two-step fetch.
 * <ol>
 *     <li>{@link TransactionRepository#findIdsByFilter}: one page of ids, with LIMIT/OFFSET and COUNT in the database.</li>
 *     <li>{@link TransactionRepository#findAllWithDetailsByIdIn}: the full graph of those ids in one query.</li>
 * </ol>
 * The query count stays constant whatever the page size (no N+1). Hibernate never has to paginate a
 * collection fetch in memory (HHH90003004), which is configured as a hard error.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionHistoryService {

    static final Instant DEFAULT_FROM = Instant.parse("1970-01-01T00:00:00Z");
    static final Instant DEFAULT_TO = Instant.parse("2100-01-01T00:00:00Z");
    static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "amount", "id", "type", "status");
    /** Placeholder for the inactive tag filter: never matches, and avoids an empty {@code IN ()}. */
    private static final Set<Long> NO_TAG_IDS = Set.of(-1L);

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    public PageResponse<TransactionResponse> getHistory(Long walletId, TransactionHistoryFilter filter,
                                                        Pageable pageable) {
        if (!walletRepository.existsById(walletId)) {
            throw new ResourceNotFoundException("Wallet", walletId);
        }

        Instant from = filter.fromDate() != null ? filter.fromDate() : DEFAULT_FROM;
        Instant to = filter.toDate() != null ? filter.toDate() : DEFAULT_TO;
        if (from.isAfter(to)) {
            throw new InvalidRequestException("fromDate must not be after toDate");
        }
        boolean tagFilterActive = filter.tagIds() != null && !filter.tagIds().isEmpty();
        Set<Long> tagIds = tagFilterActive ? filter.tagIds() : NO_TAG_IDS;

        Page<Long> idPage = transactionRepository.findIdsByFilter(
                walletId, from, to, filter.type(), filter.categoryId(), tagFilterActive, tagIds, stableSort(pageable));

        Map<Long, Transaction> byId = idPage.isEmpty()
                ? Map.of()
                : transactionRepository.findAllWithDetailsByIdIn(idPage.getContent()).stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity(), (a, b) -> a));

        // Page.map keeps the order of step 1 and its paging metadata.
        return PageResponse.from(idPage.map(id -> transactionMapper.toResponse(byId.get(id))));
    }

    /**
     * Allows only known scalar properties: sorting by an association path would add an inner join and drop rows.
     * It also adds {@code id DESC} as a tie-breaker, so rows with the same timestamp never move between pages.
     */
    private static Pageable stableSort(Pageable pageable) {
        Sort sort = pageable.getSort();
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new InvalidRequestException("Unsupported sort property '%s'. Allowed: %s"
                        .formatted(order.getProperty(), SORTABLE_PROPERTIES.stream().sorted().toList()));
            }
        }
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Direction.DESC, "createdAt");
        }
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
