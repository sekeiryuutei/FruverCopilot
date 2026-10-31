const fs = require('fs');
const { execSync } = require('child_process');

function renderHtml(itemCount) {
  const items = Array.from({length: itemCount}, (_, i) => ({
    cant: (i + 1) + ' LB',
    desc: 'PRODUCTO ' + (i + 1)
  }));

  const maxPerPage = 30;
  const pagesCount = Math.max(1, Math.ceil(items.length / maxPerPage));
  const chunkSize = Math.ceil(items.length / pagesCount);

  let pagesHtml = '';

  for (let p = 0; p < pagesCount; p++) {
    const pageItems = items.slice(p * chunkSize, (p + 1) * chunkSize);
    const isFirstPage = (p === 0);
    const isLastPage = (p === pagesCount - 1);
    const targetRows = (pagesCount === 1 && pageItems.length <= 16) ? 16 : pageItems.length;

    let rowsHtml = '';
    for (let i = 0; i < targetRows; i++) {
      const globalIdx = p * chunkSize + i + 1;
      if (i < pageItems.length) {
        const it = pageItems[i];
        rowsHtml += `<tr>
          <td style="text-align:center;font-weight:bold;padding:3.5px 3px;border:1px solid #1e40af;font-size:11px;color:#475569">${globalIdx}</td>
          <td style="text-align:center;font-weight:bold;padding:3.5px 3px;border:1px solid #1e40af;font-size:11px">${it.cant}</td>
          <td style="padding:3.5px 6px;border:1px solid #1e40af;text-transform:uppercase;font-size:11px;font-weight:normal">${it.desc}</td>
          <td style="text-align:right;padding:3.5px 3px;border:1px solid #1e40af;font-size:11px">&nbsp;</td>
          <td style="text-align:right;padding:3.5px 3px;border:1px solid #1e40af;font-size:11px">&nbsp;</td>
        </tr>\n`;
      } else {
        rowsHtml += `<tr>
          <td style="height:19px;text-align:center;color:#94a3b8;font-size:11px;border:1px solid #1e40af">${globalIdx}</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
          <td style="border:1px solid #1e40af">&nbsp;</td>
        </tr>\n`;
      }
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

// Probar con 50 items (debe dar 2 páginas de 25 items cada una, balanceadas)
const html50 = renderHtml(50);
fs.writeFileSync('./test_bal_50.html', html50);
execSync('docker cp ./test_bal_50.html fruvercopilot-backend:/tmp/test_bal_50.html');
execSync('docker exec fruvercopilot-backend wkhtmltopdf -q -s A5 -B 3mm -T 6mm -L 7mm -R 7mm /tmp/test_bal_50.html /tmp/test_bal_50.pdf');
execSync('docker exec fruvercopilot-backend gs -q -sDEVICE=png16m -r150 -o /tmp/bal50_p%03d.png /tmp/test_bal_50.pdf');
const pages = execSync('docker exec fruvercopilot-backend bash -c "ls -1 /tmp/bal50_p*.png"').toString().trim().split('\n').filter(Boolean);
console.log('50 items generó: ' + pages.length + ' páginas');

// Probar con 30 items (debe dar 1 sola página completa con los 30 items)
const html30 = renderHtml(30);
fs.writeFileSync('./test_bal_30.html', html30);
execSync('docker cp ./test_bal_30.html fruvercopilot-backend:/tmp/test_bal_30.html');
execSync('docker exec fruvercopilot-backend wkhtmltopdf -q -s A5 -B 3mm -T 6mm -L 7mm -R 7mm /tmp/test_bal_30.html /tmp/test_bal_30.pdf');
execSync('docker exec fruvercopilot-backend gs -q -sDEVICE=png16m -r150 -o /tmp/bal30_p%03d.png /tmp/test_bal_30.pdf');
const pages30 = execSync('docker exec fruvercopilot-backend bash -c "ls -1 /tmp/bal30_p*.png"').toString().trim().split('\n').filter(Boolean);
console.log('30 items generó: ' + pages30.length + ' páginas');
