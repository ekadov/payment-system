package ru.transaction.service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.transaction.service.entity.OutboxEventEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventsRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Готовые к отправке события под блокировкой: второй экземпляр сервиса ждёт конца транзакции первого,
     * после чего PostgreSQL перепроверяет условие, и уже отправленные события в выборку не попадают.
     */
    @Query(value = """
            select * from {h-schema}outbox_events
            where status = 'NEW' and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update""", nativeQuery = true)
    List<OutboxEventEntity> findReadyForUpdate(Instant now, int limit);

    List<OutboxEventEntity> findByMessageKey(String messageKey);
}
