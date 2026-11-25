package com.wallet.service;

import com.wallet.dto.request.TransferRequest;
import com.wallet.dto.response.TagResponse;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.entity.Category;
import com.wallet.entity.CategoryType;
import com.wallet.entity.Currency;
import com.wallet.entity.Tag;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.entity.WalletStatus;
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
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String KEY = "key-123";

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private TagRepository tagRepository;
    @Spy
    private TransactionMapper transactionMapper = Mappers.getMapper(TransactionMapper.class);

    @InjectMocks
    private TransferService transferService;

    // ------------------------------------------------------------------ happy path

    @Test
    void transfersMoneyWithCategoryAndTags() {
        Wallet from = wallet(1L, Currency.EUR, "100.0000");
        Wallet to = wallet(2L, Currency.EUR, "5.0000");
        givenLocked(from, to);
        Category rent = category(7L, "Rent");
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(rent));
        Tag home = tag(10L, "home");
        Tag monthly = tag(11L, "monthly");
        when(tagRepository.findAllById(Set.of(10L, 11L))).thenReturn(List.of(monthly, home));
        givenSaveAssignsId(500L);

        TransactionResponse response = transferService.transfer(
                new TransferRequest(1L, 2L, new BigDecimal("30.5"), 7L, Set.of(10L, 11L), "June rent"), KEY);

        assertThat(from.getBalance()).isEqualByComparingTo("69.5");
        assertThat(to.getBalance()).isEqualByComparingTo("35.5");

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(saved.capture());
        Transaction tx = saved.getValue();
        assertThat(tx.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getSourceWallet()).isSameAs(from);
        assertThat(tx.getTargetWallet()).isSameAs(to);
        assertThat(tx.getCategory()).isSameAs(rent);
        assertThat(tx.getTags()).containsExactlyInAnyOrder(home, monthly);
        assertThat(tx.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(tx.getAmount()).isEqualByComparingTo("30.5").hasScaleOf(4);

        assertThat(response.id()).isEqualTo(500L);
        assertThat(response.sourceWalletId()).isEqualTo(1L);
        assertThat(response.targetWalletId()).isEqualTo(2L);
        assertThat(response.amount()).isEqualByComparingTo("30.5");
        assertThat(response.currency()).isEqualTo(Currency.EUR);
        assertThat(response.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.categoryName()).isEqualTo("Rent");
        assertThat(response.tags()).extracting(TagResponse::name).containsExactly("home", "monthly");
        assertThat(response.idempotencyKey()).isEqualTo(KEY);
        assertThat(response.description()).isEqualTo("June rent");
        assertThat(response.createdAt()).isNotNull();
    }

    // ------------------------------------------------------------------ deadlock prevention

    @ParameterizedTest(name = "from={0}, to={1}")
    @CsvSource({"5, 2", "2, 5"})
    void alwaysLocksTheSmallerWalletIdFirst(long fromId, long toId) {
        givenLocked(wallet(fromId, Currency.EUR, "100.0000"), wallet(toId, Currency.EUR, "0.0000"));
        givenSaveAssignsId(1L);

        transferService.transfer(request(fromId, toId, "10"), KEY);

        InOrder inOrder = inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdForUpdate(2L);
        inOrder.verify(walletRepository).findByIdForUpdate(5L);
        inOrder.verifyNoMoreInteractions();
    }

    // ------------------------------------------------------------------ validation failures

    @Test
    void rejectsSelfTransferBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> transferService.transfer(request(3L, 3L, "10"), KEY))
                .isInstanceOf(InvalidTransferException.class);

        verifyNoInteractions(walletRepository, transactionRepository, categoryRepository, tagRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.00001"})
    void rejectsInvalidAmount(String amount) {
        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, amount), KEY))
                .isInstanceOf(InvalidTransferException.class);

        verifyNoInteractions(walletRepository, transactionRepository);
    }

    @Test
    void rejectsInsufficientFunds() {
        Wallet from = wallet(1L, Currency.BGN, "29.9999");
        Wallet to = wallet(2L, Currency.BGN, "0.0000");
        givenLocked(from, to);

        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, "30"), KEY))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(from.getBalance()).isEqualByComparingTo("29.9999");
        assertThat(to.getBalance()).isEqualByComparingTo("0");
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest(name = "{0} wallet is {1}")
    @CsvSource({"SOURCE, FROZEN", "SOURCE, CLOSED", "TARGET, FROZEN", "TARGET, CLOSED"})
    void rejectsInactiveWallet(String side, WalletStatus status) {
        Wallet from = wallet(1L, Currency.EUR, "100.0000");
        Wallet to = wallet(2L, Currency.EUR, "0.0000");
        ("SOURCE".equals(side) ? from : to).setStatus(status);
        givenLocked(from, to);

        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, "10"), KEY))
                .isInstanceOf(WalletNotActiveException.class)
                .hasMessageContaining(status.name());

        assertThat(from.getBalance()).isEqualByComparingTo("100");
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCurrencyMismatch() {
        givenLocked(wallet(1L, Currency.EUR, "100.0000"), wallet(2L, Currency.USD, "0.0000"));

        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, "10"), KEY))
                .isInstanceOf(CurrencyMismatchException.class);

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsMissingWallet() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet(1L, Currency.EUR, "100.0000")));
        when(walletRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, "10"), KEY))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Wallet with id 2");
    }

    @Test
    void rejectsMissingCategory() {
        givenLocked(wallet(1L, Currency.EUR, "100.0000"), wallet(2L, Currency.EUR, "0.0000"));
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(1L, 2L, BigDecimal.TEN, 99L, null, null), KEY))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category");

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsMissingTag() {
        givenLocked(wallet(1L, Currency.EUR, "100.0000"), wallet(2L, Currency.EUR, "0.0000"));
        when(tagRepository.findAllById(Set.of(10L, 404L))).thenReturn(List.of(tag(10L, "home")));

        assertThatThrownBy(() -> transferService.transfer(
                new TransferRequest(1L, 2L, BigDecimal.TEN, null, Set.of(10L, 404L), null), KEY))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void rejectsAlreadyUsedIdempotencyKeyBeforeLocking() {
        when(transactionRepository.existsByIdempotencyKey(KEY)).thenReturn(true);

        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, "10"), KEY))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);

        verifyNoInteractions(walletRepository);
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentIdempotencyKeyRaceIntoDuplicateKeyException() {
        givenLocked(wallet(1L, Currency.EUR, "100.0000"), wallet(2L, Currency.EUR, "0.0000"));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(uniqueViolation(TransferService.IDEMPOTENCY_KEY_CONSTRAINT));

        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, "10"), KEY))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void doesNotMaskOtherIntegrityViolations() {
        givenLocked(wallet(1L, Currency.EUR, "100.0000"), wallet(2L, Currency.EUR, "0.0000"));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(uniqueViolation("some_other_constraint"));

        assertThatThrownBy(() -> transferService.transfer(request(1L, 2L, "10"), KEY))
                .isExactlyInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static TransferRequest request(long fromId, long toId, String amount) {
        return new TransferRequest(fromId, toId, new BigDecimal(amount), null, null, null);
    }

    private static Wallet wallet(long id, Currency currency, String balance) {
        Wallet wallet = new Wallet();
        wallet.setId(id);
        wallet.setCurrency(currency);
        wallet.setBalance(new BigDecimal(balance));
        wallet.setStatus(WalletStatus.ACTIVE);
        return wallet;
    }

    private static Category category(long id, String name) {
        Category category = new Category(name, CategoryType.EXPENSE);
        category.setId(id);
        return category;
    }

    private static Tag tag(long id, String name) {
        Tag tag = new Tag(name);
        tag.setId(id);
        return tag;
    }

    private void givenLocked(Wallet... wallets) {
        for (Wallet wallet : wallets) {
            when(walletRepository.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
        }
    }

    private void givenSaveAssignsId(long id) {
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            tx.setId(id);
            tx.setCreatedAt(Instant.now());
            return tx;
        });
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        SQLException sql = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"%s\"".formatted(constraint), "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, constraint));
    }
}
