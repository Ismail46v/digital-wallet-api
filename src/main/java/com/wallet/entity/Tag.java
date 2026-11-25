package com.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tags are linked to transactions through {@code transaction_tags}, which only {@link Transaction} maps.
 * Tag has no inverse collection on purpose. Deleting a tag is cleaned up by
 * {@code ON DELETE CASCADE} on {@code transaction_tags.tag_id}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tags")
public class Tag extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;

    public Tag(String name) {
        this.name = name;
    }
}
