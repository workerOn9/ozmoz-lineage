/**
 * 演示示例库：落地页画廊与工作台「示例」下拉共用的唯一出处。
 *
 * 质量约束（合规红线）：示例语料只用公开 / 自造的电商 schema 表名，不引用任何真实业务标识。
 *
 * 每条示例可带两个可选开关：
 * - `schema`：内联 DDL，载入后进 schema 面板（语义校验 / 星号展开的元数据来源）；
 * - `aggregate`：true = 多语句合并图（ADR-0011 opt-in），载入时自动勾「聚合多语句」。
 */

export interface DemoExample {
  id: string
  title: string
  blurb: string
  sql: string
  schema?: string
  aggregate?: boolean
}

export const EXAMPLES: DemoExample[] = [
  {
    id: 'sample',
    title: '列级血缘入门',
    blurb: 'JOIN + 聚合查询，六类边一次看齐。',
    sql: `SELECT o.order_id, c.name, SUM(oi.price * oi.qty) AS total
FROM orders o
JOIN customers c ON o.customer_id = c.id
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.status = 'paid'
GROUP BY o.order_id, c.name
ORDER BY total DESC
`,
    schema: `CREATE TABLE orders (
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
`,
  },
  {
    id: 'cte-chain',
    title: 'CTE 链式引用',
    blurb: '多层 WITH 逐层引用，血缘跨 CTE 下溯。',
    sql: `WITH paid AS (
  SELECT order_id, customer_id, amount, order_date
  FROM orders
  WHERE status = 'paid'
),
ranked AS (
  SELECT order_id, customer_id, amount,
         ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date DESC) AS rn
  FROM paid
)
SELECT r.order_id, c.name, r.amount, r.rn
FROM ranked r
JOIN customers c ON r.customer_id = c.id
WHERE r.rn <= 3
`,
  },
  {
    id: 'star',
    title: '星号展开',
    blurb: 'SELECT * 结合 DDL 展开到具体列。',
    sql: `CREATE TABLE orders (
  order_id INT,
  customer_id INT,
  status VARCHAR(20)
);
CREATE TABLE customers (
  id INT,
  name VARCHAR(100)
);
SELECT o.*, c.name AS customer_name
FROM orders o
JOIN customers c ON o.customer_id = c.id
WHERE o.status = 'paid';
`,
    aggregate: true,
  },
  {
    id: 'aggregate',
    title: '多语句合并图',
    blurb: '两条 INSERT 语句汇成一张血缘图。',
    sql: `INSERT INTO order_summary (customer_id, total_amount)
SELECT customer_id, SUM(amount) AS total_amount
FROM orders
WHERE status = 'paid'
GROUP BY customer_id;

INSERT INTO vip_customers (customer_id, total_amount)
SELECT s.customer_id, s.total_amount
FROM order_summary s
WHERE s.total_amount > 1000;
`,
    aggregate: true,
  },
]

/** 按 id 取示例；id 为空或不在库内返回 null（落地页深链的入口守卫）。 */
export function getExample(id: string | null | undefined): DemoExample | null {
  return EXAMPLES.find((e) => e.id === id) ?? null
}
