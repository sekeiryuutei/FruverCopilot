package com.fruver.infrastructure.printing;

import com.fruver.application.service.TicketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class TalonarioRasterService {

    private static final Logger log = LoggerFactory.getLogger(TalonarioRasterService.class);

    public byte[] renderTicketToPwgRaster(TicketData ticket) throws IOException {
        String html = buildHtml(ticket);
        return convertHtmlToPwgRaster(html);
    }

    public byte[] renderTestPageToPwgRaster(String location, String host, int port) throws IOException {
        String nowStr = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <style>
              @page { size: A5 portrait; margin: 6mm 8mm; }
              body { font-family: Arial, sans-serif; margin: 0; padding: 0; text-align: center; }
              .box { border: 2px solid #1e40af; border-radius: 6px; padding: 12px; margin-top: 15px; }
            </style>
            </head>
            <body>
              <div style="font-size: 22px; font-weight: 900; color: #1e40af;">FRUVER EL OASIS</div>
              <div style="font-size: 13px; font-weight: bold; color: #475569; margin-top: 3px;">PRUEBA DE IMPRESIÓN LÁSER IPP</div>
              <div class="box">
                <table style="width: 100%; font-size: 12px; text-align: left; line-height: 1.8;">
                  <tr><td><b>Destino:</b></td><td>%s</td></tr>
                  <tr><td><b>Servidor:</b></td><td>%s:%d</td></tr>
                  <tr><td><b>Fecha y Hora:</b></td><td>%s</td></tr>
                  <tr><td><b>Formato:</b></td><td>PWG-Raster A5 (Alta Resolución)</td></tr>
                  <tr><td><b>Estado:</b></td><td><span style="color:#16a34a; font-weight:bold;">✓ CONEXIÓN IPP EXITOSA</span></td></tr>
                </table>
              </div>
              <div style="margin-top: 25px; font-size: 11px; color: #64748b;">
                Impresora Brother HL-L2405W configurada correctamente en el sistema.
              </div>
            </body>
            </html>
            """.formatted(location, host, port, nowStr);

        return convertHtmlToPwgRaster(html);
    }

    public byte[] convertHtmlToPwgRaster(String html) throws IOException {
        String id = UUID.randomUUID().toString();
        File tmpHtml = new File("/tmp/talonario_" + id + ".html");
        File tmpPdf = new File("/tmp/talonario_" + id + ".pdf");
        File tmpPwg = new File("/tmp/talonario_" + id + ".pwg");

        try {
            Files.writeString(tmpHtml.toPath(), html);

            // 1. Convertir HTML a PDF A5
            ProcessBuilder pbPdf = new ProcessBuilder(
                    "wkhtmltopdf", "-q", "-s", "A5",
                    "-B", "4mm", "-T", "4mm", "-L", "6mm", "-R", "6mm",
                    tmpHtml.getAbsolutePath(), tmpPdf.getAbsolutePath()
            );
            pbPdf.redirectErrorStream(true);
            Process p1 = pbPdf.start();
            int exit1 = p1.waitFor();
            if (exit1 != 0) {
                throw new IOException("wkhtmltopdf falló con código " + exit1);
            }

            // 2. Convertir PDF a PWG Raster A5 a 300 DPI
            ProcessBuilder pbPwg = new ProcessBuilder(
                    "gs", "-q", "-dNOPAUSE", "-dBATCH",
                    "-sDEVICE=pwgraster", "-r300", "-sPAPERSIZE=a5",
                    "-sOutputFile=" + tmpPwg.getAbsolutePath(),
                    tmpPdf.getAbsolutePath()
            );
            pbPwg.redirectErrorStream(true);
            Process p2 = pbPwg.start();
            int exit2 = p2.waitFor();
            if (exit2 != 0) {
                throw new IOException("Ghostscript falló con código " + exit2);
            }

            return Files.readAllBytes(tmpPwg.toPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversión a PWG interrumpida", e);
        } finally {
            if (tmpHtml.exists()) tmpHtml.delete();
            if (tmpPdf.exists()) tmpPdf.delete();
            if (tmpPwg.exists()) tmpPwg.delete();
        }
    }

    private String buildHtml(TicketData t) {
        String customer = (t.customer() != null ? t.customer() : "CLIENTE GENERAL").toUpperCase();
        String rawPhone = (t.phone() != null ? t.phone() : "").trim();
        String phone = rawPhone.replaceFirst("^\\+?57", "");
        String address = t.address() != null ? t.address() : "";
        String notes = t.notes() != null ? t.notes() : "";
        String orderNum = String.format("%06d", t.orderNumber());

        var dt = t.at();
        String day = dt != null ? String.format("%02d", dt.getDayOfMonth()) : "";
        String month = dt != null ? String.format("%02d", dt.getMonthValue()) : "";
        String year = dt != null ? String.valueOf(dt.getYear()) : "";

        List<TicketData.Line> lines = t.lines() != null ? t.lines() : List.of();
        int targetRows = lines.size() <= 16 ? 16 : lines.size();
        boolean compact = lines.size() > 13;
        String fontSize = compact ? "9.5px" : "10.5px";
        String pad = compact ? "1.5px 3px" : "3px 4px";
        String descPad = compact ? "1.5px 5px" : "3px 6px";

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < targetRows; i++) {
            if (i < lines.size()) {
                TicketData.Line l = lines.get(i);
                String cant = (l.quantity() != null ? l.quantity().stripTrailingZeros().toPlainString() : "") +
                        (l.unit() != null && !l.unit().isBlank() ? " " + l.unit() : "");
                String name = l.name() != null ? l.name().toUpperCase() : "";
                rows.append("<tr>")
                    .append("<td style=\"text-align:center;font-weight:bold;padding:").append(pad).append(";border:1px solid #1e40af;font-size:").append(fontSize).append(";color:#475569\">").append(i + 1).append("</td>")
                    .append("<td style=\"text-align:center;font-weight:bold;padding:").append(pad).append(";border:1px solid #1e40af;font-size:").append(fontSize).append("\">").append(cant).append("</td>")
                    .append("<td style=\"padding:").append(descPad).append(";border:1px solid #1e40af;font-size:").append(fontSize).append("\">").append(name).append("</td>")
                    .append("<td style=\"text-align:right;padding:").append(pad).append(";border:1px solid #1e40af;font-size:").append(fontSize).append("\">&nbsp;</td>")
                    .append("<td style=\"text-align:right;padding:").append(pad).append(";border:1px solid #1e40af;font-weight:bold;font-size:").append(fontSize).append("\">&nbsp;</td>")
                    .append("</tr>\n");
            } else {
                rows.append("<tr>")
                    .append("<td style=\"height:15px;text-align:center;color:#94a3b8;font-size:").append(fontSize).append(";border:1px solid #1e40af\">").append(i + 1).append("</td>")
                    .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                    .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                    .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                    .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                    .append("</tr>\n");
            }
        }

        String template = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <style>
              @page { size: A5 portrait; margin: 4mm 6mm 4mm 6mm; }
              body { font-family: Arial, sans-serif; margin: 0; padding: 0; color: #000; }
              table { width: 100%; border-collapse: collapse; }
              thead { display: table-header-group; }
              tr { page-break-inside: avoid; break-inside: avoid; }
            </style>
            </head>
            <body>
              <!-- Encabezado superior -->
              <div style="border-bottom:1.5px solid #1e40af;padding-bottom:3px;margin-bottom:3px">
                <table style="width:100%;border:none">
                  <tr>
                    <td style="border:none;vertical-align:middle">
                      <div style="font-size:17px;font-weight:900;letter-spacing:0.5px;color:#1e40af;line-height:1.05">FRUVER EL OASIS</div>
                      <div style="font-size:10px;font-weight:bold;color:#334155;margin-top:1px">Frutas y Verduras al por Mayor</div>
                    </td>
                    <td style="border:none;text-align:right;vertical-align:middle">
                      <div style="display:inline-block;padding:2px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11px;color:#1e40af;margin-right:5px">
                        FACTURA / PEDIDO N° {{ORDER_NUM}}
                      </div>
                      <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #1e40af;font-size:8px;text-align:center;width:85px;vertical-align:middle">
                        <tr style="background:#e0e7ff;color:#1e40af;font-weight:bold">
                          <th style="padding:1px;border:1px solid #1e40af;width:33%">DIA</th>
                          <th style="padding:1px;border:1px solid #1e40af;width:33%">MES</th>
                          <th style="padding:1px;border:1px solid #1e40af;width:34%">AÑO</th>
                        </tr>
                        <tr style="font-size:10px;font-weight:bold;height:14px;color:#0f172a">
                          <td style="border:1px solid #1e40af">{{DAY}}</td>
                          <td style="border:1px solid #1e40af">{{MONTH}}</td>
                          <td style="border:1px solid #1e40af">{{YEAR}}</td>
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
                    <td style="border:none;width:65%"><b style="color:#1e40af">CLIENTE:</b> <span>{{CUSTOMER}}</span></td>
                    <td style="border:none;width:35%"><b style="color:#1e40af">NIT./ C.C.:</b> <span></span></td>
                  </tr>
                  <tr>
                    <td style="border:none"><b style="color:#1e40af">DIRECCION:</b> <span>{{ADDRESS}}</span></td>
                    <td style="border:none"><b style="color:#1e40af">TEL.:</b> <span>{{PHONE}}</span></td>
                  </tr>
                  {{OBS_ROW}}
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
                  {{ROWS}}
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
            </html>
            """;

        String obsRow = notes.isBlank() ? "" : "<tr><td colspan=\"2\" style=\"border:none;font-size:9px;color:#475569\"><b style=\"color:#1e40af\">OBS:</b> " + notes + "</td></tr>";

        return template
                .replace("{{ORDER_NUM}}", orderNum)
                .replace("{{DAY}}", day)
                .replace("{{MONTH}}", month)
                .replace("{{YEAR}}", year)
                .replace("{{CUSTOMER}}", customer)
                .replace("{{ADDRESS}}", address)
                .replace("{{PHONE}}", phone)
                .replace("{{OBS_ROW}}", obsRow)
                .replace("{{ROWS}}", rows.toString());
    }
}
