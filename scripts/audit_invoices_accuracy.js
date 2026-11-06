const { Pool } = require('pg');

const pool = new Pool({
  host: process.env.DB_HOST || '127.0.0.1',
  port: parseInt(process.env.DB_PORT || '5439', 10),
  database: process.env.DB_NAME || 'fruver',
  user: process.env.DB_USER || 'fruver',
  password: process.env.DB_PASSWORD || 'fruver'
});

async function runAudit() {
  console.log('Iniciando auditoría exhaustiva de facturas vs mensajes ingresados...\n');

  // 1. Estadísticas globales
  const statsRes = await pool.query(`
    SELECT 
      count(distinct s.id) as total_sales,
      count(distinct o.id) as total_orders,
      sum(case when o.corrected = true then 1 else 0 end) as orders_corrected_by_human,
      sum(case when o.review_status = 'GREEN' then 1 else 0 end) as green_orders,
      sum(case when o.review_status = 'YELLOW' then 1 else 0 end) as yellow_orders,
      sum(case when o.review_status = 'RED' then 1 else 0 end) as red_orders
    FROM sale s
    JOIN orders o ON s.order_id = o.id
  `);

  console.log('--- RESUMEN GLOBAL DE FACTURAS EXISTENTES ---');
  console.log(statsRes.rows[0]);

  // 2. Traer una muestra representativa de ventas con sus mensajes y líneas
  const salesQuery = `
    SELECT 
      s.id as sale_id,
      s.invoice_number,
      s.created_at,
      o.id as order_id,
      o.customer_name,
      o.input_type,
      o.review_status,
      o.corrected,
      o.ai_note,
      string_agg(distinct cm.content, ' || ') as raw_content
    FROM sale s
    JOIN orders o ON s.order_id = o.id
    LEFT JOIN conversation_message cm ON cm.order_id = o.id AND cm.direction = 'INBOUND'
    GROUP BY s.id, s.invoice_number, s.created_at, o.id, o.customer_name, o.input_type, o.review_status, o.corrected, o.ai_note
    ORDER BY s.id DESC
    LIMIT 40
  `;

  const salesRes = await pool.query(salesQuery);
  console.log(`\nAnalizando ${salesRes.rows.length} ventas recientes detalladamente...\n`);

  let totalItemsAnalyzed = 0;
  let perfectMatches = 0;
  let unitDiscrepancies = 0;
  let priceDiscrepancies = 0;
  let missingItemsFound = 0;
  let caseStudies = [];

  for (const sale of salesRes.rows) {
    const itemsRes = await pool.query(`
      SELECT 
        si.id as sale_item_id,
        si.product_name,
        si.quantity,
        si.unit,
        si.applied_price,
        si.subtotal,
        si.price_manually_changed,
        oi.mentioned,
        oi.ai_product_name,
        oi.ai_quantity,
        oi.ai_unit,
        oi.observation,
        oi.edited
      FROM sale_item si
      LEFT JOIN order_item oi ON oi.order_id = $1 AND (
        lower(oi.ai_product_name) = lower(si.product_name) 
        OR lower(si.product_name) LIKE '%' || lower(coalesce(oi.mentioned, 'xyz')) || '%'
      )
      WHERE si.sale_id = $2
    `, [sale.order_id, sale.sale_id]);

    const raw = (sale.raw_content || '').trim();
    const items = itemsRes.rows;
    totalItemsAnalyzed += items.length;

    let saleHasIssues = false;
    let issues = [];

    for (const item of items) {
      // Comparar lo que dijo el usuario con la factura
      const mention = item.mentioned || '';
      const prodName = item.product_name || '';
      const qty = parseFloat(item.quantity);
      const unit = (item.unit || '').toUpperCase();

      if (item.edited) {
        saleHasIssues = true;
        issues.push(`Ítem corregido por humano: "${mention}" -> ${prodName} ${qty} ${unit}`);
      }

      if (unit === 'VALOR') {
        // Pedido por valor monetario
      } else if (!unit && qty > 0) {
        // Sin unidad explícita (unidad / bulto / paquete)
      }
    }

    if (caseStudies.length < 10) {
      caseStudies.push({
        saleId: sale.sale_id,
        orderId: sale.order_id,
        customer: sale.customer_name,
        inputType: sale.input_type,
        reviewStatus: sale.review_status,
        rawText: raw.substring(0, 300),
        items: items.map(i => `${i.product_name}: ${i.quantity} ${i.unit || 'UNID'} (Mencionado: "${i.mentioned || ''}")`),
        corrected: sale.corrected,
        aiNote: sale.ai_note
      });
    }
  }

  console.log(JSON.stringify({
    totalItemsAnalyzed,
    sampleCases: caseStudies
  }, null, 2));

  await pool.end();
}

runAudit().catch(err => {
  console.error(err);
  process.exit(1);
});

