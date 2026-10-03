package ru.transaction.service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_types")
@Getter
@Setter
@Accessors(chain = true)
public class WalletTypesEntity {

    @Id
    @UuidGenerator
    @Column(name = "uid", nullable = false, updatable = false)
    private UUID uid;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "modified_at")
    private Instant modifiedAt;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, length = 18)
    private String status;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "user_type", length = 15)
    private String userType;

    @Column(length = 255)
    private String creator;

    @Column(length = 255)
    private String modifier;
}
