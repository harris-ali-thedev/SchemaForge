CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    age INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE products (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0)
);
INSERT INTO users(name,email,age) VALUES ('Ada','ada@example.test',28),('Lin','lin@example.test',32);
INSERT INTO products(name,price) VALUES ('Notebook',12.50),('Pen',2.25);
INSERT INTO orders(user_id,product_id,quantity) VALUES (1,1,2),(2,2,3);
