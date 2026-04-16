package com.shopflow.orders.infrastructure.messaging;

import com.shopflow.orders.infrastructure.persistence.entity.OutboxEventEntity;
import com.shopflow.orders.infrastructure.persistence.JpaOutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox Pattern implementation.
 *
 * Phase 1 — Save to outbox (same DB transaction as the order):
 *   publishOrderCreated() saves to outbox_events table.
 *   If the DB transaction rolls back, the event is NOT saved either.
 *   → Guaranteed consistency between order state and pending events.
 *
 * Phase 2 — Poll and publish (separate transaction):
 *   @Scheduled job reads unpublished events and sends to Kafka.
 *   Only marks as published after Kafka confirms receipt.
 *   → At-least-once delivery (consumers must be idempotent).
 */
@Component
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);
    private static final String TOPIC = "order-events";

    private final JpaOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxEventPublisher(JpaOutboxEventRepository outboxRepository,
                                KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Phase 1: save event to outbox within the same DB transaction.
     * Called from OrderService.createOrder() — same @Transactional boundary.
     */
    @Transactional
    public void saveOrderCreatedEvent(UUID orderId) {
        String payload = """
                {"type":"ORDER_CREATED","orderId":"%s"}
                """.formatted(orderId).trim();

        OutboxEventEntity event = new OutboxEventEntity();
        event.setAggregateId(orderId);
        event.setEventType("ORDER_CREATED");
        event.setPayload(payload);
        outboxRepository.save(event);

        log.debug("Outbox event saved for order: {}", orderId);
    }

    /**
     * Phase 2: poll unpublished events and send to Kafka.
     * Separate transaction — runs independently of order creation.
     *
     * TODO Paso 2 — implementa este método:
     *   1. Lee todos los OutboxEventEntity pendientes: outboxRepository.findUnpublished()
     *   2. Para cada evento, publica a Kafka con:
     *        kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload())
     *                     .get(2, TimeUnit.SECONDS)
     *   3. Solo actualiza published_at si get() no lanza excepción:
     *        event.setPublishedAt(Instant.now()); outboxRepository.save(event);
     *   4. Loguea el resultado de cada publicación con el eventId y el tipo de evento.
     *      Si falla, loguea el error — el evento queda pendiente y se reintentará en el ciclo siguiente.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        // TODO: implementar
    }
}
