# Tema 13 — Event-Driven Architecture con IA

## 🎯 Objetivo

Transformar un sistema de mensajería frágil en uno apto para producción.
`NaiveEventPublisher` tiene el bug del dual-write; `NaiveOrderEventConsumer` no tiene
retry ni DLQ. Los dos deben ser reemplazados por implementaciones con garantías reales.

---

## Punto de partida

```bash
git checkout exercise/topic-13
git checkout -b mi-solucion/topic-13
docker-compose up -d   # levanta Kafka + Postgres
mvn test -pl shopflow-orders   # debe pasar en verde
```

---

## El problema: dual-write sin garantías

### Productor frágil — `NaiveEventPublisher`

`OrderService.createOrder()` llama a `NaiveEventPublisher.publishOrderCreated()` dentro
de la misma `@Transactional`. El bug: `KafkaTemplate.send()` **no es parte de la
transacción de base de datos**. Hay escenarios en que la BD confirma y Kafka no (o
al revés), dejando el sistema en un estado inconsistente.

```java
// NaiveEventPublisher.java — el bug
@Transactional
public void publishOrderCreated(UUID orderId) {
    kafkaTemplate.send("order-events", orderId.toString(), event);
    // Si Kafka está caído: la transacción de BD hace rollback... pero el orden
    // ya había sido guardado antes de llegar aquí si el commit ocurrió.
    // Si Kafka confirma pero una excepción posterior hace rollback en BD:
    // el evento salió pero la orden no existe → consumidor procesa una orden fantasma.
}
```

**Observa el bug antes de modificar nada:**

```bash
# Crea una orden con Kafka caído
docker stop shopflow-kafka
curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"a0000000-0000-0000-0000-000000000001","items":[{"productId":"b0000000-0000-0000-0000-000000000001","quantity":1,"unitPrice":"29.99"}]}'

# La llamada falla con error de conexión a Kafka — la orden NO se guardó
# (si el send() lanza antes del commit, la transacción hace rollback)

# Levanta Kafka y comprueba que la orden no existe en la BD:
docker start shopflow-kafka
docker exec -it shopflow-postgres psql -U shopflow -c "SELECT id, status FROM orders ORDER BY created_at DESC LIMIT 5;"
```

### Consumidor frágil — `NaiveOrderEventConsumer`

```java
// NaiveOrderEventConsumer.java — sin protección
@KafkaListener(topics = "order-events", groupId = "notifications-group")
public void onOrderEvent(String message) {
    sendEmail(message);
    // Si sendEmail() lanza excepción: auto-commit ya confirmó el offset
    // → el mensaje se pierde silenciosamente
    // Sin retry, sin DLQ, sin idempotencia, sin contexto en los logs
}
```

---

## Ejercicio 1 — Outbox Pattern: atomicidad garantizada

Reemplaza `NaiveEventPublisher` con un publicador que use el patrón Outbox.
El evento se guarda en la misma transacción de BD que la entidad de negocio.
Un job `@Scheduled` lo publica a Kafka por separado.

### Paso 1 — Verifica la infraestructura existente

La entidad JPA `OutboxEventEntity` ya existe en `infrastructure/persistence/entity/`.
El repositorio `JpaOutboxEventRepository` con `findUnpublished()` también está creado.
Verifica que `db/schema.sql` tiene la tabla `outbox_events` con columna `published_at`
(`null` = pendiente de publicar).

### Paso 2 — Implementa el relay job

La clase `OutboxEventPublisher` en `infrastructure/messaging/` ya tiene el esqueleto.
Tu misión es implementar el método `publishPendingEvents()` marcado con `// TODO`:

1. Lee todos los `OutboxEventEntity` pendientes: `outboxRepository.findUnpublished()`.
2. Para cada uno, publica con `kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), event.getPayload()).get(2, TimeUnit.SECONDS)`.
3. Solo actualiza `published_at` si el `get()` no lanza excepción: `event.setPublishedAt(Instant.now())`.
4. Loguea el resultado de cada publicación con el `aggregateId` y el tipo de evento.
   Si falla, loguea el error — el evento queda pendiente y el job lo reintentará en 5 segundos.

### Paso 3 — Integra con el servicio de aplicación

Modifica `OrderService.createOrder()` para que use `OutboxEventPublisher.saveOrderCreatedEvent()`
en lugar de `NaiveEventPublisher.publishOrderCreated()`. El método `saveOrderCreatedEvent()`
debe ejecutarse **dentro de la misma `@Transactional`** que guarda la orden.

