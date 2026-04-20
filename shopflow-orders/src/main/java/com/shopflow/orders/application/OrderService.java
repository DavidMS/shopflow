package com.shopflow.orders.application;

import com.shopflow.orders.application.command.CreateOrderCommand;
import com.shopflow.orders.domain.model.*;
import com.shopflow.orders.infrastructure.messaging.NaiveEventPublisher;
import com.shopflow.orders.infrastructure.persistence.JpaOrderRepository;
import com.shopflow.orders.infrastructure.persistence.entity.OrderEntity;
import com.shopflow.orders.infrastructure.persistence.entity.OrderItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Application service for the Order bounded context.
 */
@Service
@Transactional
public class OrderService {

    private final JpaOrderRepository orderRepository;
    private final NaiveEventPublisher eventPublisher;

    public OrderService(JpaOrderRepository orderRepository, NaiveEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    public OrderEntity createOrder(CreateOrderCommand command) {
        List<OrderItemEntity> itemEntities = command.items().stream()
                .map(item -> {
                    OrderItemEntity entity = new OrderItemEntity();
                    entity.setProductId(UUID.fromString(item.productId()));
                    entity.setQuantity(item.quantity());
                    entity.setUnitPrice(new BigDecimal(item.unitPrice()));
                    return entity;
                })
                .toList();

        BigDecimal total = itemEntities.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        OrderEntity order = new OrderEntity();
        order.setCustomerId(UUID.fromString(command.customerId()));
        order.setStatus(OrderStatus.PENDING.name());
        order.setTotalAmount(total);
        order.setDiscountCode(command.discountCode());
        itemEntities.forEach(item -> {
            item.setOrder(order);
            order.getItems().add(item);
        });

        OrderEntity saved = orderRepository.save(order);
        eventPublisher.publishOrderCreated(saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<OrderEntity> listOrders(String status, Pageable pageable) {
        if (status != null) {
            return orderRepository.findByStatus(status, pageable);
        }
        return orderRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public OrderEntity getOrder(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

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

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID id) {
            super("Order not found: " + id);
        }
    }
}
