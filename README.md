# Digital Wallet API

Spring Boot 3.4 backend for a digital wallet: users, multi-currency wallets, atomic transfers with idempotency,
tags and filtered transaction history. Team: **Ismail** and **Erdzhan**.

| | |
|---|---|
| Java | 21 |
| Spring Boot | 3.4.13 (Maven Wrapper, Maven 3.9.11) |
| Database | PostgreSQL 16, Flyway migrations, `ddl-auto: validate` |
| Mapping | MapStruct 1.6.3 + Lombok |
| API docs | springdoc-openapi 2.8.8 |
| Tests | JUnit 5, Mockito, Testcontainers 1.21.4 (real PostgreSQL, no H2) |

## Getting started

Prerequisites: **JDK 21** and **Docker** (Docker Desktop on Windows/macOS). Maven is not needed; use the wrapper.

```bash
# 1. Start PostgreSQL 16 (database wallet_db, user/password wallet/wallet)
docker compose up -d

# 2. Run the application
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run

# 3. Open Swagger UI
#    http://localhost:8080/swagger-ui.html   (OpenAPI JSON: http://localhost:8080/v3/api-docs)
```

### Database port
The compose file publishes PostgreSQL on host port **5433** by default, because 5432 is often taken by a
locally installed PostgreSQL. The application uses the same default. To use another port, set `DB_PORT` for
**both** commands:

```bash
DB_PORT=5432 docker compose up -d
DB_PORT=5432 ./mvnw spring-boot:run
```

Other connection settings: `DB_HOST` (localhost), `DB_NAME` (wallet_db), `DB_USER` (wallet), `DB_PASSWORD` (wallet).

### Tests
```bash
./mvnw test
```
Docker must be running. Integration tests start their own throw-away PostgreSQL 16 container through Testcontainers
(random port, independent of `docker compose`).

## Project layout (`com.wallet`)

| Package | Contents |
|---|---|
| `config` | OpenAPI configuration |
| `entity` | `User`, `Wallet`, `Category`, `Transaction`, `Tag`, the enums, and `BaseEntity` (id + proxy-safe `equals`/`hashCode`) |
| `repository` | One Spring Data repository per entity |
| `dto.request` / `dto.response` | API contracts (`TransactionResponse`, `TagResponse`, `PageResponse`, ...) |
| `mapper` | MapStruct mappers (`componentModel = "spring"`) |
| `service` | Business logic. Transactions live here (`open-in-view` is off) |
| `controller` | REST controllers under `/api/v1` |
| `exception` | `ApiException` hierarchy, `ErrorResponse`, `GlobalExceptionHandler` |

Database schema: `src/main/resources/db/migration/V1__init_schema.sql`.

## Conventions (both developers)

- Money is always `BigDecimal` with scale 4 (`NUMERIC(19,4)`). Compare with `compareTo`, never with `equals`.
- Entities use `@Getter`/`@Setter`/`@NoArgsConstructor` only. Never `@Data` or `@ToString`. Associations are `LAZY`.
- Every balance change locks the wallet row with `WalletRepository.findByIdForUpdate(id)` inside a `@Transactional`
  service method. When locking two wallets, lock the **smaller id first**.
- Business errors extend `ApiException` and carry their HTTP status. `GlobalExceptionHandler` turns them into
  `ErrorResponse` automatically.
- Mapping to DTOs happens inside the service transaction. Fetch-join associations needed for lists, so there is no N+1.
- Schema changes go in a **new** Flyway file (`V2__...sql`, ...). Never edit `V1` once it is pushed. Agree on the
  version number with your teammate first, so two branches do not both add `V2`.

## For Erdzhan: what is ready and where to plug in

Ready in the shared foundation (Phase 0):

| What | Where |
|---|---|
| Entities and enums | `com.wallet.entity.*` |
| Repositories (`UserRepository`, `CategoryRepository` are empty, so add your query methods) | `com.wallet.repository.*` |
| Wallet row lock for deposit/withdraw | `WalletRepository.findByIdForUpdate(id)` |
| Transaction DTO + mapper (null-safe currency for DEPOSIT/WITHDRAWAL) | `TransactionResponse`, `TransactionMapper` |
| Paginated response wrapper | `PageResponse.from(page)` |
| Exceptions (`ResourceNotFoundException`, `InsufficientFundsException`, `WalletNotActiveException`, `DuplicateResourceException`, ...) | `com.wallet.exception.*` |
| Global error handling | `GlobalExceptionHandler`. **Extend this class; do not add a second `@RestControllerAdvice`** |
| Integration test base (PostgreSQL container, MockMvc, cleanup, fixtures) | `src/test/java/com/wallet/support/AbstractIntegrationTest.java` |

Still to be implemented by Erdzhan (on his own branch):
- `UserController` / `UserService`
- `WalletController` / `WalletService`
- `CategoryController` / `CategoryService`
- Deposit / Withdraw: use `findByIdForUpdate`. Insert a `Transaction` with only `targetWallet` (DEPOSIT) or only
  `sourceWallet` (WITHDRAWAL); the DB enforces this with `chk_transactions_wallets_by_type`.
- Monthly analytics report by category
- Tests for all of the above
