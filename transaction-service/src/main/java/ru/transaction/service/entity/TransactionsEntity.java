package ru.transaction.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Setter
@Getter
@Accessors(chain = true)
public class TransactionsEntity {

    @Id
    @Column(name = "uid", updatable = false, nullable = false)
    @UuidGenerator
    private UUID uid;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column(name = "modified_at")
    @UpdateTimestamp
    private Instant modifiedAt;

    // Ключ шардирования: ShardingSphere запрещает менять его в UPDATE.
    @Column(name = "user_uid", nullable = false, updatable = false)
    private UUID userUid;

    @Column(name = "wallet_uid", nullable = false)
    private UUID walletUid;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, columnDefinition = "payment_type")
    private PaymentTypeEntity type;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 256)
    private String comment;

    @Column(precision = 15, scale = 2)
    private BigDecimal fee;

    @Column(name = "target_wallet_uid")
    private UUID targetWalletUid;

    @Column(name = "payment_method_id")
    private Long paymentMethodId;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;
}
