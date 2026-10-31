const fs = require('fs');
const { execSync } = require('child_process');

function testLayout(fontSize, pad, topMargin) {
  const items = Array.from({length: 50}, (_, i) => ({
    cant: '1 LB',
    desc: 'PRODUCTO ' + (i + 1)
  }));

  let rows = '';
  for(let i=0; i<items.length; i++) {
    rows += `<tr>
      <td style="text-align:center;font-weight:bold;padding:${pad};border:1px solid #1e40af;font-size:${fontSize};color:#475569">${i+1}</td>
      <td style="text-align:center;font-weight:bold;padding:${pad};border:1px solid #1e40af;font-size:${fontSize}">${items[i].cant}</td>
      <td style="padding:${pad};border:1px solid #1e40af;text-transform:uppercase;font-size:${fontSize}">${items[i].desc}</td>
      <td style="text-align:right;padding:${pad};border:1px solid #1e40af;font-size:${fontSize}">&nbsp;</td>
      <td style="text-align:right;padding:${pad};border:1px solid #1e40af;font-weight:bold;font-size:${fontSize}">&nbsp;</td>
    </tr>\n`;
  }

  const html = `<!DOCTYPE html><html><head><meta charset="utf-8">
    <style>
      @page { size: A5 portrait; margin: ${topMargin} 6mm 2.5mm 6mm; }
      body { font-family: Arial, sans-serif; margin: 0; padding: 0; color: #000; background: #fff; }
      table { width: 100%; border-collapse: collapse; }
      tr { page-break-inside: avoid; break-inside: avoid; }
    </style></head><body>
      <div style="border-bottom:1.5px solid #1e40af;padding-bottom:2px;margin-bottom:2px;padding-top:2px">
        <table style="width:100%;border:none"><tr>
          <td style="border:none;vertical-align:middle">
            <div style="font-size:16px;font-weight:900;letter-spacing:0.5px;color:#1e40af;line-height:1.05">FRUVER EL OASIS</div>
            <div style="font-size:9.5px;font-weight:bold;color:#334155;margin-top:1px">Frutas y Verduras al por Mayor</div>
          </td>
          <td style="border:none;text-align:right;vertical-align:middle">
            <div style="display:inline-block;padding:2px 6px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:10.5px;color:#1e40af;margin-right:4px">FACTURA / PEDIDO N° 000006</div>
            <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #1e40af;font-size:8px;text-align:center;width:80px;vertical-align:middle">
              <tr style="background:#e0e7ff;color:#1e40af;font-weight:bold"><th style="padding:1px;border:1px solid #1e40af">DIA</th><th style="padding:1px;border:1px solid #1e40af">MES</th><th style="padding:1px;border:1px solid #1e40af">AÑO</th></tr>
              <tr style="font-size:9.5px;font-weight:bold;height:13px;color:#0f172a"><td style="border:1px solid #1e40af">27</td><td style="border:1px solid #1e40af">10</td><td style="border:1px solid #1e40af">2026</td></tr>
            </table>
          </td>
        </tr></table>
      </div>
      <div style="border:1.5px solid #1e40af;border-radius:4px;padding:2px 4px;font-size:9px;margin-bottom:3px;line-height:1.2">
        <table style="width:100%;border:none;font-size:9px">
          <tr><td style="border:none;width:65%"><b style="color:#1e40af">CLIENTE:</b> <span>MAÑANA</span></td><td style="border:none;width:35%"><b style="color:#1e40af">NIT./ C.C.:</b> <span></span></td></tr>
          <tr><td style="border:none"><b style="color:#1e40af">DIRECCION:</b> <span>Álamos</span></td><td style="border:none"><b style="color:#1e40af">TEL.:</b> <span>3104957719</span></td></tr>
        </table>
      </div>
      <table style="width:100%;border:1.5px solid #1e40af;font-size:${fontSize}">
        <thead><tr style="background:#e0e7ff;color:#1e40af;font-size:${fontSize};font-weight:bold;text-align:center">
          <th style="width:6%;padding:${pad};border:1px solid #1e40af">#</th>
          <th style="width:16%;padding:${pad};border:1px solid #1e40af">CANT.</th>
          <th style="width:48%;padding:${pad};border:1px solid #1e40af;text-align:left">DESCRIPCION ARTICULO.</th>
          <th style="width:15%;padding:${pad};border:1px solid #1e40af;text-align:right">VR. UNIT</th>
          <th style="width:15%;padding:${pad};border:1px solid #1e40af;text-align:right">VR. TOTAL</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>
      <div style="margin-top:3px;page-break-inside:avoid">
        <table style="width:100%;border:none"><tr>
          <td style="border:none;font-size:9px;font-weight:bold;color:#1e40af;vertical-align:middle">RECIBI: <span style="display:inline-block;width:140px;border-bottom:1.5px solid #1e40af">&nbsp;</span></td>
          <td style="border:none;text-align:right;width:130px"><div style="border:1.5px solid #1e40af;padding:2px 4px;font-weight:bold;font-size:9.5px;background:#f8fafc"><span style="color:#1e40af">TOTAL $</span> &nbsp;</div></td>
        </tr></table>
      </div></body></html>`;

  fs.writeFileSync('./tmp_test.html', html);
  execSync('docker cp ./tmp_test.html fruvercopilot-backend:/tmp/tmp_test.html');
  execSync(`docker exec fruvercopilot-backend wkhtmltopdf -q -s A5 -B 2mm -T ${topMargin} -L 6mm -R 6mm /tmp/tmp_test.html /tmp/tmp_test.pdf`);
  execSync('docker exec fruvercopilot-backend gs -q -sDEVICE=png16m -o /tmp/test_p%03d.png /tmp/tmp_test.pdf');
  const pages = execSync('docker exec fruvercopilot-backend ls /tmp/test_p*.png').toString().trim().split('\n').filter(Boolean);
  execSync('docker exec fruvercopilot-backend rm -f /tmp/test_p*.png');
  console.log(`Test font=${fontSize}, pad=${pad}, top=${topMargin} => ${pages.length} page(s)`);
}

testLayout('8.5px', '1.2px 2px', '5mm');
testLayout('8.8px', '1.2px 2px', '5.5mm');
testLayout('9px', '1.3px 2px', '5.5mm');
testLayout('9px', '1.4px 2px', '5mm');
testLayout('9.2px', '1.5px 2px', '5mm');
testLayout('9.2px', '1.3px 2px', '5.5mm');

