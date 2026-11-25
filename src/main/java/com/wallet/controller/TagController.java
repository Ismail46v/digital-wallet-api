package com.wallet.controller;

import com.wallet.dto.request.TagRequest;
import com.wallet.dto.response.TagResponse;
import com.wallet.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Tag(name = "Tags", description = "Tag CRUD. Names are normalized with trim + lower case and must be unique.")
@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @Operation(summary = "Create a tag")
    @PostMapping
    public ResponseEntity<TagResponse> create(@Valid @RequestBody TagRequest request) {
        TagResponse created = tagService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "List all tags, sorted by name")
    @GetMapping
    public List<TagResponse> findAll() {
        return tagService.findAll();
    }

    @Operation(summary = "Get a tag by id")
    @GetMapping("/{id}")
    public TagResponse findById(@PathVariable Long id) {
        return tagService.findById(id);
    }

    @Operation(summary = "Rename a tag")
    @PutMapping("/{id}")
    public TagResponse update(@PathVariable Long id, @Valid @RequestBody TagRequest request) {
        return tagService.update(id, request);
    }

    @Operation(summary = "Delete a tag (also detaches it from all transactions)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tagService.delete(id);
    }
}
