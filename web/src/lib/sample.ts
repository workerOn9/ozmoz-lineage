// 内置示例 SQL：自造电商 schema（质量约束：示例语料只用公开/自造表名）。
export const SAMPLE_SQL = `SELECT o.order_id, c.name, SUM(oi.price * oi.qty) AS total
FROM orders o
JOIN customers c ON o.customer_id = c.id
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.status = 'paid'
GROUP BY o.order_id, c.name
ORDER BY total DESC
`

// 与 SAMPLE_SQL 对齐的 DDL（语义校验 `/api/validate` 的默认 schema）。
// 同为自造电商表；改动 SQL 里的列名即可在「校验」里看到 calcite.validate_error。
export const SAMPLE_SCHEMA = `CREATE TABLE orders (
  order_id INT,
  customer_id INT,
  status VARCHAR(20)
);
CREATE TABLE customers (
  id INT,
  name VARCHAR(100)
);
CREATE TABLE order_items (
  order_id INT,
  price DECIMAL(10,2),
  qty INT
);
`
