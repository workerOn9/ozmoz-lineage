// 内置示例 SQL：自造电商 schema（质量约束：示例语料只用公开/自造表名）。
export const SAMPLE_SQL = `SELECT o.order_id, c.name, SUM(oi.price * oi.qty) AS total
FROM orders o
JOIN customers c ON o.customer_id = c.id
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.status = 'paid'
GROUP BY o.order_id, c.name
ORDER BY total DESC
`
