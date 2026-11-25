package com.wallet.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.wallet.entity.Category;
import com.wallet.entity.CategoryType;
import com.wallet.entity.Currency;
import com.wallet.entity.Tag;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code GET /api/v1/wallets/{walletId}/transactions}: the two-step custom {@code @Query} on a real PostgreSQL.
 * <pre>
 *  id  type        source -> target  amount   category  tags            created_at
 *  t1  DEPOSIT        -   -> w1      100.00   Salary    salary          2026-01-10
 *  t2  TRANSFER      w1   -> w2      250.00   Rent      home, monthly   2026-02-05
 *  t3  WITHDRAWAL    w1   -> -        40.00   Food      food            2026-02-20
 *  t4  TRANSFER      w2   -> w1       15.00   -         -               2026-03-01
 *  t5  TRANSFER      w2   -> w3        5.00   -         home            2026-03-05   (not w1)
 * </pre>
 */
class TransactionHistoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Wallet w1;
    private Wallet w2;
    private Category rent;
    private Tag home;
    private Tag food;
    private Transaction t1;
    private Transaction t2;
    private Transaction t3;
    private Transaction t4;
    private Transaction t5;

    @BeforeEach
    void setUp() {
        w1 = createWallet(Currency.EUR, "1000.0000");
        w2 = createWallet(Currency.EUR, "1000.0000");
        Wallet w3 = createWallet(Currency.EUR, "0.0000");
        Category salary = createCategory("Salary", CategoryType.INCOME);
        rent = createCategory("Rent", CategoryType.EXPENSE);
        Category foodCategory = createCategory("Food", CategoryType.EXPENSE);
        Tag salaryTag = createTag("salary");
        home = createTag("home");
        Tag monthly = createTag("monthly");
        food = createTag("food");

        t1 = tx(TransactionType.DEPOSIT, null, w1, "100.0000", salary, "2026-01-10T09:00:00Z", salaryTag);
        t2 = tx(TransactionType.TRANSFER, w1, w2, "250.0000", rent, "2026-02-05T10:00:00Z", home, monthly);
        t3 = tx(TransactionType.WITHDRAWAL, w1, null, "40.0000", foodCategory, "2026-02-20T18:30:00Z", food);
        t4 = tx(TransactionType.TRANSFER, w2, w1, "15.0000", null, "2026-03-01T12:00:00Z");
        t5 = tx(TransactionType.TRANSFER, w2, w3, "5.0000", null, "2026-03-05T12:00:00Z", home);
    }

    @Test
    void withoutFiltersReturnsAllTransactionsOfTheWalletNewestFirst() throws Exception {
        JsonNode page = getOk(history(w1));

        assertThat(ids(page)).containsExactly(t4.getId(), t3.getId(), t2.getId(), t1.getId());
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);

        JsonNode transfer = page.get("content").get(2);
        assertThat(transfer.get("sourceWalletId").asLong()).isEqualTo(w1.getId());
        assertThat(transfer.get("targetWalletId").asLong()).isEqualTo(w2.getId());
        assertThat(transfer.get("currency").asText()).isEqualTo("EUR");
        assertThat(transfer.get("categoryName").asText()).isEqualTo("Rent");
        assertThat(transfer.get("tags").findValuesAsText("name")).containsExactly("home", "monthly");
        assertThat(new BigDecimal(transfer.get("amount").asText())).isEqualByComparingTo("250");

        JsonNode deposit = page.get("content").get(3);
        assertThat(deposit.get("sourceWalletId").isNull()).isTrue();
        assertThat(deposit.get("currency").asText()).isEqualTo("EUR");
    }

    @Test
    void filtersByPeriodInclusive() throws Exception {
        JsonNode page = getOk(history(w1)
                .param("fromDate", "2026-02-01T00:00:00Z")
                .param("toDate", "2026-02-20T18:30:00Z"));

        assertThat(ids(page)).containsExactly(t3.getId(), t2.getId());
    }

    @Test
    void filtersByType() throws Exception {
        assertThat(ids(getOk(history(w1).param("type", "TRANSFER"))))
                .containsExactly(t4.getId(), t2.getId());
    }

    @Test
    void filtersByCategory() throws Exception {
        assertThat(ids(getOk(history(w1).param("categoryId", rent.getId().toString()))))
                .containsExactly(t2.getId());
    }

    @Test
    void filtersByAnyOfTheGivenTags() throws Exception {
        assertThat(ids(getOk(history(w1).param("tagIds", food.getId().toString(), home.getId().toString()))))
                .containsExactly(t3.getId(), t2.getId());
        assertThat(ids(getOk(history(w2).param("tagIds", home.getId().toString()))))
                .containsExactly(t5.getId(), t2.getId());
    }

    @Test
    void combinesFilters() throws Exception {
        JsonNode page = getOk(history(w1)
                .param("type", "TRANSFER")
                .param("tagIds", home.getId().toString())
                .param("fromDate", "2026-02-01T00:00:00Z"));

        assertThat(ids(page)).containsExactly(t2.getId());
    }

    @Test
    void paginatesInTheDatabase() throws Exception {
        JsonNode first = getOk(history(w1).param("page", "0").param("size", "2"));
        assertThat(ids(first)).containsExactly(t4.getId(), t3.getId());
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(first.get("totalElements").asLong()).isEqualTo(4);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("first").asBoolean()).isTrue();
        assertThat(first.get("last").asBoolean()).isFalse();

        JsonNode second = getOk(history(w1).param("page", "1").param("size", "2"));
        assertThat(ids(second)).containsExactly(t2.getId(), t1.getId());
        assertThat(second.get("last").asBoolean()).isTrue();
    }

    @Test
    void supportsCustomSort() throws Exception {
        assertThat(ids(getOk(history(w1).param("sort", "amount,asc"))))
                .containsExactly(t4.getId(), t3.getId(), t1.getId(), t2.getId());
    }

    @Test
    void emptyResultIsAnEmptyPage() throws Exception {
        JsonNode page = getOk(history(w1).param("fromDate", "2030-01-01T00:00:00Z"));

        assertThat(ids(page)).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    @Test
    void unknownWalletReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/{id}/transactions", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Wallet with id 999999 not found"));
    }

    @Test
    void invalidParametersReturn400() throws Exception {
        mockMvc.perform(history(w1).param("type", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.type").exists());
        mockMvc.perform(history(w1).param("fromDate", "not-a-date"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(history(w1).param("sort", "category.name,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unsupported sort")));
        mockMvc.perform(history(w1)
                        .param("fromDate", "2026-03-01T00:00:00Z")
                        .param("toDate", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Proof that there is no N+1: the number of SQL statements per request is the same for a page of 2 and a page
     * of 20, although every row has a category, two wallets and tags.
     */
    @Test
    void queryCountDoesNotGrowWithPageSize() throws Exception {
        for (int i = 0; i < 20; i++) {
            tx(TransactionType.TRANSFER, w1, w2, "1.0000", rent, "2026-04-01T00:00:%02dZ".formatted(i), home, food);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        getOk(history(w1).param("size", "2"));
        long statementsForSmallPage = statistics.getPrepareStatementCount();

        statistics.clear();
        JsonNode bigPage = getOk(history(w1).param("size", "20"));
        long statementsForBigPage = statistics.getPrepareStatementCount();

        assertThat(ids(bigPage)).hasSize(20);
        // wallet exists + ids page + count + details
        assertThat(statementsForBigPage).isLessThanOrEqualTo(4).isEqualTo(statementsForSmallPage);
    }

    // ------------------------------------------------------------------ helpers

    private Transaction tx(TransactionType type, Wallet source, Wallet target, String amount, Category category,
                           String createdAt, Tag... tags) {
        Transaction tx = new Transaction();
        tx.setType(type);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setSourceWallet(source);
        tx.setTargetWallet(target);
        tx.setAmount(new BigDecimal(amount));
        tx.setCategory(category);
        tx.setIdempotencyKey(UUID.randomUUID().toString());
        tx.setCreatedAt(Instant.parse(createdAt));
        for (Tag tag : tags) {
            tx.addTag(tag);
        }
        return transactionRepository.saveAndFlush(tx);
    }

    private static MockHttpServletRequestBuilder history(Wallet wallet) {
        return get("/api/v1/wallets/{walletId}/transactions", wallet.getId());
    }

    private JsonNode getOk(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }
}
