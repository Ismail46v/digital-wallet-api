package com.wallet.mapper;

import com.wallet.dto.response.TagResponse;
import com.wallet.dto.response.TransactionResponse;
import com.wallet.entity.Currency;
import com.wallet.entity.Tag;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Shared mapper for all transaction types.
 * <p>
 * Call it inside the service transaction ({@code open-in-view} is disabled). For lists, fetch-join
 * {@code tags}, {@code category} and both wallets beforehand to avoid N+1.
 */
@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(target = "sourceWalletId", source = "sourceWallet.id")
    @Mapping(target = "targetWalletId", source = "targetWallet.id")
    @Mapping(target = "currency", expression = "java(resolveCurrency(transaction))")
    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "tags", source = "tags")
    TransactionResponse toResponse(Transaction transaction);

    /**
     * There is no currency column on transactions. It comes from whichever wallet is present:
     * the source wallet is null for DEPOSIT, the target wallet is null for WITHDRAWAL.
     */
    default Currency resolveCurrency(Transaction transaction) {
        Wallet wallet = transaction.getSourceWallet() != null
                ? transaction.getSourceWallet()
                : transaction.getTargetWallet();
        return wallet != null ? wallet.getCurrency() : null;
    }

    default List<TagResponse> toTagResponses(Set<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .sorted(Comparator.comparing(Tag::getName))
                .map(tag -> new TagResponse(tag.getId(), tag.getName()))
                .toList();
    }
}
