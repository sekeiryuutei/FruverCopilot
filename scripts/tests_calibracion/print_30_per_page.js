const fs = require('fs');
const { execSync } = require('child_process');
const ipp = require('ipp');

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

function buildHtml(items) {
  // Regla: 30 items por hoja
  const maxPerPage = 30;
  const pagesCount = Math.max(1, Math.ceil(items.length / maxPerPage));

  let pagesHtml = '';

  for (let p = 0; p < pagesCount; p++) {
    // Si la última página quedaría con solo 1 o 2 ítems, rebalanceamos
    let from, to;
    if (pagesCount === 2 && items.length > 30 && items.length <= 34) {
      // Si son 31..34 items, ponemos 20 y el resto para que la pág 2 tenga al menos 11 items
      const half = Math.ceil(items.length / 2);
      from = p * half;
      to = Math.min(items.length, (p + 1) * half);
    } else {
      // 30 por hoja en pág 1
      from = p * maxPerPage;
      to = Math.min(items.length, (p + 1) * maxPerPage);
    }

    const pageItems = items.slice(from, to);
    const isFirstPage = (p === 0);
    const isLastPage = (p === pagesCount - 1);

    let rowsHtml = '';
    for (let i = 0; i < pageItems.length; i++) {
      const globalIdx = from + i + 1;
      const it = pageItems[i];
      rowsHtml += `<tr>
        <td style="text-align:center;font-weight:bold;padding:3.8px 3px;border:1px solid #1e40af;font-size:11px;color:#475569">${globalIdx}</td>
        <td style="text-align:center;font-weight:bold;padding:3.8px 3px;border:1px solid #1e40af;font-size:11px">${it.cant}</td>
        <td style="padding:3.8px 6px;border:1px solid #1e40af;text-transform:uppercase;font-size:11px;font-weight:normal">${it.desc}</td>
        <td style="text-align:right;padding:3.8px 3px;border:1px solid #1e40af;font-size:11px">&nbsp;</td>
        <td style="text-align:right;padding:3.8px 3px;border:1px solid #1e40af;font-size:11px">&nbsp;</td>
      </tr>\n`;
    }

    let headerHtml = '';
    if (isFirstPage) {
      const pageIndicator = pagesCount > 1 ? `<span style="font-size:9.5px;color:#64748b">(Pág 1/${pagesCount})</span>` : '';
      headerHtml = `
      <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:4px;padding-top:2px">
        <table style="width:100%;border:none">
          <tr>
            <td style="border:none;vertical-align:middle">
              <div style="font-size:18px;font-weight:900;letter-spacing:0.5px;color:#1e40af;line-height:1.05">FRUVER EL OASIS</div>
              <div style="font-size:10px;font-weight:bold;color:#334155;margin-top:2px">Frutas y Verduras al por Mayor</div>
            </td>
            <td style="border:none;text-align:right;vertical-align:middle">
              <div style="display:inline-block;padding:3px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11.5px;color:#1e40af;margin-right:5px">
                FACTURA / PEDIDO N° 000006 ${pageIndicator}
              </div>
              <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #1e40af;font-size:8.5px;text-align:center;width:85px;vertical-align:middle">
                <tr style="background:#e0e7ff;color:#1e40af;font-weight:bold">
                  <th style="padding:1px;border:1px solid #1e40af;width:33%">DIA</th>
                  <th style="padding:1px;border:1px solid #1e40af;width:33%">MES</th>
                  <th style="padding:1px;border:1px solid #1e40af;width:34%">AÑO</th>
                </tr>
                <tr style="font-size:10px;font-weight:bold;height:14px;color:#0f172a">
                  <td style="border:1px solid #1e40af">27</td>
                  <td style="border:1px solid #1e40af">10</td>
                  <td style="border:1px solid #1e40af">2026</td>
                </tr>
              </table>
            </td>
          </tr>
        </table>
      </div>
      <div style="border:1.5px solid #1e40af;border-radius:4px;padding:3px 6px;font-size:9.5px;margin-bottom:5px;line-height:1.25">
        <table style="width:100%;border:none;font-size:9.5px">
          <tr>
            <td style="border:none;width:65%"><b style="color:#1e40af">CLIENTE:</b> <span>MAÑANA</span></td>
            <td style="border:none;width:35%"><b style="color:#1e40af">NIT./ C.C.:</b> <span></span></td>
          </tr>
          <tr>
            <td style="border:none"><b style="color:#1e40af">DIRECCION:</b> <span>Álamos</span></td>
            <td style="border:none"><b style="color:#1e40af">TEL.:</b> <span>3104957719</span></td>
          </tr>
        </table>
      </div>`;
    } else {
      headerHtml = `
      <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:6px;padding-top:2px">
        <table style="width:100%;border:none">
          <tr>
            <td style="border:none">
              <span style="font-size:16px;font-weight:900;color:#1e40af">FRUVER EL OASIS</span>
              <span style="font-size:10.5px;color:#475569;margin-left:8px">— Continuación Pedido</span>
            </td>
            <td style="border:none;text-align:right">
              <div style="display:inline-block;padding:2px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11px;color:#1e40af">
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
      <div style="margin-top:5px;page-break-inside:avoid">
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
      <div style="margin-top:5px;text-align:right;font-size:10px;font-weight:bold;color:#64748b">
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
      margin: 6mm 7mm 3mm 7mm;
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

const html = buildHtml(items);
fs.writeFileSync('./talonario_30_20.html', html);
execSync('docker cp ./talonario_30_20.html fruvercopilot-backend:/tmp/talonario_30_20.html');
execSync('docker exec fruvercopilot-backend wkhtmltopdf -q -s A5 -B 3mm -T 6mm -L 7mm -R 7mm /tmp/talonario_30_20.html /tmp/talonario_30_20.pdf');
execSync('docker exec fruvercopilot-backend gs -q -dNOPAUSE -dBATCH -sDEVICE=pwgraster -dcupsColorSpace=18 -dcupsBitsPerColor=8 -sPAPERSIZE=a5 -r600 -sOutputFile=/tmp/talonario_30_20.pwg /tmp/talonario_30_20.pdf');
execSync('docker cp fruvercopilot-backend:/tmp/talonario_30_20.pwg ./talonario_30_20.pwg');

const pwgData = fs.readFileSync('talonario_30_20.pwg');
console.log('Enviando talonario_30_20.pwg (' + pwgData.length + ' bytes)...');

const printer = ipp.Printer('http://192.168.1.5:631/ipp/print');
const msg = {
  'operation-attributes-tag': {
    'requesting-user-name': 'FruverCopilot',
    'job-name': 'Pedido_6_30items_Pag1',
    'document-format': 'image/pwg-raster'
  },
  'job-attributes-tag': {
    'media': 'iso_a5_148x210mm',
    'print-quality': 'normal'
  },
  data: pwgData
};

printer.execute('Print-Job', msg, (err, res) => {
  if (err) console.error(err);
  else {
    console.log('Status:', res.statusCode);
    const j = res['job-attributes-tag'];
    console.log('Job ID:', j['job-id'], 'State:', j['job-state'], 'Reasons:', j['job-state-reasons']);
  }
});

