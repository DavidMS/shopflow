# Tema 13 — Event-Driven Architecture con Copilot

## 🎯 Objetivo

Transformar un sistema de mensajería frágil en uno apto para producción.
`NaiveEventPublisher` tiene el bug del dual-write; `NaiveOrderEventConsumer` no tiene
retry ni DLQ. Los dos deben ser reemplazados por implementaciones con garantías reales.

---

## Punto de partida

```bash
git checkout exercise/topic-13
git checkout -b mi-solucion/topic-13
docker-compose up -d   # levanta Kafka + Zookeeper + Postgres
mvn test -pl shopflow-orders   # debe pasar el EnvironmentSanityCheck
```

---

## El problema: dual-write sin garantías

### Productor frágil — `NaiveEventPublisher`

```java
// NaiveEventPublisher.java — el bug
@Transactional
public void publishOrderCreated(UUID orderId) {
    orderRepository.save(order);                       // ① persiste en DB
    kafkaTemplate.send("order-events", payload);      // ② envía a Kafka
    // Si Kafka falla en ②: la transacción hace rollback en DB
    // Si DB confirma en ① y Kafka falla después: la orden existe pero el evento se pierde
}
```

El bug del dual-write: DB y Kafka no son parte de la misma transacción.
Hay escenarios en los que uno confirma y el otro no.

### Consumidor frágil — `NaiveOrderEventConsumer`

```java
// NaiveOrderEventConsumer.java — sin protección
@KafkaListener(topics = "order-events", groupId = "notifications-group")
public void onOrderEvent(String message) {
    sendEmail(message);
    // Si sendEmail() lanza excepción: el mensaje se pierde (auto-commit activado)
    // Si el servicio se reinicia después del auto-commit: el mensaje no se reprocesal
    // Sin retry, sin DLQ, sin idempotencia
}
```

**Observa los problemas antes de modificar nada:**

```bash
# Detén el contenedor de Kafka mientras se procesa una orden
docker stop <kafka-container>
# Crea una orden → la BD la guarda, el evento nunca sale
# Levanta Kafka de nuevo → el evento no llega, el cliente no recibe notificación
docker start <kafka-container>

# Fuerza un error en sendEmail publicando un payload malformado
# El mensaje se pierde silenciosamente
```

---

## Ejercicio 1 — Outbox Pattern: atomicidad garantizada

Reemplaza `NaiveEventPublisher` con un publicador que use el patrón Outbox.
El evento se guarda en la misma transacción de DB que la entidad de negocio.
Un job `@Scheduled` lo publica a Kafka por separado.

### Paso 1 — Crea la tabla y entidad Outbox

La entidad JPA `OutboxEventEntity` ya existe en `infrastructure/persistence/entity/`.
El repositorio `JpaOutboxEventRepository` también está creado.

Verifica el schema: `db/schema.sql` debe tener la tabla `outbox_events` con los campos
`event_id`, `topic`, `event_type`, `payload`, `created_at`, `published_at`.

### Paso 2 — Implementa el relay job

La clase `OutboxEventPublisher` en `infrastructure/messaging/` ya tiene el esqueleto.
Tu misión es implementar el método `relay()`:

1. Lee todos los `OutboxEventEntity` con `published_at IS NULL`.
2. Para cada uno, publica con `kafkaTemplate.send(topic, eventId, payload).get(2, SECONDS)`.
3. Solo actualiza `published_at` si el `get()` no lanza excepción.
4. Loguea el resultado de cada publicación con el `eventId` y el tópico.

### Paso 3 — Integra con el servicio de aplicación

Modifica `OrderService.createOrder()` para que persista el evento en outbox **dentro
de la misma `@Transactional`** que guarda la orden.

```bash
# Verifica que el Outbox funciona deteniendo Kafka
docker stop <kafka-container>
# Crea una orden → BD confirma, OutboxEvent queda con published_at = null
# Verifica en la BD:
docker exec -it <postgres-container> psql -U shopflow -c "SELECT * FROM outbox_events;"

# Levanta Kafka
docker start <kafka-container>
# Espera 5 segundos → el relay publica el evento pendiente
# Verifica que published_at se rellena:
docker exec -it <postgres-container> psql -U shopflow -c "SELECT event_id, published_at FROM outbox_events;"
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
docker exec -it <kafka-container> kafka-console-producer.sh \
  --broker-list localhost:9092 --topic order-events
> {"broken":"json

# Verifica que aparece en el DLT (no bloquea la partición)
docker exec -it <kafka-container> kafka-console-consumer.sh \
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

Formato de respuesta: tabla por tópico con columnas `Productor | Consumidor | DLT | Outbox`.
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

- Orden creada con Kafka detenido: evento queda en `outbox_events` con `published_at = null` ✅
- Kafka levantado de nuevo: relay publica el evento en máximo 5 segundos ✅
- Payload malformado: va al tópico `order-events.DLT` después de 3 reintentos ✅
- Mensaje duplicado (mismo `eventId`): se ignora sin reprocesar ✅
- Logs del consumer incluyen `eventId` y `orderId` en cada línea ✅
- `mvn test -pl shopflow-orders` y `mvn test -pl shopflow-notifications` en verde ✅

---

## Solución de referencia

```bash
git checkout v13-eda
```

Contiene el Outbox completo y el consumidor con las cuatro garantías.
Consúltala solo después de completar el ejercicio.
