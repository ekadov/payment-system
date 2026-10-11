package ru.transaction.service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Setter
@Getter
@Accessors(chain = true)
public class OutboxEventEntity {

    @Id
    @Column(name = "uid", updatable = false, nullable = false)
    @UuidGenerator
    private UUID uid;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    // Ключ шардирования: событие лежит на том же шарде, что и транзакция.
    @Column(name = "user_uid", nullable = false, updatable = false)
    private UUID userUid;

    @Column(nullable = false, updatable = false, length = 128)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false, length = 128)
    private String messageKey;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "trace_id", updatable = false, length = 32)
    private String traceId;

    @Column(name = "span_id", updatable = false, length = 16)
    private String spanId;
}
