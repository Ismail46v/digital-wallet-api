package com.wallet.integration;

import com.wallet.entity.CategoryType;
import com.wallet.entity.Currency;
import com.wallet.entity.Wallet;
import com.wallet.entity.WalletStatus;
import com.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of {@code POST /api/v1/transfers}: status codes, validation and the error body. */
class TransferApiIntegrationTest extends AbstractIntegrationTest {

    private static final String HEADER = "Idempotency-Key";

    @Test
    void successfulTransferReturns201() throws Exception {
        Wallet from = createWallet(Currency.EUR, "100.0000");
        Wallet to = createWallet(Currency.EUR, "1.0000");
        Long categoryId = createCategory("Rent", CategoryType.EXPENSE).getId();
        Long tagId = createTag("home").getId();

        Map<String, Object> body = body(from, to, "40.5");
        body.put("categoryId", categoryId);
        body.put("tagIds", List.of(tagId));
        body.put("description", "June rent");

        transfer("key-1", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.sourceWalletId").value(from.getId()))
                .andExpect(jsonPath("$.targetWalletId").value(to.getId()))
                .andExpect(jsonPath("$.amount").value(comparesEqualTo(40.5)))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.categoryName").value("Rent"))
                .andExpect(jsonPath("$.tags", hasSize(1)))
                .andExpect(jsonPath("$.tags[0].name").value("home"))
                .andExpect(jsonPath("$.idempotencyKey").value("key-1"))
                .andExpect(jsonPath("$.description").value("June rent"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(balanceOf(from)).isEqualByComparingTo("59.5");
        assertThat(balanceOf(to)).isEqualByComparingTo("41.5");
    }

    @Test
    void missingOrInvalidIdempotencyKeyReturns400() throws Exception {
        Wallet from = createWallet(Currency.EUR, "100.0000");
        Wallet to = createWallet(Currency.EUR, "0.0000");
        String json = objectMapper.writeValueAsString(body(from, to, "1"));

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['Idempotency-Key']").exists());
        transfer("   ", body(from, to, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['Idempotency-Key']").exists());
        transfer("k".repeat(101), body(from, to, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['Idempotency-Key']").exists());

        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        Wallet from = createWallet(Currency.EUR, "100.0000");
        Wallet to = createWallet(Currency.EUR, "0.0000");

        transfer("k1", body(from, to, "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
        transfer("k2", body(from, to, "1.00001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());

        Map<String, Object> missingSource = body(from, to, "1");
        missingSource.remove("fromWalletId");
        transfer("k3", missingSource)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fromWalletId").exists());
    }

    @Test
    void selfTransferReturns400() throws Exception {
        Wallet wallet = createWallet(Currency.EUR, "100.0000");

        transfer("self", body(wallet, wallet, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Source and target wallet must be different"));
    }

    @Test
    void currencyMismatchReturns400() throws Exception {
        transfer("fx", body(createWallet(Currency.EUR, "100.0000"), createWallet(Currency.USD, "0.0000"), "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Currency mismatch")));
    }

    @Test
    void unknownWalletOrTagReturns404AndRollsBack() throws Exception {
        Wallet from = createWallet(Currency.EUR, "100.0000");
        Wallet to = createWallet(Currency.EUR, "0.0000");

        Map<String, Object> unknownWallet = body(from, to, "1");
        unknownWallet.put("toWalletId", 999_999);
        transfer("w404", unknownWallet).andExpect(status().isNotFound());

        Map<String, Object> unknownTag = body(from, to, "1");
        unknownTag.put("tagIds", List.of(999_999));
        transfer("t404", unknownTag).andExpect(status().isNotFound());

        assertThat(balanceOf(from)).isEqualByComparingTo("100");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void frozenOrClosedWalletReturns409() throws Exception {
        Wallet active = createWallet(Currency.EUR, "100.0000");
        Wallet frozen = createWallet(Currency.EUR, "100.0000", WalletStatus.FROZEN);
        Wallet closed = createWallet(Currency.EUR, "0.0000", WalletStatus.CLOSED);

        transfer("f1", body(frozen, active, "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Wallet %d is FROZEN".formatted(frozen.getId())));
        transfer("f2", body(active, closed, "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Wallet %d is CLOSED".formatted(closed.getId())));
    }

    @Test
    void repeatedIdempotencyKeyReturns409AndChargesOnce() throws Exception {
        Wallet from = createWallet(Currency.BGN, "100.0000");
        Wallet to = createWallet(Currency.BGN, "0.0000");

        transfer("same-key", body(from, to, "25")).andExpect(status().isCreated());
        transfer("same-key", body(from, to, "25"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("same-key")));

        assertThat(balanceOf(from)).isEqualByComparingTo("75");
        assertThat(balanceOf(to)).isEqualByComparingTo("25");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void insufficientFundsReturns422() throws Exception {
        Wallet from = createWallet(Currency.EUR, "10.0000");
        Wallet to = createWallet(Currency.EUR, "0.0000");

        transfer("poor", body(from, to, "10.0001"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Entity"));

        assertThat(balanceOf(from)).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> body(Wallet from, Wallet to, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromWalletId", from.getId());
        body.put("toWalletId", to.getId());
        body.put("amount", new java.math.BigDecimal(amount));
        return body;
    }

    private ResultActions transfer(String idempotencyKey, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/transfers")
                .header(HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
