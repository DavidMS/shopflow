# Tema 12 — DDD con Copilot

## 🎯 Objetivo

Enriquecer el modelo de dominio de `Order`: mover las invariantes de negocio desde
`OrderService` al propio agregado y añadir métodos semánticos de transición de estado.
El criterio de éxito es que `OrderService` pase de ~80 líneas de validación a menos de 30.

---

## Punto de partida

```bash
git checkout exercise/topic-12
git checkout -b mi-solucion/topic-12
docker-compose up -d postgres
mvn test -pl shopflow-orders
```

---

## El problema: lógica de negocio desplazada

`Order` es un `record` con `create()` pero sin comportamiento de transición de estado.
Toda la lógica que pertenece al dominio vive en `OrderService`:

```java
// OrderService.java — lógica que debería estar en Order
public OrderEntity cancelOrder(UUID id) {
    OrderEntity order = getOrder(id);
    if ("CANCELLED".equals(order.getStatus()) || "DELIVERED".equals(order.getStatus())) {
        throw new IllegalStateException(
            "Cannot cancel order in status: " + order.getStatus()
        );
    }
    order.setStatus(OrderStatus.CANCELLED.name());
    return orderRepository.save(order);
}
```

Otro problema: `OrderService` trabaja directamente con `OrderEntity` (JPA) en lugar de
con el objeto de dominio `Order`. Las transiciones de estado las hace sobre un `String`
(`order.setStatus("CANCELLED")`), sin verificar que la transición es válida.

**Antipatrones del estado inicial:**

| Problema | Dónde |
|---|---|
| Validación de transición de estado en el servicio, no en el agregado | `OrderService.cancelOrder()` |
| `order.setStatus(String)` — sin type safety, acepta cualquier string | `OrderService.java` |
| Regla "no cancelar si DELIVERED" fuera del objeto que la posee | `OrderService.java` |
| `OrderService` trabaja con `OrderEntity` directamente (mezcla capas) | Toda la clase |

---

## Ejercicio — Matar al Servicio Anémico

Usa Chat para enriquecer el modelo paso a paso. Compila entre cada paso.

### Paso 1 — Crea el Value Object `Money` con operaciones

`Money` ya existe como `record` con valor y moneda. Añade las operaciones que necesita:
- `add(Money other)` — suma dos cantidades de la misma moneda
- `multiply(int factor)` — multiplica por un factor entero
- `isZero()` — true si el valor es `BigDecimal.ZERO`

Añade validación en el constructor compacto: valor no negativo, moneda no nula.

```bash
mvn compile -pl shopflow-orders -q
```

### Paso 2 — Mueve la invariante "no añadir ítems a pedidos enviados"

En `OrderService` hay una comprobación (o debería haberla) que impide añadir ítems
a un pedido que ya fue enviado. Mueve esa regla a `Order.addItem(OrderItem item)`:

```java
// Order.java — el método devuelve un nuevo Order (record es inmutable)
public Order addItem(OrderItem item) {
    if (this.status != OrderStatus.PENDING) {
        throw new OrderDomainException("Cannot add items to an order in status: " + this.status);
    }
    // ...
}
```

### Paso 3 — Añade métodos semánticos de transición de estado

Reemplaza la lógica de `cancelOrder()` y otros métodos de `OrderService` con métodos
en `Order` que encapsulen las transiciones válidas:

- `confirm()` → solo desde `PENDING`; devuelve `Order` con status `CONFIRMED`
- `ship(TrackingNumber trackingNumber)` → solo desde `CONFIRMED`; devuelve `Order` con status `SHIPPED`
- `deliver()` → solo desde `SHIPPED`; devuelve `Order` con status `DELIVERED`
- `cancel()` → solo desde `PENDING` o `CONFIRMED`; devuelve `Order` con status `CANCELLED`

Cada método lanza `OrderDomainException` si la transición no es válida.

```bash
mvn compile -pl shopflow-orders -q
```

### Paso 4 — Genera los tests unitarios de cada transición

Pide al agente que genere un test unitario completo para `Order` cubriendo:
- Happy path de cada transición válida
- Excepción en cada transición inválida (ej: `deliver()` desde `PENDING`)

```bash
mvn test -pl shopflow-orders
```

---

## Tabla comparativa antes/después

Completa esta tabla antes de hacer el commit final:

| Métrica | Estado inicial | Tu solución |
|---|---|---|
| Líneas en `OrderService` | ~80 | ¿? |
| Lógica de transición en dominio | 0 métodos | ¿? métodos semánticos |
| Tests de transiciones | Ninguno | ¿? tests |
| `setStatus(String)` | Sí | No |

---

## Ejercicio Plugin — Construye `spring-aggregate-designer` y `/extract-value-object`

> Este ejercicio es adicional y complementa el ejemplo guiado del tema.

### Parte A — Subagente `spring-aggregate-designer`

Crea `.claude/agents/spring-aggregate-designer.md`. Actúa en fase de diseño: dado un
conjunto de clases y casos de uso, propone el límite del agregado, la raíz, las invariantes
a proteger dentro de la frontera y los Value Objects candidatos. También señala si la
frontera propuesta es demasiado grande o pequeña.

### Parte B — Skill `/extract-value-object`

Crea `.claude/commands/extract-value-object.md`. Extrae un campo concreto de una clase
como `record` Java 21 de forma segura: analiza todos los usos, crea el `record` con
validación en el constructor compacto, sustituye el tipo primitivo con compilación entre
cada paso, y ejecuta los tests al terminar.

**Verificación:**
```bash
# Usa spring-aggregate-designer con Order.java, OrderItem.java y los casos de uso del tema
# Debe proponer:
# - Agregado: Order + OrderItem (Customer solo por CustomerId)
# - VOs candidatos: status (→ enum con transiciones), discountCode (→ DiscountCode record)

# Extrae discountCode como Value Object
/extract-value-object Order.discountCode
# Debe generar DiscountCode record con validación de formato
# y sustituir el campo String en Order y clases relacionadas
```

Documenta en el commit:
- ¿Qué invariante identificó `spring-aggregate-designer` que no estaba encapsulada?
- ¿En qué paso de `/extract-value-object` fue más crítico compilar antes de continuar?

---

## Criterios de éxito ✅

- `Order.cancel()`, `Order.confirm()`, `Order.ship()`, `Order.deliver()` implementados ✅
- Cada método lanza `OrderDomainException` para transiciones inválidas ✅
- `OrderService.cancelOrder()` delega a `order.cancel()` sin lógica propia ✅
- `OrderService` tiene menos de 30 líneas (excluyendo mappers) ✅
- Tests unitarios de transiciones de estado en verde ✅
- `mvn test -pl shopflow-orders` en verde ✅

---

## Solución de referencia

```bash
git checkout v12-ddd
```

Contiene `Order` enriquecido con métodos semánticos y `OrderService` reducido.
Consúltala solo después de completar el ejercicio.
