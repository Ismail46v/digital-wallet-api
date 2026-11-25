package com.wallet.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

public record TransferRequest(
        @NotNull
        Long fromWalletId,

        @NotNull
        Long toWalletId,

        @NotNull
        @DecimalMin(value = "0.0001")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        Long categoryId,

        Set<@NotNull Long> tagIds,

        @Size(max = 255)
        String description) {
}
