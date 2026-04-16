package com.shopflow.orders.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Value Object representing a monetary amount with currency.
 *
 * Currently holds value + currency but lacks arithmetic operations.
 * Step 1 of the T12 exercise: add add(), multiply(), isZero()
 * and harden the constructor with non-negative validation.
 */
public record Money(BigDecimal amount, Currency currency) {

    private static final Currency EUR = Currency.getInstance("EUR");

    public Money {
        if (amount == null) throw new IllegalArgumentException("Amount cannot be null");
        if (currency == null) throw new IllegalArgumentException("Currency cannot be null");
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount, EUR);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount), EUR);
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO, EUR);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
