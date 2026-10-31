const fs = require('fs');
const { execSync } = require('child_process');

function renderTalonarioHtml(t) {
  const items = t.items || [];
  const chunkSize = 25;
  const pagesCount = Math.max(1, Math.ceil(items.length / chunkSize));
  
  let pagesHtml = '';

  for (let p = 0; p < pagesCount; p++) {
    const pageItems = items.slice(p * chunkSize, (p + 1) * chunkSize);
    const isFirstPage = (p === 0);
    const isLastPage = (p === pagesCount - 1);
    
    // Fill up to 16 lines if order is small (< 16), or 25 if multiple pages
    const targetRows = (pagesCount === 1 && pageItems.length <= 16) ? 16 : pageItems.length;

    let rowsHtml = '';
    for (let i = 0; i < targetRows; i++) {
      const globalIdx = p * chunkSize + i + 1;
      if (i < pageItems.length) {
        const it = pageItems[i];
        rowsHtml += `<tr>
          <td style="text-align:center;font-weight:bold;padding:4.5px 3px;border:1px solid #1e40af;font-size:11.5px;color:#475569">${globalIdx}</td>
          <td style="text-align:center;font-weight:bold;padding:4.5px 3px;border:1px solid #1e40af;font-size:11.5px">${it.cant}</td>
          <td style="padding:4.5px 6px;border:1px solid #1e40af;text-transform:uppercase;font-size:11.5px;font-weight:bold">${it.desc}</td>
          <td style="text-align:right;padding:4.5px 3px;border:1px solid #1e40af;font-size:11.5px">&nbsp;</td>
          <td style="text-align:right;padding:4.5px 3px;border:1px solid #1e40af;font-weight:bold;font-size:11.5px">&nbsp;</td>
        </tr>\n`;
      } else {
        rowsHtml += `<tr>
          <td style="height:21px;text-align:center;color:#94a3b8;font-size:11.5px;border:1px solid #1e40af">${globalIdx}</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
        </tr>\n`;
      }
    }

    let headerHtml = '';
    if (isFirstPage) {
      headerHtml = `
      <!-- Encabezado Principal Completo -->
      <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:4px;padding-top:2px">
        <table style="width:100%;border:none">
          <tr>
            <td style="border:none;vertical-align:middle">
              <div style="font-size:18px;font-weight:900;letter-spacing:0.5px;color:#1e40af;line-height:1.05">FRUVER EL OASIS</div>
              <div style="font-size:10px;font-weight:bold;color:#334155;margin-top:2px">Frutas y Verduras al por Mayor</div>
            </td>
            <td style="border:none;text-align:right;vertical-align:middle">
              <div style="display:inline-block;padding:3px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11.5px;color:#1e40af;margin-right:5px">
                FACTURA / PEDIDO N° ${t.orderNum} ${pagesCount > 1 ? '<span style="font-size:9.5px;color:#64748b">(Pág 1/' + pagesCount + ')</span>' : ''}
              </div>
              <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #1e40af;font-size:8.5px;text-align:center;width:85px;vertical-align:middle">
                <tr style="background:#e0e7ff;color:#1e40af;font-weight:bold">
                  <th style="padding:1px;border:1px solid #1e40af;width:33%">DIA</th>
                  <th style="padding:1px;border:1px solid #1e40af;width:33%">MES</th>
                  <th style="padding:1px;border:1px solid #1e40af;width:34%">AÑO</th>
                </tr>
                <tr style="font-size:10px;font-weight:bold;height:14px;color:#0f172a">
                  <td style="border:1px solid #1e40af">${t.day}</td>
                  <td style="border:1px solid #1e40af">${t.month}</td>
                  <td style="border:1px solid #1e40af">${t.year}</td>
                </tr>
              </table>
            </td>
          </tr>
        </table>
      </div>

      <!-- Recuadro Cliente -->
      <div style="border:1.5px solid #1e40af;border-radius:4px;padding:3px 6px;font-size:9.5px;margin-bottom:5px;line-height:1.25">
        <table style="width:100%;border:none;font-size:9.5px">
          <tr>
            <td style="border:none;width:65%"><b style="color:#1e40af">CLIENTE:</b> <span>${t.customer}</span></td>
            <td style="border:none;width:35%"><b style="color:#1e40af">NIT./ C.C.:</b> <span></span></td>
          </tr>
          <tr>
            <td style="border:none"><b style="color:#1e40af">DIRECCION:</b> <span>${t.address}</span></td>
            <td style="border:none"><b style="color:#1e40af">TEL.:</b> <span>${t.phone}</span></td>
          </tr>
          ${t.obs ? '<tr><td colspan="2" style="border:none;font-size:9px;color:#475569"><b style="color:#1e40af">OBS:</b> ' + t.obs + '</td></tr>' : ''}
        </table>
      </div>`;
    } else {
      headerHtml = `
      <!-- Encabezado Continuación Páginas 2+ -->
      <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:6px;padding-top:2px;display:flex;justify-content:space-between;align-items:center">
        <table style="width:100%;border:none">
          <tr>
            <td style="border:none">
              <span style="font-size:16px;font-weight:900;color:#1e40af">FRUVER EL OASIS</span>
              <span style="font-size:10.5px;color:#475569;margin-left:8px">— Continuación Pedido</span>
            </td>
            <td style="border:none;text-align:right">
              <div style="display:inline-block;padding:2px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11px;color:#1e40af">
                FACTURA N° ${t.orderNum} (Pág ${p + 1}/${pagesCount})
              </div>
            </td>
          </tr>
        </table>
      </div>`;
    }

    let footerHtml = '';
    if (isLastPage) {
      footerHtml = `
      <!-- Pie Final -->
      <div style="margin-top:6px;page-break-inside:avoid">
        <table style="width:100%;border:none">
          <tr>
            <td style="border:none;font-size:10px;font-weight:bold;color:#1e40af;vertical-align:middle">
              RECIBI: <span style="display:inline-block;width:150px;border-bottom:1.5px solid #1e40af">&nbsp;</span>
            </td>
            <td style="border:none;text-align:right;width:145px">
              <div style="border:1.5px solid #1e40af;padding:3px 6px;display:flex;justify-content:space-between;align-items:center;font-weight:bold;font-size:11px;background:#f8fafc">
                <span style="color:#1e40af">TOTAL $</span>
                <span style="font-size:11.5px;color:#0f172a">&nbsp;</span>
              </div>
            </td>
          </tr>
        </table>
      </div>`;
    } else {
      footerHtml = `
      <div style="margin-top:6px;text-align:right;font-size:10px;font-weight:bold;color:#64748b">
        Continúa en la siguiente página (Pág ${p + 2}/${pagesCount}) ➔
      </div>`;
    }

    const pageBreak = (p < pagesCount - 1) ? 'page-break-after: always; break-after: page;' : '';

    pagesHtml += `
    <div class="page-container" style="${pageBreak}">
      ${headerHtml}
      <table style="width:100%;border:1.5px solid #1e40af;font-size:11px">
        <thead>
          <tr style="background:#e0e7ff;color:#1e40af;font-size:10px;font-weight:bold;text-align:center">
            <th style="width:7%;padding:3px 2px;border:1px solid #1e40af">#</th>
            <th style="width:16%;padding:3px 2px;border:1px solid #1e40af">CANT.</th>
            <th style="width:47%;padding:3px 5px;border:1px solid #1e40af;text-align:left">DESCRIPCION ARTICULO.</th>
            <th style="width:15%;padding:3px 2px;border:1px solid #1e40af;text-align:right">VR. UNIT</th>
            <th style="width:15%;padding:3px 2px;border:1px solid #1e40af;text-align:right">VR. TOTAL</th>
          </tr>
        </thead>
        <tbody>
          ${rowsHtml}
        </tbody>
      </table>
      ${footerHtml}
    </div>`;
  }

  return `<!DOCTYPE html>
  <html>
  <head>
  <meta charset="utf-8">
  <style>
    @page {
      size: A5 portrait;
      margin: 6mm 7mm 5mm 7mm;
    }
    body {
      font-family: Arial, sans-serif;
      margin: 0;
      padding: 0;
      color: #000;
      background: #fff;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    tr {
      page-break-inside: avoid;
      break-inside: avoid;
    }
    .page-container {
      width: 100%;
      box-sizing: border-box;
    }
  </style>
  </head>
  <body>
    ${pagesHtml}
  </body>
  </html>`;
}