```bash
# Verifica que el Outbox funciona deteniendo Kafka
docker stop shopflow-kafka

# Crea una orden → la BD confirma, OutboxEvent queda con published_at = null
curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"a0000000-0000-0000-0000-000000000001","items":[{"productId":"b0000000-0000-0000-0000-000000000001","quantity":1,"unitPrice":"29.99"}]}'

# Verifica que la orden Y el evento outbox existen en BD:
docker exec -it shopflow-postgres psql -U shopflow \
  -c "SELECT id, status FROM orders ORDER BY created_at DESC LIMIT 1;" \
  -c "SELECT aggregate_id, event_type, published_at FROM outbox_events ORDER BY created_at DESC LIMIT 1;"

# Levanta Kafka → el relay publica el evento en máximo 5 segundos
docker start shopflow-kafka

# Verifica que published_at se rellena:
docker exec -it shopflow-postgres psql -U shopflow \
  -c "SELECT event_id, published_at FROM outbox_events ORDER BY created_at DESC LIMIT 1;"
```

---

## Ejercicio 2 — Consumidor Indestructible

Transforma `NaiveOrderEventConsumer` en `shopflow-notifications` añadiendo las
cuatro garantías de producción.

### Garantía 1 — DefaultErrorHandler con DLQ

Crea `KafkaConsumerConfig` en `shopflow-notifications` con:
- `DefaultErrorHandler` con `ExponentialBackOffWithMaxRetries(3)`: backoff inicial 1s, multiplicador 2
- `DeadLetterPublishingRecoverer` al tópico `order-events.DLT`
- `JsonProcessingException` en la lista de excepciones no reintentables

### Garantía 2 — ACK manual

Cambia el consumer a ACK manual: el offset solo se confirma si el procesamiento fue exitoso.
Configura `AckMode.MANUAL_IMMEDIATE` en el container factory.
Elimina `spring.kafka.consumer.enable-auto-commit=true` del `application.properties`.

### Garantía 3 — Idempotencia

Crea la tabla `processed_events` (`event_id VARCHAR PRIMARY KEY, processed_at TIMESTAMP`).
Antes de procesar cualquier mensaje, verifica que `eventId` no existe en esa tabla.
Si existe, confirma el ACK y retorna sin procesar.

### Garantía 4 — Logging estructurado

Añade al consumer:
- `MDC.put("eventId", eventId)` y `MDC.put("orderId", ...)` al inicio
- `MDC.clear()` en bloque `finally`
- Log de inicio: `Processing event type=... orderId=...`
- Log de fin: `Completed event orderId=...`

```bash
# Publica un payload malformado y verifica que va al DLT
docker exec -it shopflow-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic order-events
> {"broken":"json

# Verifica que aparece en el DLT (no bloquea la partición):
docker exec -it shopflow-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic order-events.DLT --from-beginning
```

---

## Ejercicio Plugin — Construye `spring-outbox-monitor` y `/audit-events`

> Este ejercicio es adicional y complementa el ejemplo guiado del tema.

### Parte A — Subagente `spring-outbox-monitor`

Crea `.claude/agents/spring-outbox-monitor.md`. Analiza el estado operacional de
los canales EDA del proyecto: qué tópicos tienen productor pero no consumidor (eventos
huérfanos), qué tópicos no tienen DLT configurado, y cuáles usan publicación directa
sin Outbox.

Formato de respuesta: tabla por tópico con columnas `Tópico | Productor | Consumidor | DLT | Outbox`.
Señala los eventos huérfanos con 🔴 y los tópicos sin DLT con 🟡.

### Parte B — Skill `/audit-events`

Crea `.claude/commands/audit-events.md`. Escanea el proyecto en busca de todos los
canales EDA (`@KafkaListener`, `kafkaTemplate.send()`, tópicos en `application.properties`)
e invoca `spring-outbox-monitor` para generar un cuadro de mando con:
- Tabla de todos los tópicos y sus garantías (Outbox ✅/🔴, DLT ✅/🔴, Idempotencia ✅/🔴)
- Score global: `[tópicos con todas las garantías] / [total]`
- Opción de generar correcciones para los hallazgos críticos

**Verificación:**
```bash
/audit-events
# En exercise/topic-13:
# - order-events: sin Outbox 🔴, sin DLT 🟡 → score ~0%
# Después de tu solución:
# - order-events: Outbox ✅, DLT ✅, idempotencia ✅ → score 100%
```

Documenta en el commit:
- ¿Qué canal apareció como huérfano que no esperabas?
- ¿Cómo cambió el score de `/audit-events` entre el estado inicial y tu solución?

---

## Criterios de éxito ✅

- `mvn test -pl shopflow-orders` en verde ✅
- Orden creada con Kafka detenido: evento queda en `outbox_events` con `published_at = null` ✅
- Kafka levantado de nuevo: relay publica el evento en máximo 5 segundos ✅
- Payload malformado: va al tópico `order-events.DLT` después de 3 reintentos ✅
- Mensaje duplicado (mismo `eventId`): se ignora sin reprocesar ✅
- Logs del consumer incluyen `eventId` y `orderId` en cada línea ✅

---

## Solución de referencia

```bash
git checkout v13-eda
```

Contiene el Outbox completo y el consumidor con las cuatro garantías.
Consúltala solo después de completar el ejercicio.
