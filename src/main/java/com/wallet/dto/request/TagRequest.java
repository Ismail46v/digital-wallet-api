package com.wallet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The name is normalized by the service ({@code trim().toLowerCase()}). */
public record TagRequest(
        @NotBlank
        @Size(max = 50)
        String name) {
}
