package com.wallet.integration;

import com.wallet.dto.request.TransferRequest;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.entity.Currency;
import com.wallet.entity.Wallet;
import com.wallet.exception.DuplicateIdempotencyKeyException;
import com.wallet.exception.InsufficientFundsException;
import com.wallet.service.TransferService;
import com.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real concurrency against PostgreSQL through the transactional proxy of {@link TransferService}.
 * <p>
 * There is deliberately no {@code @Transactional} here: every thread must run its own database transaction,
 * so row locks, commits and constraint checks behave exactly as in production.
 * {@code readyLatch} waits until all threads are parked. {@code startLatch} then releases them at the same instant.
 */
class TransferConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long TIMEOUT_SECONDS = 60;

    @Autowired
    private TransferService transferService;

    @Test
    void opposingConcurrentTransfersNeverDeadlockAndConserveMoney() throws Exception {
        Wallet a = createWallet(Currency.EUR, "1000.0000");
        Wallet b = createWallet(Currency.EUR, "1000.0000");

        List<Callable<TransactionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> transferService.transfer(request(a, b, "10.0000"), UUID.randomUUID().toString()));
            tasks.add(() -> transferService.transfer(request(b, a, "15.0000"), UUID.randomUUID().toString()));
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        // Any deadlock (SQLState 40P01) or lock failure would show up as an error here.
        assertThat(outcomes).filteredOn(Outcome::failed).isEmpty();
        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(20);

        BigDecimal balanceA = balanceOf(a);
        BigDecimal balanceB = balanceOf(b);
        assertThat(balanceA.compareTo(new BigDecimal("1050.0000"))).isZero();
        assertThat(balanceB.compareTo(new BigDecimal("950.0000"))).isZero();
        assertThat(balanceA.add(balanceB).compareTo(new BigDecimal("2000.0000"))).isZero();
        assertThat(transactionRepository.count()).isEqualTo(20);
    }

    @Test
    void concurrentTransfersCannotDoubleSpend() throws Exception {
        Wallet a = createWallet(Currency.BGN, "100.0000");
        Wallet b = createWallet(Currency.BGN, "0.0000");

        List<Callable<TransactionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> transferService.transfer(request(a, b, "30.0000"), UUID.randomUUID().toString()));
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(3);
        assertThat(outcomes).filteredOn(Outcome::failed)
                .hasSize(2)
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(InsufficientFundsException.class));
        assertThat(balanceOf(a).compareTo(new BigDecimal("10.0000"))).isZero();
        assertThat(balanceOf(b).compareTo(new BigDecimal("90.0000"))).isZero();
        assertThat(transactionRepository.count()).isEqualTo(3);
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyTransferOnlyOnce() throws Exception {
        Wallet a = createWallet(Currency.EUR, "1000.0000");
        Wallet b = createWallet(Currency.EUR, "0.0000");
        String sharedKey = "transfer-" + UUID.randomUUID();

        List<Callable<TransactionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> transferService.transfer(request(a, b, "50.0000"), sharedKey));
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(Outcome::failed)
                .hasSize(4)
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(DuplicateIdempotencyKeyException.class));
        assertThat(balanceOf(a).compareTo(new BigDecimal("950.0000"))).isZero();
        assertThat(balanceOf(b).compareTo(new BigDecimal("50.0000"))).isZero();
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static TransferRequest request(Wallet from, Wallet to, String amount) {
        return new TransferRequest(from.getId(), to.getId(), new BigDecimal(amount), null, null, null);
    }

    /** Starts all tasks at the same instant and collects every result or exception. Nothing is swallowed. */
    private static List<Outcome> runConcurrently(List<? extends Callable<?>> tasks) throws Exception {
        int threads = tasks.size();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return task.call();
                }));
            }
            assertThat(readyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            startLatch.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    outcomes.add(new Outcome(null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome(e.getCause()));
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private record Outcome(Throwable error) {
        boolean succeeded() {
            return error == null;
        }

        boolean failed() {
            return error != null;
        }
    }
}
