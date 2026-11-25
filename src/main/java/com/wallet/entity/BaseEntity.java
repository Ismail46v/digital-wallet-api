package com.wallet.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.proxy.HibernateProxy;

import java.util.Objects;

/**
 * Common identifier plus Hibernate-safe {@code equals}/{@code hashCode}.
 * <ul>
 *     <li>Equality is by database id only. Two transient (id == null) instances are equal only if identical.</li>
 *     <li>The real class is resolved through {@link HibernateProxy} without initializing the proxy,
 *     and no lazy association or collection is touched.</li>
 *     <li>{@code hashCode} is constant per entity class, so it does not change when the id is assigned on persist.
 *     This keeps entities safe inside {@code HashSet}s.</li>
 * </ul>
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (effectiveClass(this) != effectiveClass(o)) {
            return false;
        }
        BaseEntity other = (BaseEntity) o;
        return getId() != null && Objects.equals(getId(), other.getId());
    }

    @Override
    public final int hashCode() {
        return effectiveClass(this).hashCode();
    }

    private static Class<?> effectiveClass(Object o) {
        return o instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();
    }
}
