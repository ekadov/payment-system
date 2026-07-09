package ru.person.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "addresses", schema = "person")
@Getter
@Setter
@Accessors(chain = true)
public class AddressEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant created;

    @Column(nullable = false)
    private Instant updated;

    @Version
    @Column(nullable = false)
    private long version;

    // CountryEntity — неаудируемый справочник, поэтому связь помечаем NOT_AUDITED,
    // иначе Envers откажется строить метамодель.
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private CountryEntity country;

    @Column(name = "address", nullable = false, length = 128)
    private String addressLine;

    @Column(name = "zip_code", length = 32)
    private String zipCode;

    @Column
    private Instant archived;

    @Column(nullable = false, length = 64)
    private String city;

    @Column(length = 64)
    private String state;

}
