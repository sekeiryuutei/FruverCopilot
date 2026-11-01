const { execSync } = require('child_process');
const fs = require('fs');

// 1. Obtener los ítems de la venta 7 (pedido 6)
const rawData = execSync('docker exec -i fruvercopilot-postgres psql -U fruver -d fruver -A -t -F "|" -c "SELECT product_name, quantity, unit FROM sale_item WHERE sale_id = 7 ORDER BY id ASC"').toString();

const lines = rawData.trim().split('\n').filter(Boolean).map(row => {
    const [name, qty, unit] = row.split('|');
    return { name: name || '', qty: parseFloat(qty) || 1, unit: unit || '' };
});

console.log(`Ítems cargados: ${lines.length}`);

// Distribución: 35 ítems en Pág 1, 15 ítems en Pág 2 (con bloque de totales y firma)
const p1Count = 35;
const pages = [
    lines.slice(0, p1Count),
    lines.slice(p1Count)
];
const pagesCount = pages.length;

let pagesHtml = '';

for (let p = 0; p < pagesCount; p++) {
    const pageLines = pages[p];
    const isFirstPage = (p === 0);
    const isLastPage = (p === pagesCount - 1);
    const from = isFirstPage ? 0 : p1Count;

    // Altura de fila calibrada a 25.5px (padding 4.2px) para absorber exactamente 0.5 cm adicionales
    let rowsHtml = '';
    for (let i = 0; i < pageLines.length; i++) {
        const globalIdx = from + i + 1;
        const l = pageLines[i];
        const cant = `${l.qty} ${l.unit}`.trim();
        rowsHtml += `
        <tr style="height:25.5px">
          <td style="text-align:center;font-weight:bold;padding:4.2px 2px;border:1px solid #000;font-size:11.5px;color:#111">${globalIdx}</td>
          <td style="text-align:center;font-weight:900;padding:4.2px 3px;border:1px solid #000;font-size:12px">${cant}</td>
          <td style="padding:4.2px 5px;border:1px solid #000;text-transform:uppercase;font-size:11.5px;font-weight:bold;white-space:nowrap;overflow:hidden">${l.name.toUpperCase()}</td>
          <td style="text-align:right;padding:4.2px 5px;border:1px solid #000;font-size:11.5px">&nbsp;</td>
          <td style="text-align:right;padding:4.2px 5px;border:1px solid #000;font-size:11.5px">&nbsp;</td>
        </tr>`;
    }

    let headerHtml = '';
    if (isFirstPage) {
        headerHtml = `
        <div style="border-bottom:2px solid #000;padding-bottom:5px;margin-bottom:6px">
          <table style="width:100%;border-collapse:collapse">
            <tr>
              <td style="vertical-align:middle">
                <div style="font-size:25px;font-weight:900;letter-spacing:0.3px;color:#000;line-height:1.05">FRUVER EL OASIS</div>
                <div style="font-size:11.5px;font-weight:bold;color:#333;margin-top:3px">Frutas y Verduras al por Mayor</div>
              </td>
              <td style="text-align:right;vertical-align:middle">
                <div style="display:inline-block;padding:5px 10px;border:2px solid #000;border-radius:3px;font-weight:900;font-size:13.5px;color:#000;margin-right:6px">
                  FACTURA N° 000006 <span style="font-size:11px;color:#444">(1/${pagesCount})</span>
                </div>
                <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #000;font-size:10px;text-align:center;width:105px;vertical-align:middle">
                  <tr style="background:#e5e7eb;color:#000;font-weight:bold">
                    <th style="padding:2px;border:1px solid #000">D</th>
                    <th style="padding:2px;border:1px solid #000">M</th>
                    <th style="padding:2px;border:1px solid #000">A</th>
                  </tr>
                  <tr style="font-size:11px;font-weight:bold;height:18px;color:#000">
                    <td style="border:1px solid #000">31</td>
                    <td style="border:1px solid #000">10</td>
                    <td style="border:1px solid #000">26</td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </div>
        <div style="border:1.5px solid #000;border-radius:3px;padding:6px 10px;font-size:12px;margin-bottom:6px;line-height:1.35">
          <table style="width:100%;border-collapse:collapse;font-size:12px">
            <tr>
              <td style="width:65%"><b>CLIENTE:</b> <span>MAÑANA</span></td>
              <td style="width:35%"><b>TEL:</b> <span>3104957719</span></td>
            </tr>
          </table>
        </div>`;
    } else {
        headerHtml = `
        <div style="border-bottom:2px solid #000;padding-bottom:5px;margin-bottom:7px">
          <table style="width:100%;border-collapse:collapse">
            <tr>
              <td style="vertical-align:middle">
                <span style="font-size:21px;font-weight:900;color:#000">FRUVER EL OASIS</span>
                <span style="font-size:12px;color:#333;margin-left:8px;font-weight:bold">— Continuación Pedido</span>
              </td>
              <td style="text-align:right;vertical-align:middle">
                <div style="display:inline-block;padding:4px 10px;border:2px solid #000;border-radius:3px;font-weight:900;font-size:13px;color:#000">
                  FACTURA N° 000006 (Pág ${p + 1}/${pagesCount})
                </div>
              </td>
            </tr>
          </table>
        </div>`;
    }

    let footerHtml = '';
    if (isLastPage) {
        footerHtml = `
        <div style="margin-top:14px;display:flex;justify-content:space-between;align-items:flex-end">
          <div style="width:56%;border:1.5px solid #000;border-radius:4px;padding:9px 12px;font-size:11.5px">
            <b>RECIBÍ A CONFORMIDAD:</b> ___________________________<br>
            <span style="font-size:10px;color:#555;margin-top:5px;display:inline-block">Firma y C.C. del cliente</span>
          </div>
          <div style="width:40%;text-align:right">
            <table style="width:100%;border-collapse:collapse;border:2px solid #000;font-size:14px;font-weight:bold">
              <tr style="background:#e5e7eb;color:#000">
                <td style="padding:8px 10px;border:1px solid #000;font-size:14px">TOTAL $</td>
                <td style="padding:8px 10px;border:1px solid #000;min-width:90px">&nbsp;</td>
              </tr>
            </table>
          </div>
        </div>`;
    }

    const breakStyle = (p < pagesCount - 1) ? 'page-break-after: always;' : '';
    pagesHtml += `
    <div class="a4-sheet" style="${breakStyle}">
      <div class="a5-invoice-container">
        ${headerHtml}
        <table class="items-table" style="width:100%;border-collapse:collapse;border:2px solid #000">
          <thead>
            <tr style="background:#e5e7eb;color:#000;font-size:11px;font-weight:900;height:24px">
              <th style="padding:3px;border:1px solid #000;width:6%;text-align:center">#</th>
              <th style="padding:3px;border:1px solid #000;width:16%;text-align:center">CANT.</th>
              <th style="padding:3px 5px;border:1px solid #000;width:36%;text-align:left">DESCRIPCIÓN</th>
              <th style="padding:3px 5px;border:1px solid #000;width:21%;text-align:right">VR. UNIT</th>
              <th style="padding:3px 5px;border:1px solid #000;width:21%;text-align:right">VR. TOTAL</th>
            </tr>
          </thead>
          <tbody>
            ${rowsHtml}
          </tbody>
        </table>
        ${footerHtml}
      </div>
    </div>`;
}

