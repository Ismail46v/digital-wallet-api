package com.wallet.mapper;

import com.wallet.dto.response.TagResponse;
import com.wallet.entity.Tag;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TagMapper {

    TagResponse toResponse(Tag tag);

    List<TagResponse> toResponses(List<Tag> tags);
}
