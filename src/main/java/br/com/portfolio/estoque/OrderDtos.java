package br.com.portfolio.estoque;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

final class OrderDtos {
    private OrderDtos() {}

    record CreateProductRequest(@NotBlank @Size(max = 140) String name,
                                @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice,
                                @NotNull @PositiveOrZero Integer initialStock) {}
    record ProductResponse(UUID id, String name, BigDecimal unitPrice, int onHand, int reserved, int available) {}
    record OrderItemRequest(@NotNull UUID productId, @NotNull @Positive Integer quantity) {}
    record CreateOrderRequest(@NotEmpty @Size(max = 25) List<@Valid OrderItemRequest> items) {}
    record OrderLineResponse(UUID productId, String productName, int quantity,
                             BigDecimal unitPriceSnapshot, BigDecimal lineTotal) {}
    record OrderResponse(UUID id, OrderStatus status, OffsetDateTime createdAt,
                         List<OrderLineResponse> items, BigDecimal total) {}
    enum OrderStatus { RESERVED, CONFIRMED, CANCELLED }
}