const fullHtml = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  @page {
    size: A4 portrait;
    margin: 0;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    padding: 0;
    font-family: Arial, Helvetica, sans-serif;
    color: #000;
    -webkit-print-color-adjust: exact;
  }
  .a4-sheet {
    width: 270mm;
    position: relative;
    overflow: hidden;
  }
  .a5-invoice-container {
    width: 176mm;
    margin-left: 50mm;
    padding-top: 3mm;
  }
  td, th {
    vertical-align: middle;
  }
</style>
</head>
<body>
${pagesHtml}
</body>
</html>`;

fs.writeFileSync('factura_6.html', fullHtml, 'utf8');
console.log('✓ HTML calibrado a 0.5cm exacto generado en factura_6.html');

// 3. Ejecutar wkhtmltopdf
execSync('docker cp factura_6.html fruvercopilot-backend:/tmp/factura_6.html');
execSync('docker exec fruvercopilot-backend wkhtmltopdf -q -s A4 -B 0 -T 0 -L 0 -R 0 /tmp/factura_6.html /tmp/factura_6.pdf');
execSync('docker cp fruvercopilot-backend:/tmp/factura_6.pdf factura_6.pdf');

const stats = fs.statSync('factura_6.pdf');
console.log(`✓ PDF generado exitosamente: factura_6.pdf (${stats.size} bytes)`);
