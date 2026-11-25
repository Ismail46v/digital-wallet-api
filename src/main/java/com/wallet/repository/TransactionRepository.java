package com.wallet.repository;

import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * History step 1 of 2: only the ids of one page, with filters, sorting and LIMIT/OFFSET done by the database.
     * <ul>
     *     <li>{@code t.sourceWallet.id} / {@code t.targetWallet.id} compile to the FK columns with no join, so the
     *     {@code OR} keeps DEPOSIT and WITHDRAWAL rows (an inner join would drop them).</li>
     *     <li>Dates are never null: the service supplies defaults. PostgreSQL cannot infer the type of a null
     *     timestamp parameter in {@code :p IS NULL}.</li>
     *     <li>The tag filter uses {@code EXISTS}, so there are no duplicate rows and no DISTINCT. It matches
     *     transactions that have ANY of the given tags. When the filter is inactive, a placeholder list is
     *     passed so the SQL never contains an empty {@code IN ()}.</li>
     * </ul>
     */
    @Query(value = """
            SELECT t.id FROM Transaction t
            WHERE (t.sourceWallet.id = :walletId OR t.targetWallet.id = :walletId)
              AND t.createdAt >= :fromDate
              AND t.createdAt <= :toDate
              AND (:type IS NULL OR t.type = :type)
              AND (:categoryId IS NULL OR t.category.id = :categoryId)
              AND (:tagFilterActive = false
                   OR EXISTS (SELECT 1 FROM t.tags tg WHERE tg.id IN :tagIds))
            """,
            countQuery = """
            SELECT COUNT(t) FROM Transaction t
            WHERE (t.sourceWallet.id = :walletId OR t.targetWallet.id = :walletId)
              AND t.createdAt >= :fromDate
              AND t.createdAt <= :toDate
              AND (:type IS NULL OR t.type = :type)
              AND (:categoryId IS NULL OR t.category.id = :categoryId)
              AND (:tagFilterActive = false
                   OR EXISTS (SELECT 1 FROM t.tags tg WHERE tg.id IN :tagIds))
            """)
    Page<Long> findIdsByFilter(@Param("walletId") Long walletId,
                               @Param("fromDate") Instant fromDate,
                               @Param("toDate") Instant toDate,
                               @Param("type") TransactionType type,
                               @Param("categoryId") Long categoryId,
                               @Param("tagFilterActive") boolean tagFilterActive,
                               @Param("tagIds") Collection<Long> tagIds,
                               Pageable pageable);

    /**
     * History step 2 of 2: loads the full graph of the given ids in one query. There is no pagination here,
     * so fetching the collection is safe. The result order is undefined: the caller restores the order of step 1.
     */
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.tags
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.sourceWallet
            LEFT JOIN FETCH t.targetWallet
            WHERE t.id IN :ids
            """)
    List<Transaction> findAllWithDetailsByIdIn(@Param("ids") Collection<Long> ids);

    /** A single transaction with everything {@code TransactionMapper} needs, in one query. */
    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.tags
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.sourceWallet
            LEFT JOIN FETCH t.targetWallet
            WHERE t.id = :id
            """)
    Optional<Transaction> findWithDetailsById(@Param("id") Long id);
}
