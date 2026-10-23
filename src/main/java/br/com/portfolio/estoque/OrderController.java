package br.com.portfolio.estoque;

import static br.com.portfolio.estoque.OrderDtos.*;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class OrderController {
    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/products")
    ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = service.createProduct(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @GetMapping("/products")
    List<ProductResponse> listProducts() {
        return service.listProducts();
    }

    @PostMapping("/orders")
    ResponseEntity<OrderResponse> createOrder(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = service.createOrder(key, request);
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    @GetMapping("/orders/{id}")
    OrderResponse getOrder(@PathVariable UUID id) {
        return service.getOrder(id);
    }

    @PostMapping("/orders/{id}/confirm")
    OrderResponse confirm(@PathVariable UUID id) {
        return service.confirm(id);
    }

    @PostMapping("/orders/{id}/cancel")
    OrderResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
