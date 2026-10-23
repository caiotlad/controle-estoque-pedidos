CREATE TABLE products (
    id UUID PRIMARY KEY,
    name VARCHAR(140) NOT NULL UNIQUE,
    unit_price NUMERIC(14, 2) NOT NULL CHECK (unit_price >= 0),
    on_hand INTEGER NOT NULL CHECK (on_hand >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    status VARCHAR(12) NOT NULL CHECK (status IN ('RESERVED', 'CONFIRMED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_lines (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id),
    product_name VARCHAR(140) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_snapshot NUMERIC(14, 2) NOT NULL CHECK (unit_price_snapshot >= 0),
    CONSTRAINT uq_order_product UNIQUE (order_id, product_id)
);

CREATE TABLE order_idempotency (
    idempotency_key VARCHAR(120) PRIMARY KEY,
    request_fingerprint CHAR(64) NOT NULL,
    order_id UUID REFERENCES orders(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_created_at ON orders(created_at DESC);
CREATE INDEX idx_order_lines_order ON order_lines(order_id);
