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
@Table(name = "individuals", schema = "person")
@Setter
@Getter
@Accessors(chain = true)
public class IndividualEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Version
    @Column(nullable = false)
    private long version;

    @Audited(withModifiedFlag = true)
    @Column(name = "passport_number", length = 32)
    private String passportNumber;

    @Audited(withModifiedFlag = true)
    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Audited(withModifiedFlag = true)
    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Audited(withModifiedFlag = true)
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Audited(withModifiedFlag = true)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IndividualStatus status;

}
