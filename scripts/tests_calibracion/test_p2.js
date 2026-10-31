const fs = require('fs');
const { execSync } = require('child_process');

function testPage2(itemsCount, pad, fontSize) {
  const items = Array.from({length: itemsCount}, (_, i) => ({
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

  const html = `<!DOCTYPE html><html><head><meta charset="utf-8">
    <style>
      @page { size: A5 portrait; margin: 6mm 7mm 3mm 7mm; }
      body { font-family: Arial, sans-serif; margin: 0; padding: 0; color: #000; background: #fff; }
      table { width: 100%; border-collapse: collapse; }
    </style></head><body>
      <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:6px;padding-top:2px">
        <table style="width:100%;border:none"><tr>
          <td style="border:none">
            <span style="font-size:16px;font-weight:900;color:#1e40af">FRUVER EL OASIS</span>
            <span style="font-size:10.5px;color:#475569;margin-left:8px">— Continuación Pedido</span>
          </td>
          <td style="border:none;text-align:right">
            <div style="display:inline-block;padding:2px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11px;color:#1e40af">
              FACTURA N° 000006 (Pág 2/2)
            </div>
          </td>
        </tr></table>
      </div>
      <table style="width:100%;border:1.5px solid #1e40af;font-size:${fontSize}">
        <thead><tr style="background:#e0e7ff;color:#1e40af;font-size:10px;font-weight:bold;text-align:center">
          <th style="width:7%;padding:${pad};border:1px solid #1e40af">#</th>
          <th style="width:16%;padding:${pad};border:1px solid #1e40af">CANT.</th>
          <th style="width:47%;padding:${pad};border:1px solid #1e40af;text-align:left">DESCRIPCION ARTICULO.</th>
          <th style="width:15%;padding:${pad};border:1px solid #1e40af;text-align:right">VR. UNIT</th>
          <th style="width:15%;padding:${pad};border:1px solid #1e40af;text-align:right">VR. TOTAL</th>
        </tr></thead>
        <tbody>${rows}</tbody>
      </table>
      <div style="margin-top:6px;page-break-inside:avoid">
        <table style="width:100%;border:none"><tr>
          <td style="border:none;font-size:10.5px;font-weight:bold;color:#1e40af;vertical-align:middle">RECIBI: <span style="display:inline-block;width:150px;border-bottom:1.5px solid #1e40af">&nbsp;</span></td>
          <td style="border:none;text-align:right;width:145px"><div style="border:1.5px solid #1e40af;padding:3px 6px;font-weight:bold;font-size:11px;background:#f8fafc"><span style="color:#1e40af">TOTAL $</span> &nbsp;</div></td>
        </tr></table>
      </div>
    </body></html>`;

  fs.writeFileSync('./tmp_p2.html', html);
  execSync('docker cp ./tmp_p2.html fruvercopilot-backend:/tmp/tmp_p2.html');
  execSync('docker exec fruvercopilot-backend wkhtmltopdf -q -s A5 -B 3mm -T 6mm -L 7mm -R 7mm /tmp/tmp_p2.html /tmp/tmp_p2.pdf');
  execSync('docker exec fruvercopilot-backend gs -q -sDEVICE=png16m -r150 -o /tmp/p2_%03d.png /tmp/tmp_p2.pdf');
  const pages = execSync('docker exec fruvercopilot-backend bash -c "ls -1 /tmp/p2_*.png"').toString().trim().split('\n').filter(Boolean);
  console.log(`Page 2: ${itemsCount} items with pad ${pad}, font ${fontSize} => ${pages.length} page(s)`);
  execSync('docker exec fruvercopilot-backend bash -c "rm -f /tmp/p2_*.png"');
}

testPage2(20, '4.5px 3px', '11.5px');
testPage2(25, '4.5px 3px', '11.5px');
testPage2(30, '4.2px 3px', '11.5px');
testPage2(35, '3.8px 3px', '11px');

