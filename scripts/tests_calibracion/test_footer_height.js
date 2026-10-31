const fs = require('fs');
const { execSync } = require('child_process');

function testFullFooter(pad, fontSize) {
  const items = Array.from({length: 30}, (_, i) => ({
    cant: (i + 1) + ' LB',
    desc: 'PRODUCTO ' + (i + 1)
  }));

  let rows = '';
  for(let i=0; i<items.length; i++) {
    rows += `<tr>
      <td style="text-align:center;font-weight:bold;padding:${pad};border:1px solid #1e40af;font-size:${fontSize};color:#475569">${i+1}</td>
      <td style="text-align:center;font-weight:bold;padding:${pad};border:1px solid #1e40af;font-size:${fontSize}">${items[i].cant}</td>
      <td style="padding:${pad};border:1px solid #1e40af;text-transform:uppercase;font-size:${fontSize};font-weight:normal">${items[i].desc}</td>
      <td style="text-align:right;padding:${pad};border:1px solid #1e40af;font-size:${fontSize}">&nbsp;</td>
      <td style="text-align:right;padding:${pad};border:1px solid #1e40af;font-size:${fontSize}">&nbsp;</td>
    </tr>\n`;
  }

  const footer = `<div style="margin-top:5px;page-break-inside:avoid">
    <table style="width:100%;border:none"><tr>
      <td style="border:none;font-size:10px;font-weight:bold;color:#1e40af;vertical-align:middle">
        RECIBI: <span style="display:inline-block;width:150px;border-bottom:1.5px solid #1e40af">&nbsp;</span>
      </td>
      <td style="border:none;text-align:right;width:145px">
        <div style="border:1.5px solid #1e40af;padding:3px 6px;display:flex;justify-content:space-between;align-items:center;font-weight:bold;font-size:11px;background:#f8fafc">
          <span style="color:#1e40af">TOTAL $</span>
          <span style="font-size:11.5px;color:#0f172a">&nbsp;</span>
        </div>
      </td>
    </tr></table></div>`;

  const html = `<!DOCTYPE html><html><head><meta charset="utf-8">
    <style>
      @page { size: A5 portrait; margin: 6mm 7mm 3mm 7mm; }
      body { font-family: Arial, sans-serif; margin: 0; padding: 0; color: #000; background: #fff; }
      table { width: 100%; border-collapse: collapse; }
      tr { page-break-inside: avoid; break-inside: avoid; }
    </style></head><body>
      <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:4px;padding-top:2px">
        <table style="width:100%;border:none"><tr>
          <td style="border:none;vertical-align:middle">
            <div style="font-size:18px;font-weight:900;letter-spacing:0.5px;color:#1e40af;line-height:1.05">FRUVER EL OASIS</div>
            <div style="font-size:10px;font-weight:bold;color:#334155;margin-top:2px">Frutas y Verduras al por Mayor</div>
          </td>
          <td style="border:none;text-align:right;vertical-align:middle">
            <div style="display:inline-block;padding:3px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11.5px;color:#1e40af;margin-right:5px">FACTURA / PEDIDO N° 000006</div>
            <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #1e40af;font-size:8.5px;text-align:center;width:85px;vertical-align:middle">
              <tr style="background:#e0e7ff;color:#1e40af;font-weight:bold"><th style="padding:1px;border:1px solid #1e40af">DIA</th><th style="padding:1px;border:1px solid #1e40af">MES</th><th style="padding:1px;border:1px solid #1e40af">AÑO</th></tr>
              <tr style="font-size:10px;font-weight:bold;height:14px;color:#0f172a"><td style="border:1px solid #1e40af">27</td><td style="border:1px solid #1e40af">10</td><td style="border:1px solid #1e40af">2026</td></tr>
            </table>
          </td>
        </tr></table>
      </div>
      <div style="border:1.5px solid #1e40af;border-radius:4px;padding:3px 6px;font-size:9.5px;margin-bottom:5px;line-height:1.25">
        <table style="width:100%;border:none;font-size:9.5px">
          <tr><td style="border:none;width:65%"><b style="color:#1e40af">CLIENTE:</b> <span>MAÑANA</span></td><td style="border:none;width:35%"><b style="color:#1e40af">NIT./ C.C.:</b> <span></span></td></tr>
          <tr><td style="border:none"><b style="color:#1e40af">DIRECCION:</b> <span>Álamos</span></td><td style="border:none"><b style="color:#1e40af">TEL.:</b> <span>3104957719</span></td></tr>
        </table>
      </div>
      <table style="width:100%;border:1.5px solid #1e40af;font-size:${fontSize}">
        <thead><tr style="background:#e0e7ff;color:#1e40af;font-size:10px;font-weight:bold;text-align:center">
          <th style="width:7%;padding:3px 2px;border:1px solid #1e40af">#</th>
          <th style="width:17%;padding:3px 2px;border:1px solid #1e40af">CANT.</th>
          <th style="width:38%;padding:3px 4px;border:1px solid #1e40af;text-align:left">DESCRIPCION ARTICULO.</th>
          <th style="width:19%;padding:3px 2px;border:1px solid #1e40af;text-align:right">VR. UNIT</th>
          <th style="width:19%;padding:3px 2px;border:1px solid #1e40af;text-align:right">VR. TOTAL</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>
      ${footer}
    </body></html>`;

  fs.writeFileSync('./tmp_expand.html', html);
  execSync('docker cp ./tmp_expand.html fruvercopilot-backend:/tmp/tmp_expand.html');
  execSync('docker exec fruvercopilot-backend wkhtmltopdf -q -s A5 -B 3mm -T 6mm -L 7mm -R 7mm /tmp/tmp_expand.html /tmp/tmp_expand.pdf');
  execSync('docker exec fruvercopilot-backend gs -q -sDEVICE=png16m -r150 -o /tmp/exp_%03d.png /tmp/tmp_expand.pdf');
  const pages = execSync('docker exec fruvercopilot-backend bash -c "ls -1 /tmp/exp_*.png"').toString().trim().split('\n').filter(Boolean);
  console.log(`Full footer - Pad ${pad}, Font ${fontSize} => ${pages.length} page(s)`);
  execSync('docker exec fruvercopilot-backend bash -c "rm -f /tmp/exp_*.png"');
}

['4.5px 3px', '4.8px 3px', '5.0px 3px', '5.2px 3px', '5.5px 3px', '5.8px 3px', '6.0px 3px'].forEach(pad => {
  testFullFooter(pad, '11px');
});

