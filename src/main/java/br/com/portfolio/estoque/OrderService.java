package br.com.portfolio.estoque;

import static br.com.portfolio.estoque.OrderDtos.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class OrderService {
    private final JdbcTemplate jdbc;

    OrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record ProductRow(UUID id, String name, BigDecimal unitPrice, int onHand, int reserved) {
        ProductResponse response() {
            return new ProductResponse(id, name, unitPrice, onHand, reserved, onHand - reserved);
        }
    }
    private record LineRow(UUID productId, String productName, int quantity, BigDecimal unitPrice) {}
    private static final RowMapper<ProductRow> PRODUCT = (rs, row) -> new ProductRow(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getBigDecimal("unit_price"),
            rs.getInt("on_hand"), rs.getInt("reserved"));

    @Transactional
    ProductResponse createProduct(CreateProductRequest request) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO products(id, name, unit_price, on_hand) VALUES (?, ?, ?, ?)",
                    id, request.name().trim(), request.unitPrice(), request.initialStock());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um produto com esse nome");
        }
        return jdbc.queryForObject("SELECT id, name, unit_price, on_hand, reserved FROM products WHERE id = ?", PRODUCT, id).response();
    }

    List<ProductResponse> listProducts() {
        return jdbc.query("SELECT id, name, unit_price, on_hand, reserved FROM products ORDER BY name", PRODUCT)
                .stream().map(ProductRow::response).toList();
    }

    @Transactional
    OrderResponse createOrder(String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie Idempotency-Key com 1 a 120 caracteres");
        }
        List<OrderItemRequest> requested = new ArrayList<>(request.items());
        Set<UUID> uniqueProducts = new HashSet<>();
        for (OrderItemRequest item : requested) {
            if (!uniqueProducts.add(item.productId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada produto deve aparecer uma única vez no pedido");
            }
        }
        requested.sort(Comparator.comparing(OrderItemRequest::productId));
        String fingerprint = fingerprint(requested);

        // O INSERT com ON CONFLICT serializa duas requisições que reutilizam a mesma chave.
        int inserted = jdbc.update("""
                INSERT INTO order_idempotency(idempotency_key, request_fingerprint)
                VALUES (?, ?) ON CONFLICT (idempotency_key) DO NOTHING
                """, idempotencyKey, fingerprint);
        if (inserted == 0) {
            IdempotencyRow previous = jdbc.queryForObject("""
                    SELECT request_fingerprint, order_id FROM order_idempotency WHERE idempotency_key = ?
                    """, (rs, row) -> new IdempotencyRow(rs.getString("request_fingerprint"),
                    rs.getObject("order_id", UUID.class)), idempotencyKey);
            if (!previous.fingerprint().equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Essa chave já foi usada com outro conteúdo");
            }
            if (previous.orderId() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A chave idempotente não aponta para um pedido concluído");
            }
            return getOrder(previous.orderId());
        }

        // Sempre bloqueia produtos por UUID crescente para reduzir o risco de deadlock.
        List<ProductRow> lockedProducts = new ArrayList<>();
        for (OrderItemRequest item : requested) {
            List<ProductRow> rows = jdbc.query("""
                    SELECT id, name, unit_price, on_hand, reserved FROM products WHERE id = ? FOR UPDATE
                    """, PRODUCT, item.productId());
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado: " + item.productId());
            ProductRow product = rows.get(0);
            if (product.onHand() - product.reserved() < item.quantity()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Estoque insuficiente para " + product.name());
            }
            lockedProducts.add(product);
        }

        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders(id, status) VALUES (?, 'RESERVED')", orderId);
        for (int i = 0; i < requested.size(); i++) {
            OrderItemRequest item = requested.get(i);
            ProductRow product = lockedProducts.get(i);
            jdbc.update("UPDATE products SET reserved = reserved + ? WHERE id = ?", item.quantity(), product.id());
            jdbc.update("""
                    INSERT INTO order_lines(id, order_id, product_id, product_name, quantity, unit_price_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), orderId, product.id(), product.name(), item.quantity(), product.unitPrice());
        }
        jdbc.update("UPDATE order_idempotency SET order_id = ? WHERE idempotency_key = ?", orderId, idempotencyKey);
        return getOrder(orderId);
    }

    @Transactional
    OrderResponse confirm(UUID orderId) {
        OrderStatus status = lockOrder(orderId);
        if (status == OrderStatus.CONFIRMED) return getOrder(orderId);
        if (status != OrderStatus.RESERVED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Somente pedidos reservados podem ser confirmados");
        List<LineRow> lines = loadLines(orderId);
        for (LineRow line : lines) lockProduct(line.productId());
        for (LineRow line : lines) {
            int updated = jdbc.update("""
                    UPDATE products SET on_hand = on_hand - ?, reserved = reserved - ?
                    WHERE id = ? AND reserved >= ? AND on_hand >= ?
                    """, line.quantity(), line.quantity(), line.productId(), line.quantity(), line.quantity());
            if (updated != 1) throw new IllegalStateException("A reserva não corresponde ao estoque do produto " + line.productId());
        }
        jdbc.update("UPDATE orders SET status = 'CONFIRMED', updated_at = now() WHERE id = ?", orderId);
        return getOrder(orderId);
    }

    @Transactional
    OrderResponse cancel(UUID orderId) {
        OrderStatus status = lockOrder(orderId);
        if (status == OrderStatus.CANCELLED) return getOrder(orderId);
        if (status != OrderStatus.RESERVED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Somente pedidos reservados podem ser cancelados");
        List<LineRow> lines = loadLines(orderId);
        for (LineRow line : lines) lockProduct(line.productId());
        for (LineRow line : lines) {
            int updated = jdbc.update("UPDATE products SET reserved = reserved - ? WHERE id = ? AND reserved >= ?",
                    line.quantity(), line.productId(), line.quantity());
            if (updated != 1) throw new IllegalStateException("A reserva não corresponde ao estoque do produto " + line.productId());
        }
        jdbc.update("UPDATE orders SET status = 'CANCELLED', updated_at = now() WHERE id = ?", orderId);
        return getOrder(orderId);
    }

    OrderResponse getOrder(UUID orderId) {
        List<OrderResponse> headers = jdbc.query("SELECT id, status, created_at FROM orders WHERE id = ?",
                (rs, row) -> new OrderResponse(rs.getObject("id", UUID.class), OrderStatus.valueOf(rs.getString("status")),
                        rs.getObject("created_at", OffsetDateTime.class), List.of(), BigDecimal.ZERO), orderId);
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado");
        OrderResponse header = headers.get(0);
        List<OrderLineResponse> lines = jdbc.query("""
                SELECT product_id, product_name, quantity, unit_price_snapshot
                FROM order_lines WHERE order_id = ? ORDER BY product_id
                """, (rs, row) -> {
                    BigDecimal unitPrice = rs.getBigDecimal("unit_price_snapshot");
                    int quantity = rs.getInt("quantity");
                    return new OrderLineResponse(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                            quantity, unitPrice, unitPrice.multiply(BigDecimal.valueOf(quantity)));
                }, orderId);
        BigDecimal total = lines.stream().map(OrderLineResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(header.id(), header.status(), header.createdAt(), lines, total);
    }

    private OrderStatus lockOrder(UUID id) {
        List<OrderStatus> rows = jdbc.query("SELECT status FROM orders WHERE id = ? FOR UPDATE",
                (rs, row) -> OrderStatus.valueOf(rs.getString("status")), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado");
        return rows.get(0);
    }

    private ProductRow lockProduct(UUID id) {
        List<ProductRow> rows = jdbc.query("SELECT id, name, unit_price, on_hand, reserved FROM products WHERE id = ? FOR UPDATE", PRODUCT, id);
        if (rows.isEmpty()) throw new IllegalStateException("Produto referenciado pelo pedido não existe: " + id);
        return rows.get(0);
    }

    private List<LineRow> loadLines(UUID orderId) {
        return jdbc.query("""
                SELECT product_id, product_name, quantity, unit_price_snapshot FROM order_lines
                WHERE order_id = ? ORDER BY product_id
                """, (rs, row) -> new LineRow(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                rs.getInt("quantity"), rs.getBigDecimal("unit_price_snapshot")), orderId);
    }

    private String fingerprint(List<OrderItemRequest> items) {
        String canonical = items.stream().map(item -> item.productId() + ":" + item.quantity())
                .reduce((left, right) -> left + "|" + right).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 não está disponível", impossible);
        }
    }

    private record IdempotencyRow(String fingerprint, UUID orderId) {}
}
