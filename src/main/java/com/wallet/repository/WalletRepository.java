package com.wallet.repository;

import com.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * Shared row lock ({@code SELECT ... FOR UPDATE}) used by every operation that changes a balance.
     * It must run inside a transaction.
     * <p>
     * When locking more than one wallet, always lock in ascending id order to prevent deadlocks.
     * Do not load the same wallet earlier in the same transaction: Hibernate would return the
     * already-managed (stale) instance instead of the freshly locked row state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
