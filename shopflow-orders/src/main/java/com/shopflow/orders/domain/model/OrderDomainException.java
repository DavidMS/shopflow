package com.shopflow.orders.domain.model;

/**
 * Thrown when a domain invariant is violated.
 *
 * Use this instead of generic RuntimeException to make business rule violations
 * explicit and distinguishable from programming errors.
 */
public class OrderDomainException extends RuntimeException {

    public OrderDomainException(String message) {
        super(message);
    }
}
