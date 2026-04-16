package com.shopflow.orders.domain.port.in;

import com.shopflow.orders.domain.model.Order;

import java.util.List;
import java.util.UUID;

/**
 * Input port: defines how the outside world queries orders.
 *
 * Pure Java interface — no Spring types. The REST adapter calls this;
 * the application service implements it.
 */
public interface GetOrderUseCase {

    Order getById(UUID id);

    List<Order> list(String status);
}
