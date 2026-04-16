package com.shopflow.orders.domain.model;

/**
 * Lifecycle states of an Order.
 * Valid transitions (to implement in Order): PENDING → CONFIRMED → SHIPPED → DELIVERED
 *                                            PENDING → CANCELLED
 *                                            CONFIRMED → CANCELLED
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
