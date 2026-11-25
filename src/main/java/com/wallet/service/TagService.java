package com.wallet.service;

import com.wallet.dto.request.TagRequest;
import com.wallet.dto.response.TagResponse;
import com.wallet.entity.Tag;
import com.wallet.exception.DuplicateResourceException;
import com.wallet.exception.InvalidRequestException;
import com.wallet.exception.ResourceNotFoundException;
import com.wallet.mapper.TagMapper;
import com.wallet.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;
    private final TagMapper tagMapper;

    @Transactional
    public TagResponse create(TagRequest request) {
        String name = normalize(request.name());
        if (tagRepository.existsByName(name)) {
            throw duplicate(name, null);
        }
        return tagMapper.toResponse(saveAndFlush(new Tag(name)));
    }

    public List<TagResponse> findAll() {
        return tagMapper.toResponses(tagRepository.findAll(Sort.by("name")));
    }

    public TagResponse findById(Long id) {
        return tagMapper.toResponse(getTag(id));
    }

    @Transactional
    public TagResponse update(Long id, TagRequest request) {
        Tag tag = getTag(id);
        String name = normalize(request.name());
        if (!name.equals(tag.getName()) && tagRepository.existsByNameAndIdNot(name, id)) {
            throw duplicate(name, null);
        }
        tag.setName(name);
        return tagMapper.toResponse(saveAndFlush(tag));
    }

    /**
     * Rows in {@code transaction_tags} that point to this tag are removed by the database
     * ({@code ON DELETE CASCADE}), so deleting a tag that is in use does not fail with a FK error.
     */
    @Transactional
    public void delete(Long id) {
        tagRepository.delete(getTag(id));
    }

    static String normalize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidRequestException("Tag name must not be blank");
        }
        return rawName.trim().toLowerCase(Locale.ROOT);
    }

    private Tag getTag(Long id) {
        return tagRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tag", id));
    }

    /** Flushes immediately, so a concurrent insert of the same name hits UNIQUE here and is reported as 409. */
    private Tag saveAndFlush(Tag tag) {
        try {
            return tagRepository.saveAndFlush(tag);
        } catch (DataIntegrityViolationException ex) {
            throw duplicate(tag.getName(), ex);
        }
    }

    private static DuplicateResourceException duplicate(String name, Throwable cause) {
        String message = "Tag '%s' already exists".formatted(name);
        return cause == null ? new DuplicateResourceException(message) : new DuplicateResourceException(message, cause);
    }
}
