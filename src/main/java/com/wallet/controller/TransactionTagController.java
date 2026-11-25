package com.wallet.controller;

import com.wallet.dto.response.TransactionResponse;
import com.wallet.service.TransactionTagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transaction tags", description = "Attach and detach tags on existing transactions (idempotent)")
@RestController
@RequestMapping("/api/v1/transactions/{transactionId}/tags")
@RequiredArgsConstructor
public class TransactionTagController {

    private final TransactionTagService transactionTagService;

    @Operation(summary = "Attach a tag to a transaction")
    @PostMapping("/{tagId}")
    public TransactionResponse addTag(@PathVariable Long transactionId, @PathVariable Long tagId) {
        return transactionTagService.addTag(transactionId, tagId);
    }

    @Operation(summary = "Detach a tag from a transaction")
    @DeleteMapping("/{tagId}")
    public TransactionResponse removeTag(@PathVariable Long transactionId, @PathVariable Long tagId) {
        return transactionTagService.removeTag(transactionId, tagId);
    }
}
