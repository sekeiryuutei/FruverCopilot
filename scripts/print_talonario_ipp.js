const fs = require('fs');
const { execSync } = require('child_process');
const ipp = require('ipp');

async function run() {
  console.log("Generando talonario para orden 6...");
  
  const items = [
    {cant: "1 LB", desc: "PARDA"},
    {cant: "25 LB", desc: "AMARILLA"},
    {cant: "20 LB", desc: "ZANAHORIA"},
    {cant: "8 LB", desc: "HABICHUELA"},
    {cant: "1 LB", desc: "CEBOLLA CABEZONA"},
    {cant: "10 LB", desc: "TOMATE DE ARBOL"},
    {cant: "15 LB", desc: "MARACUYA"},
    {cant: "6 LB", desc: "GUAYABA PERA"},
    {cant: "8 UN", desc: "PAPAYA"},
    {cant: "4 LB", desc: "PEPINO COHOMBRO"},
    {cant: "4 LB", desc: "PIMENTON ROJO"},
    {cant: "10 LB", desc: "LULO"},
    {cant: "3 LB", desc: "REMOLACHA"},
    {cant: "6 LB", desc: "MANDARINA"},
    {cant: "1 UN", desc: "AGUACATE HASS"},
    {cant: "10 LB", desc: "YUCA"},
    {cant: "4 LB", desc: "ARRACACHA"},
    {cant: "3 UN", desc: "MAZORCA"},
    {cant: "1 UN", desc: "GUINEO"},
    {cant: "1 LB", desc: "CEBOLLA LARGA"},
    {cant: "2 LB", desc: "BANANO"},
    {cant: "1 UN", desc: "ZAPALLO"},
    {cant: "2 UN", desc: "PLATANO VERDE"},
    {cant: "1 LB", desc: "TOMATE CHONTO"},
    {cant: "1 LB", desc: "ARVEJA VERDE EN VAINA"},
    {cant: "3 LB", desc: "FRIJOL VERDE DESGRANADO"},
    {cant: "10 LB", desc: "MORA"},
    {cant: "4 UN", desc: "PINA"},
    {cant: "30 LB", desc: "NARANJA VALENCIA"},
    {cant: "1 UN", desc: "APIO"},
    {cant: "2 UN", desc: "BROCOLI"},
    {cant: "1 UN", desc: "COLIFLOR"},
    {cant: "2 LB", desc: "CHAMPINON"},
    {cant: "2 LB", desc: "JENGIBRE"},
    {cant: "4 LB", desc: "BERENJENA"},
    {cant: "4 LB", desc: "SABILA"},
    {cant: "2 LB", desc: "SIDRA"},
    {cant: "3 UN", desc: "MAZORCA"},
    {cant: "1 UN", desc: "CILANTRO"},
    {cant: "1 LB", desc: "CIMARRON"},
    {cant: "2 UN", desc: "TOMILLO"},
    {cant: "2 UN", desc: "ESPINACA"},
    {cant: "1 UN", desc: "LECHUGA BATAVIA"},
    {cant: "2 LB", desc: "AJO"},
    {cant: "2 UN", desc: "COCO"},
    {cant: "6 LB", desc: "CEBOLLA CABEZONA ROJA"},
    {cant: "10 UN", desc: "PERA"},
    {cant: "6 UN", desc: "MANZANA ROJA"},
    {cant: "10 UN", desc: "MANZANA VERDE"},
    {cant: "4 LB", desc: "UVA ROJA"}
  ];

  let rows = '';
  for(let i=0; i<items.length; i++) {
    const it = items[i];
    rows += `<tr>
      <td style="text-align:center;font-weight:bold;padding:1.5px 3px;border:1px solid #1e40af;font-size:9.5px;color:#475569">${i+1}</td>
      <td style="text-align:center;font-weight:bold;padding:1.5px 3px;border:1px solid #1e40af;font-size:9.5px">${it.cant}</td>
      <td style="padding:1.5px 5px;border:1px solid #1e40af;text-transform:uppercase;font-size:9.5px">${it.desc}</td>
      <td style="text-align:right;padding:1.5px 3px;border:1px solid #1e40af;font-size:9.5px">&nbsp;</td>
      <td style="text-align:right;padding:1.5px 3px;border:1px solid #1e40af;font-weight:bold;font-size:9.5px">&nbsp;</td>
    </tr>`;
  }

  const html = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
  @page {
    size: A5 portrait;
    margin: 4mm 6mm 4mm 6mm;
  }
  body {
    font-family: Arial, sans-serif;
    margin: 0;
    padding: 0;
    color: #000;
  }
  table {
    width: 100%;
    border-collapse: collapse;
  }
  thead {
    display: table-header-group;
  }
  tr {
    page-break-inside: avoid;
    break-inside: avoid;
  }
</style>
</head>
<body>
  <!-- Encabezado superior compacto -->
  <div style="border-bottom:1.5px solid #1e40af;padding-bottom:3px;margin-bottom:3px">
    <table style="width:100%;border:none">
      <tr>
        <td style="border:none;vertical-align:middle">
          <div style="font-size:17px;font-weight:900;letter-spacing:0.5px;color:#1e40af;line-height:1.05">FRUVER EL OASIS</div>
          <div style="font-size:10px;font-weight:bold;color:#334155;margin-top:1px">Frutas y Verduras al por Mayor</div>
        </td>
        <td style="border:none;text-align:right;vertical-align:middle">
          <div style="display:inline-block;padding:2px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11px;color:#1e40af;margin-right:5px">
            FACTURA / PEDIDO N° 000006
          </div>
          <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #1e40af;font-size:8px;text-align:center;width:85px;vertical-align:middle">
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

  <!-- Recuadro Cliente -->
  <div style="border:1.5px solid #1e40af;border-radius:4px;padding:3px 5px;font-size:9.5px;margin-bottom:4px;line-height:1.25">
    <table style="width:100%;border:none;font-size:9.5px">
      <tr>
        <td style="border:none;width:65%"><b style="color:#1e40af">CLIENTE:</b> <span>MAÑANA</span></td>
        <td style="border:none;width:35%"><b style="color:#1e40af">NIT./ C.C.:</b> <span></span></td>
      </tr>
      <tr>
        <td style="border:none"><b style="color:#1e40af">DIRECCION:</b> <span>Álamos</span></td>
        <td style="border:none"><b style="color:#1e40af">TEL.:</b> <span>3104957719</span></td>
      </tr>
      <tr>
        <td colspan="2" style="border:none;font-size:9px;color:#475569"><b style="color:#1e40af">OBS:</b> Horario: para mañana | Pago: Efectivo</td>
      </tr>
    </table>
  </div>

  <!-- Tabla de Artículos -->
  <table style="width:100%;border:1.5px solid #1e40af;font-size:9.5px">
    <thead>
      <tr style="background:#e0e7ff;color:#1e40af;font-size:9px;font-weight:bold;text-align:center">
        <th style="width:6%;padding:1.5px 3px;border:1px solid #1e40af">#</th>
        <th style="width:16%;padding:1.5px 3px;border:1px solid #1e40af">CANT.</th>
        <th style="width:48%;padding:1.5px 5px;border:1px solid #1e40af;text-align:left">DESCRIPCION ARTICULO.</th>
        <th style="width:15%;padding:1.5px 3px;border:1px solid #1e40af;text-align:right">VR. UNIT</th>
        <th style="width:15%;padding:1.5px 3px;border:1px solid #1e40af;text-align:right">VR. TOTAL</th>
      </tr>
    </thead>
    <tbody>
      ${rows}
    </tbody>
  </table>

  <!-- Pie -->
  <div style="margin-top:5px;page-break-inside:avoid">
    <table style="width:100%;border:none">
      <tr>
        <td style="border:none;font-size:9.5px;font-weight:bold;color:#1e40af;vertical-align:middle">
          RECIBI: <span style="display:inline-block;width:150px;border-bottom:1.5px solid #1e40af">&nbsp;</span>
        </td>
        <td style="border:none;text-align:right;width:140px">
          <div style="border:1.5px solid #1e40af;padding:2px 5px;display:flex;justify-content:space-between;align-items:center;font-weight:bold;font-size:10px;background:#f8fafc">
            <span style="color:#1e40af">TOTAL $</span>
            <span style="font-size:11px;color:#0f172a">&nbsp;</span>
          </div>
        </td>
      </tr>
    </table>
  </div>
</body>
</html>`;

  fs.writeFileSync('./talonario_6.html', html);
  console.log("HTML guardado. Convirtiendo a PDF y PWG...");
  
  execSync('docker cp ./talonario_6.html fruvercopilot-backend:/tmp/talonario_6.html');
  execSync('docker exec fruvercopilot-backend wkhtmltopdf -s A5 -B 4mm -T 4mm -L 6mm -R 6mm /tmp/talonario_6.html /tmp/talonario_6.pdf');
  execSync('docker exec fruvercopilot-backend gs -q -dNOPAUSE -dBATCH -sDEVICE=pwgraster -r300 -sPAPERSIZE=a5 -sOutputFile=/tmp/talonario_6.pwg /tmp/talonario_6.pdf');
  execSync('docker cp fruvercopilot-backend:/tmp/talonario_6.pwg ./talonario_6.pwg');
  
  const pwgData = fs.readFileSync('./talonario_6.pwg');
  console.log("PWG generado: " + pwgData.length + " bytes. Enviando por IPP a 192.168.1.5:631...");
  
  const printer = ipp.Printer('http://192.168.1.5:631/ipp/print');
  const msg = {
    'operation-attributes-tag': {
      'requesting-user-name': 'FruverCopilot',
      'job-name': 'Talonario_Real_Pedido_6',
      'document-format': 'image/pwg-raster'
    },
    'job-attributes-tag': {
      'media': 'iso_a5_148x210mm',
      'print-quality': 'normal'
    },
    data: pwgData
  };
  
  printer.execute('Print-Job', msg, (err, res) => {
    if (err) {
      console.error("Error IPP:", err);
    } else {
      console.log("¡ÉXITO TOTAL!");
      console.log("Status:", res.statusCode);
      console.log("Job state:", res['job-attributes-tag']['job-state']);
    }
  });
}

run();
