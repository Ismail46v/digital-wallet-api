package com.wallet.service;

import com.wallet.dto.response.TransactionResponse;
import com.wallet.entity.Tag;
import com.wallet.entity.Transaction;
import com.wallet.exception.ResourceNotFoundException;
import com.wallet.mapper.TransactionMapper;
import com.wallet.repository.TagRepository;
import com.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaches and detaches tags on existing transactions. Both operations are idempotent. Because
 * {@code Transaction.tags} is a {@code Set}, each change is a single INSERT or DELETE in {@code transaction_tags}.
 */
@Service
@RequiredArgsConstructor
public class TransactionTagService {

    private final TransactionRepository transactionRepository;
    private final TagRepository tagRepository;
    private final TransactionMapper transactionMapper;

    @Transactional
    public TransactionResponse addTag(Long transactionId, Long tagId) {
        Transaction transaction = getTransaction(transactionId);
        transaction.addTag(getTag(tagId));
        transactionRepository.flush();
        return transactionMapper.toResponse(transaction);
    }

    @Transactional
    public TransactionResponse removeTag(Long transactionId, Long tagId) {
        Transaction transaction = getTransaction(transactionId);
        transaction.removeTag(getTag(tagId));
        transactionRepository.flush();
        return transactionMapper.toResponse(transaction);
    }

    private Transaction getTransaction(Long id) {
        return transactionRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    private Tag getTag(Long id) {
        return tagRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tag", id));
    }
}
