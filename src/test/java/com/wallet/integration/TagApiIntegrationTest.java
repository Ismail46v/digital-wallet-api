package com.wallet.integration;

import com.wallet.entity.Currency;
import com.wallet.entity.Tag;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TagApiIntegrationTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ Tag CRUD

    @Test
    void createReturns201WithLocationAndNormalizedName() throws Exception {
        mockMvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON).content(json("  GroCeries ")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/tags/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("groceries"));

        assertThat(tagRepository.findAll()).extracting(Tag::getName).containsExactly("groceries");
    }

    @Test
    void createValidatesTheName() throws Exception {
        mockMvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON).content(json("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        mockMvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON).content(json("x".repeat(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        mockMvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsDuplicateAfterNormalizationWith409() throws Exception {
        createTag("food");

        mockMvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON).content(json(" FOOD ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void listsAllTagsSortedByName() throws Exception {
        createTag("travel");
        createTag("bills");
        createTag("food");

        mockMvc.perform(get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name").value("bills"))
                .andExpect(jsonPath("$[1].name").value("food"))
                .andExpect(jsonPath("$[2].name").value("travel"));
    }

    @Test
    void getsTagByIdOr404() throws Exception {
        Tag tag = createTag("food");

        mockMvc.perform(get("/api/v1/tags/{id}", tag.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("food"));
        mockMvc.perform(get("/api/v1/tags/{id}", 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Tag with id 999 not found"));
    }

    @Test
    void updatesTagWith200_404_409() throws Exception {
        Tag food = createTag("food");
        createTag("bills");

        mockMvc.perform(put("/api/v1/tags/{id}", food.getId()).contentType(MediaType.APPLICATION_JSON).content(json(" Groceries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("groceries"));
        mockMvc.perform(put("/api/v1/tags/{id}", food.getId()).contentType(MediaType.APPLICATION_JSON).content(json("BILLS")))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/v1/tags/{id}", 999).contentType(MediaType.APPLICATION_JSON).content(json("x")))
                .andExpect(status().isNotFound());

        assertThat(tagRepository.findById(food.getId()).orElseThrow().getName()).isEqualTo("groceries");
    }

    @Test
    void deletesTagWith204Or404() throws Exception {
        Tag tag = createTag("food");

        mockMvc.perform(delete("/api/v1/tags/{id}", tag.getId())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/tags/{id}", tag.getId())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/tags/{id}", tag.getId())).andExpect(status().isNotFound());
    }

    @Test
    void deletingATagInUseDetachesItFromTransactionsWithoutForeignKeyErrors() throws Exception {
        Tag food = createTag("food");
        Tag home = createTag("home");
        Transaction tx = transfer(food, home);

        mockMvc.perform(delete("/api/v1/tags/{id}", food.getId())).andExpect(status().isNoContent());

        assertThat(transactionRepository.existsById(tx.getId())).isTrue();
        assertThat(joinRows(tx)).isEqualTo(1);
        mockMvc.perform(post("/api/v1/transactions/{tx}/tags/{tag}", tx.getId(), home.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(1)))
                .andExpect(jsonPath("$.tags[0].name").value("home"));
    }

    // ------------------------------------------------------------------ Transaction <-> Tag

    @Test
    void addsTagToTransactionIdempotently() throws Exception {
        Tag food = createTag("food");
        Tag bills = createTag("bills");
        Transaction tx = transfer(food);

        mockMvc.perform(post("/api/v1/transactions/{tx}/tags/{tag}", tx.getId(), bills.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId()))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.tags[0].name").value("bills"))
                .andExpect(jsonPath("$.tags[1].name").value("food"));
        mockMvc.perform(post("/api/v1/transactions/{tx}/tags/{tag}", tx.getId(), bills.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(2)));

        assertThat(joinRows(tx)).isEqualTo(2);
    }

    @Test
    void removesTagFromTransaction() throws Exception {
        Tag food = createTag("food");
        Tag bills = createTag("bills");
        Transaction tx = transfer(food, bills);

        mockMvc.perform(delete("/api/v1/transactions/{tx}/tags/{tag}", tx.getId(), food.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", hasSize(1)))
                .andExpect(jsonPath("$.tags[0].name").value("bills"));

        assertThat(joinRows(tx)).isEqualTo(1);
        assertThat(tagRepository.existsById(food.getId())).as("the tag itself is kept").isTrue();
    }

    @Test
    void addOrRemoveTagReturns404ForUnknownTransactionOrTag() throws Exception {
        Tag food = createTag("food");
        Transaction tx = transfer();

        mockMvc.perform(post("/api/v1/transactions/{tx}/tags/{tag}", 999, food.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Transaction with id 999 not found"));
        mockMvc.perform(post("/api/v1/transactions/{tx}/tags/{tag}", tx.getId(), 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Tag with id 999 not found"));
        mockMvc.perform(delete("/api/v1/transactions/{tx}/tags/{tag}", tx.getId(), 999))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String json(String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of("name", name));
    }

    private Transaction transfer(Tag... tags) {
        Wallet source = createWallet(Currency.EUR, "100.0000");
        Wallet target = createWallet(Currency.EUR, "0.0000");
        Transaction tx = new Transaction();
        tx.setSourceWallet(source);
        tx.setTargetWallet(target);
        tx.setAmount(new BigDecimal("10.0000"));
        tx.setType(TransactionType.TRANSFER);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setIdempotencyKey(UUID.randomUUID().toString());
        for (Tag tag : tags) {
            tx.addTag(tag);
        }
        return transactionRepository.saveAndFlush(tx);
    }

    private int joinRows(Transaction tx) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_tags WHERE transaction_id = ?", Integer.class, tx.getId());
        return rows == null ? 0 : rows;
    }
}
