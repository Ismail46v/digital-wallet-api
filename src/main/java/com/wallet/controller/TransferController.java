package com.wallet.controller;

import com.wallet.dto.request.TransferRequest;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfers", description = "Atomic wallet-to-wallet transfers")
@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransferService transferService;

    @Operation(summary = "Transfer money between two wallets of the same currency",
            description = "Repeating a request with the same Idempotency-Key returns 409 and never moves money twice.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse transfer(
            @Parameter(description = "Unique client-generated key (e.g. a UUID), max 100 characters", required = true)
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return transferService.transfer(request, idempotencyKey);
    }
}
