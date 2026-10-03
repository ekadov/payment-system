package ru.transaction.service.messaging.event;

/**
 * Событие для отправки в Kafka после коммита транзакции БД.
 */
public record OutgoingEvent(String topic, String key, Object payload) {
}
