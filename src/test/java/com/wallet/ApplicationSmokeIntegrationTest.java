package com.wallet;

import com.wallet.entity.CategoryType;
import com.wallet.entity.Currency;
import com.wallet.entity.Tag;
import com.wallet.entity.Transaction;
import com.wallet.entity.TransactionStatus;
import com.wallet.entity.TransactionType;
import com.wallet.entity.Wallet;
import com.wallet.support.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 0 checks: Flyway migration, schema/entity sync ({@code ddl-auto=validate} lets the context start only when
 * they match), effective configuration, DB constraints and the OpenAPI endpoint.
 */
class ApplicationSmokeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayAppliedInitialMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void effectiveConfigurationMatchesTheSpec() {
        // src/test/resources/application.yml shadows the main one, so this guards against drift.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(environment.getProperty(
                "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch")).isEqualTo("true");
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(25);
    }

    @Test
    void entitiesRoundTripThroughTheRealSchema() {
        Wallet source = createWallet(Currency.EUR, "100.0000");
        Wallet target = createWallet(Currency.EUR, "0.0000");
        Tag tag = createTag("smoke");

        Transaction tx = new Transaction();
        tx.setSourceWallet(source);
        tx.setTargetWallet(target);
        tx.setCategory(createCategory("Smoke", CategoryType.TRANSFER));
        tx.setAmount(new BigDecimal("12.3400"));
        tx.setType(TransactionType.TRANSFER);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setIdempotencyKey(UUID.randomUUID().toString());
        tx.addTag(tag);
        Transaction saved = transactionRepository.saveAndFlush(tx);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_tags WHERE transaction_id = ?", Integer.class, saved.getId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, saved.getId()))
                .isEqualByComparingTo("12.34");
    }

    @Test
    void negativeBalanceIsRejectedByTheDatabase() {
        Wallet wallet = createWallet(Currency.BGN, "1.0000");
        wallet.setBalance(new BigDecimal("-0.0001"));

        assertThatThrownBy(() -> walletRepository.saveAndFlush(wallet))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void openApiDocsAreServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Digital Wallet API"));
    }

    @Test
    void unknownPathReturnsStandardErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
