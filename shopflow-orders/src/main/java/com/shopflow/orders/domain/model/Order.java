package com.shopflow.orders.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order Aggregate Root.
 *
 * Estado inicial del ejercicio T12: solo tiene create() y el constructor compacto.
 * Objetivo: añadir addItem(), confirm(), ship(), deliver() y cancel() para que
 * las invariantes de negocio vivan aquí en lugar de en OrderService.
 */
public record Order(
        OrderId id,
        CustomerId customerId,
        List<OrderItem> items,
        OrderStatus status,
        Money totalAmount,
        String discountCode,
        Instant createdAt
) {

    public Order {
        if (id == null) throw new IllegalArgumentException("Order id cannot be null");
        if (customerId == null) throw new IllegalArgumentException("CustomerId cannot be null");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order must have at least one item");
        }
        if (status == null) throw new IllegalArgumentException("Status cannot be null");
        if (totalAmount == null) throw new IllegalArgumentException("Total amount cannot be null");
        if (createdAt == null) throw new IllegalArgumentException("CreatedAt cannot be null");

        // Defensive copy to ensure immutability
        items = List.copyOf(items);
    }

    public static Order create(CustomerId customerId, List<OrderItem> items, String discountCode) {
        BigDecimal totalAmount = items.stream()
                .map(item -> item.subtotal().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Order(
                OrderId.generate(),
                customerId,
                items,
                OrderStatus.PENDING,
                Money.of(totalAmount),
                discountCode,
                Instant.now()
        );
    }
}
