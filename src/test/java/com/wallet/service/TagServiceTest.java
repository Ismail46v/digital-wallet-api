package com.wallet.service;

import com.wallet.dto.request.TagRequest;
import com.wallet.dto.response.TagResponse;
import com.wallet.entity.Tag;
import com.wallet.exception.DuplicateResourceException;
import com.wallet.exception.ResourceNotFoundException;
import com.wallet.mapper.TagMapper;
import com.wallet.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock
    private TagRepository tagRepository;
    @Spy
    private TagMapper tagMapper = Mappers.getMapper(TagMapper.class);

    @InjectMocks
    private TagService tagService;

    // ------------------------------------------------------------------ create

    @Test
    void createNormalizesTheName() {
        when(tagRepository.existsByName("groceries")).thenReturn(false);
        when(tagRepository.saveAndFlush(any(Tag.class))).thenAnswer(inv -> withId(inv.getArgument(0), 1L));

        TagResponse response = tagService.create(new TagRequest("  GroCeries \t"));

        ArgumentCaptor<Tag> saved = ArgumentCaptor.forClass(Tag.class);
        verify(tagRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("groceries");
        assertThat(response).isEqualTo(new TagResponse(1L, "groceries"));
    }

    @Test
    void createRejectsDuplicateNameAfterNormalization() {
        when(tagRepository.existsByName("food")).thenReturn(true);

        assertThatThrownBy(() -> tagService.create(new TagRequest(" FOOD ")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("food");

        verify(tagRepository, never()).saveAndFlush(any());
    }

    @Test
    void createTranslatesUniqueRaceIntoDuplicateResource() {
        when(tagRepository.existsByName("food")).thenReturn(false);
        when(tagRepository.saveAndFlush(any(Tag.class))).thenThrow(new DataIntegrityViolationException("uq_tags_name"));

        assertThatThrownBy(() -> tagService.create(new TagRequest("food")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ------------------------------------------------------------------ read

    @Test
    void findAllReturnsTagsSortedByName() {
        when(tagRepository.findAll(Sort.by("name")))
                .thenReturn(List.of(withId(new Tag("bills"), 2L), withId(new Tag("food"), 1L)));

        assertThat(tagService.findAll())
                .containsExactly(new TagResponse(2L, "bills"), new TagResponse(1L, "food"));
    }

    @Test
    void findByIdReturnsTag() {
        when(tagRepository.findById(5L)).thenReturn(Optional.of(withId(new Tag("travel"), 5L)));

        assertThat(tagService.findById(5L)).isEqualTo(new TagResponse(5L, "travel"));
    }

    @Test
    void findByIdThrowsNotFound() {
        when(tagRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tagService.findById(5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateRenamesWithNormalization() {
        Tag tag = withId(new Tag("old"), 3L);
        when(tagRepository.findById(3L)).thenReturn(Optional.of(tag));
        when(tagRepository.existsByNameAndIdNot("new name", 3L)).thenReturn(false);
        when(tagRepository.saveAndFlush(tag)).thenReturn(tag);

        TagResponse response = tagService.update(3L, new TagRequest(" New Name "));

        assertThat(tag.getName()).isEqualTo("new name");
        assertThat(response).isEqualTo(new TagResponse(3L, "new name"));
    }

    @Test
    void updateToTheSameNormalizedNameIsAllowed() {
        Tag tag = withId(new Tag("food"), 3L);
        when(tagRepository.findById(3L)).thenReturn(Optional.of(tag));
        when(tagRepository.saveAndFlush(tag)).thenReturn(tag);

        assertThat(tagService.update(3L, new TagRequest("FOOD"))).isEqualTo(new TagResponse(3L, "food"));
        verify(tagRepository, never()).existsByNameAndIdNot(anyString(), anyLong());
    }

    @Test
    void updateRejectsNameOfAnotherTag() {
        Tag tag = withId(new Tag("old"), 3L);
        when(tagRepository.findById(3L)).thenReturn(Optional.of(tag));
        when(tagRepository.existsByNameAndIdNot("food", 3L)).thenReturn(true);

        assertThatThrownBy(() -> tagService.update(3L, new TagRequest("Food")))
                .isInstanceOf(DuplicateResourceException.class);

        assertThat(tag.getName()).isEqualTo("old");
        verify(tagRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateThrowsNotFound() {
        when(tagRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tagService.update(3L, new TagRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteRemovesExistingTag() {
        Tag tag = withId(new Tag("food"), 4L);
        when(tagRepository.findById(4L)).thenReturn(Optional.of(tag));

        tagService.delete(4L);

        verify(tagRepository).delete(tag);
    }

    @Test
    void deleteThrowsNotFound() {
        when(tagRepository.findById(4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tagService.delete(4L)).isInstanceOf(ResourceNotFoundException.class);
        verify(tagRepository, never()).delete(any());
    }

    private static Tag withId(Tag tag, long id) {
        tag.setId(id);
        return tag;
    }
}
