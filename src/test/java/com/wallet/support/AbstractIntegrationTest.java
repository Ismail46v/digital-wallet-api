package com.wallet.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.entity.Category;
import com.wallet.entity.CategoryType;
import com.wallet.entity.Currency;
import com.wallet.entity.Tag;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.entity.WalletStatus;
import com.wallet.repository.CategoryRepository;
import com.wallet.repository.TagRepository;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for every integration test: a real PostgreSQL 16 in Docker (no H2), with Flyway and
 * {@code ddl-auto=validate} applied exactly as in production.
 * <p>
 * <b>Singleton container pattern:</b> the container starts once per JVM in a static initializer and is
 * shared by all subclasses. Testcontainers' Ryuk sidecar removes it when the JVM exits. It is deliberately NOT a
 * {@code @Container} field: the JUnit extension would stop it after the first test class, while the cached
 * Spring context (and its connection pool) would still point to the dead port.
 * <p>
 * <b>No {@code @Transactional} here or in subclasses</b>, especially concurrency tests: a test-managed
 * transaction would hide commits, locks and isolation from the code under test. Fixtures are written with
 * {@code saveAndFlush} (auto-committed), and {@link #cleanDatabase()} truncates all tables after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("wallet_db")
                    .withUsername("wallet")
                    .withPassword("wallet");

    static {
        POSTGRES.start();
    }

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected WalletRepository walletRepository;

    @Autowired
    protected CategoryRepository categoryRepository;

    @Autowired
    protected TagRepository tagRepository;

    @Autowired
    protected TransactionRepository transactionRepository;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE transaction_tags, transactions, tags, categories, wallets, users
                RESTART IDENTITY CASCADE""");
    }

    // ------------------------------------------------------------------ fixtures

    protected User createUser() {
        int n = SEQUENCE.incrementAndGet();
        return createUser("Test User " + n, "user" + n + "@example.com");
    }

    protected User createUser(String fullName, String email) {
        return userRepository.saveAndFlush(new User(fullName, email));
    }

    protected Wallet createWallet(Currency currency, String balance) {
        return createWallet(createUser(), currency, balance, WalletStatus.ACTIVE);
    }

    protected Wallet createWallet(Currency currency, String balance, WalletStatus status) {
        return createWallet(createUser(), currency, balance, status);
    }

    protected Wallet createWallet(User owner, Currency currency, String balance, WalletStatus status) {
        Wallet wallet = new Wallet(owner, currency, new BigDecimal(balance));
        wallet.setStatus(status);
        return walletRepository.saveAndFlush(wallet);
    }

    protected Category createCategory(String name, CategoryType type) {
        return categoryRepository.saveAndFlush(new Category(name, type));
    }

    protected Tag createTag(String name) {
        return tagRepository.saveAndFlush(new Tag(name));
    }

    protected BigDecimal balanceOf(Wallet wallet) {
        return walletRepository.findById(wallet.getId()).orElseThrow().getBalance();
    }
}