const items = [
  {cant: "1 LB", desc: "PARDA"}, {cant: "25 LB", desc: "AMARILLA"}, {cant: "20 LB", desc: "ZANAHORIA"},
  {cant: "8 LB", desc: "HABICHUELA"}, {cant: "1 LB", desc: "CEBOLLA CABEZONA"}, {cant: "10 LB", desc: "TOMATE DE ARBOL"},
  {cant: "15 LB", desc: "MARACUYA"}, {cant: "6 LB", desc: "GUAYABA PERA"}, {cant: "8 UN", desc: "PAPAYA"},
  {cant: "4 LB", desc: "PEPINO COHOMBRO"}, {cant: "4 LB", desc: "PIMENTON ROJO"}, {cant: "10 LB", desc: "LULO"},
  {cant: "3 LB", desc: "REMOLACHA"}, {cant: "6 LB", desc: "MANDARINA"}, {cant: "1 UN", desc: "AGUACATE HASS"},
  {cant: "10 LB", desc: "YUCA"}, {cant: "4 LB", desc: "ARRACACHA"}, {cant: "3 UN", desc: "MAZORCA"},
  {cant: "1 UN", desc: "GUINEO"}, {cant: "1 LB", desc: "CEBOLLA LARGA"}, {cant: "2 LB", desc: "BANANO"},
  {cant: "1 UN", desc: "ZAPALLO"}, {cant: "2 UN", desc: "PLATANO VERDE"}, {cant: "1 LB", desc: "TOMATE CHONTO"},
  {cant: "1 LB", desc: "ARVEJA VERDE EN VAINA"}, {cant: "3 LB", desc: "FRIJOL VERDE DESGRANADO"}, {cant: "10 LB", desc: "MORA"},
  {cant: "4 UN", desc: "PINA"}, {cant: "30 LB", desc: "NARANJA VALENCIA"}, {cant: "1 UN", desc: "APIO"},
  {cant: "2 UN", desc: "BROCOLI"}, {cant: "1 UN", desc: "COLIFLOR"}, {cant: "2 LB", desc: "CHAMPINON"},
  {cant: "2 LB", desc: "JENGIBRE"}, {cant: "4 LB", desc: "BERENJENA"}, {cant: "4 LB", desc: "SABILA"},
  {cant: "2 LB", desc: "SIDRA"}, {cant: "3 UN", desc: "MAZORCA"}, {cant: "1 UN", desc: "CILANTRO"},
  {cant: "1 LB", desc: "CIMARRON"}, {cant: "2 UN", desc: "TOMILLO"}, {cant: "2 UN", desc: "ESPINACA"},
  {cant: "1 UN", desc: "LECHUGA BATAVIA"}, {cant: "2 LB", desc: "AJO"}, {cant: "2 UN", desc: "COCO"},
  {cant: "6 LB", desc: "CEBOLLA CABEZONA ROJA"}, {cant: "10 UN", desc: "PERA"}, {cant: "6 UN", desc: "MANZANA ROJA"},
  {cant: "10 UN", desc: "MANZANA VERDE"}, {cant: "4 LB", desc: "UVA ROJA"}
];

const html = renderTalonarioHtml({
  items,
  orderNum: '000006',
  day: '27',
  month: '10',
  year: '2026',
  customer: 'MAÑANA',
  address: 'Álamos',
  phone: '3104957719',
  obs: 'Horario: para mañana | Pago: Efectivo'
});

fs.writeFileSync('./talonario_chunked.html', html);
execSync('docker cp ./talonario_chunked.html fruvercopilot-backend:/tmp/talonario_chunked.html');
execSync('docker exec fruvercopilot-backend wkhtmltopdf -q -s A5 -B 5mm -T 6mm -L 7mm -R 7mm /tmp/talonario_chunked.html /tmp/talonario_chunked.pdf');
execSync('docker exec fruvercopilot-backend gs -q -sDEVICE=png16m -r150 -o /tmp/chunk_p%03d.png /tmp/talonario_chunked.pdf');
console.log('Chunked test compiled to PNG!');

