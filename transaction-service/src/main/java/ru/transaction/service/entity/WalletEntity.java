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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Getter
@Setter
@Accessors(chain = true)
public class WalletEntity {

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

    @Column(name = "wallet_type_uid", nullable = false)
    private UUID walletTypeUid;

    @Column(name = "user_uid", nullable = false, updatable = false)
    private UUID userUid;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
