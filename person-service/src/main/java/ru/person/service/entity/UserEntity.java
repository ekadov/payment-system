package ru.person.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "users", schema = "person")
@Getter
@Setter
@Accessors(chain = true)
public class UserEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "secret_key", length = 64)
    private String secretKey;

    @Audited(withModifiedFlag = true)
    @Column(nullable = false, length = 1024)
    private String email;

    @Column(nullable = false, updatable = false)
    private Instant created;

    @Column(nullable = false)
    private Instant updated;

    @Version
    @Column(nullable = false)
    private long version;

    @Audited(withModifiedFlag = true)
    @Column(name = "first_name", nullable = false, length = 64)
    private String firstName;

    @Audited(withModifiedFlag = true)
    @Column(name = "last_name", nullable = false, length = 64)
    private String lastName;

    @Audited(withModifiedFlag = true)
    @Column(nullable = false)
    private boolean filled;

    @Audited(withModifiedFlag = true, modifiedColumnName = "address_mod")
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true, optional = false)
    @JoinColumn(name = "address_id", nullable = false)
    private AddressEntity address;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, optional = false)
    private IndividualEntity individual;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        created = now;
        updated = now;
    }

    @PreUpdate
    public void preUpdate() {
        updated = Instant.now();
    }

}
