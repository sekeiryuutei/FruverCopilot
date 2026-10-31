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

            // 1. Convertir HTML a PDF A5 con margen superior de 6mm y margen inferior reducido de 3mm
            ProcessBuilder pbPdf = new ProcessBuilder(
                    "wkhtmltopdf", "-q", "-s", "A5",
                    "-B", "3mm", "-T", "6mm", "-L", "7mm", "-R", "7mm",
                    tmpHtml.getAbsolutePath(), tmpPdf.getAbsolutePath()
            );
            pbPdf.redirectErrorStream(true);
            Process p1 = pbPdf.start();
            int exit1 = p1.waitFor();
            if (exit1 != 0) {
                throw new IOException("wkhtmltopdf falló con código " + exit1);
            }

            // 2. Convertir PDF a PWG Raster A5 a 600 DPI con espacio de color sgray_8 (cupsColorSpace=18)
            // La impresora Brother HL-L2405W exige exactamente sgray_8 (18) a 600 DPI; de lo contrario aborta con document-format-error.
            ProcessBuilder pbPwg = new ProcessBuilder(
                    "gs", "-q", "-dNOPAUSE", "-dBATCH",
                    "-sDEVICE=pwgraster",
                    "-dcupsColorSpace=18",
                    "-dcupsBitsPerColor=8",
                    "-sPAPERSIZE=a5",
                    "-r600",
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
        int maxPerPage = 35;
        int pagesCount = Math.max(1, (int) Math.ceil((double) lines.size() / maxPerPage));

        StringBuilder pagesHtml = new StringBuilder();

        for (int p = 0; p < pagesCount; p++) {
            int from, to;
            if (pagesCount == 2 && lines.size() > 35 && lines.size() <= 40) {
                int half = (int) Math.ceil((double) lines.size() / 2);
                from = p * half;
                to = Math.min(lines.size(), (p + 1) * half);
            } else {
                from = p * maxPerPage;
                to = Math.min(lines.size(), (p + 1) * maxPerPage);
            }
            List<TicketData.Line> pageLines = lines.subList(from, to);
            boolean isFirstPage = (p == 0);
            boolean isLastPage = (p == pagesCount - 1);

            int targetRows = (pagesCount == 1 && pageLines.size() <= 16) ? 16 : pageLines.size();

            StringBuilder rowsHtml = new StringBuilder();
            for (int i = 0; i < targetRows; i++) {
                int globalIdx = from + i + 1;
                if (i < pageLines.size()) {
                    TicketData.Line l = pageLines.get(i);
                    String cant = (l.quantity() != null ? l.quantity().stripTrailingZeros().toPlainString() : "") +
                            (l.unit() != null && !l.unit().isBlank() ? " " + l.unit() : "");
                    String name = l.name() != null ? l.name().toUpperCase() : "";
                    rowsHtml.append("<tr>")
                        .append("<td style=\"text-align:center;font-weight:bold;padding:4.5px 3px;border:1px solid #1e40af;font-size:11px;color:#475569\">").append(globalIdx).append("</td>")
                        .append("<td style=\"text-align:center;font-weight:bold;padding:4.5px 3px;border:1px solid #1e40af;font-size:11px\">").append(cant).append("</td>")
                        .append("<td style=\"padding:4.5px 5px;border:1px solid #1e40af;text-transform:uppercase;font-size:11px;font-weight:normal\">").append(name).append("</td>")
                        .append("<td style=\"text-align:right;padding:4.5px 3px;border:1px solid #1e40af;font-size:11px\">&nbsp;</td>")
                        .append("<td style=\"text-align:right;padding:4.5px 3px;border:1px solid #1e40af;font-size:11px\">&nbsp;</td>")
                        .append("</tr>\n");
                } else {
                    rowsHtml.append("<tr>")
                        .append("<td style=\"height:20px;text-align:center;color:#94a3b8;font-size:11px;border:1px solid #1e40af\">").append(globalIdx).append("</td>")
                        .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                        .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                        .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                        .append("<td style=\"border:1px solid #1e40af\">&nbsp;</td>")
                        .append("</tr>\n");
                }
            }

            String headerHtml;
            if (isFirstPage) {
                String pageIndicator = pagesCount > 1 ? "<span style=\"font-size:9.5px;color:#64748b\">(Pág 1/" + pagesCount + ")</span>" : "";
                String obsRow = notes.isBlank() ? "" : "<tr><td colspan=\"2\" style=\"border:none;font-size:9px;color:#475569\"><b style=\"color:#1e40af\">OBS:</b> " + notes + "</td></tr>";
                headerHtml = """
                    <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:4px;padding-top:2px">
                      <table style="width:100%;border:none">
                        <tr>
                          <td style="border:none;vertical-align:middle">
                            <div style="font-size:18px;font-weight:900;letter-spacing:0.5px;color:#1e40af;line-height:1.05">FRUVER EL OASIS</div>
                            <div style="font-size:10px;font-weight:bold;color:#334155;margin-top:2px">Frutas y Verduras al por Mayor</div>
                          </td>
                          <td style="border:none;text-align:right;vertical-align:middle">
                            <div style="display:inline-block;padding:3px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11.5px;color:#1e40af;margin-right:5px">
                              FACTURA / PEDIDO N° {{ORDER_NUM}} {{PAGE_INDICATOR}}
                            </div>
                            <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #1e40af;font-size:8.5px;text-align:center;width:85px;vertical-align:middle">
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
                    <div style="border:1.5px solid #1e40af;border-radius:4px;padding:3px 6px;font-size:9.5px;margin-bottom:5px;line-height:1.25">
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
                    """
                    .replace("{{ORDER_NUM}}", orderNum)
                    .replace("{{PAGE_INDICATOR}}", pageIndicator)
                    .replace("{{DAY}}", day)
                    .replace("{{MONTH}}", month)
                    .replace("{{YEAR}}", year)
                    .replace("{{CUSTOMER}}", customer)
                    .replace("{{ADDRESS}}", address)
                    .replace("{{PHONE}}", phone)
                    .replace("{{OBS_ROW}}", obsRow);
            } else {
                headerHtml = ("""
                    <div style="border-bottom:2px solid #1e40af;padding-bottom:3px;margin-bottom:6px;padding-top:2px">
                      <table style="width:100%;border:none">
                        <tr>
                          <td style="border:none">
                            <span style="font-size:16px;font-weight:900;color:#1e40af">FRUVER EL OASIS</span>
                            <span style="font-size:10.5px;color:#475569;margin-left:8px">— Continuación Pedido</span>
                          </td>
                          <td style="border:none;text-align:right">
                            <div style="display:inline-block;padding:2px 7px;border:1.5px solid #1e40af;border-radius:4px;font-weight:bold;font-size:11px;color:#1e40af">
                              FACTURA N° {{ORDER_NUM}} (Pág {{CURR_PAGE}}/{{TOTAL_PAGES}})
                            </div>
                          </td>
                        </tr>
                      </table>
                    </div>
                    """)
                    .replace("{{ORDER_NUM}}", orderNum)
                    .replace("{{CURR_PAGE}}", String.valueOf(p + 1))
                    .replace("{{TOTAL_PAGES}}", String.valueOf(pagesCount));
            }

            String footerHtml;
            if (isLastPage) {
                footerHtml = """
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
                    </div>
                    """;
            } else {
                footerHtml = ("""
                    <div style="margin-top:6px;text-align:right;font-size:10px;font-weight:bold;color:#64748b">
                      Continúa en la siguiente página (Pág {{NEXT_PAGE}}/{{TOTAL_PAGES}}) ➔
                    </div>
                    """)
                    .replace("{{NEXT_PAGE}}", String.valueOf(p + 2))
                    .replace("{{TOTAL_PAGES}}", String.valueOf(pagesCount));
            }

            String pageBreak = (p < pagesCount - 1) ? "page-break-after: always; break-after: page;" : "";

            pagesHtml.append("""
                <div class="page-container" style="{{PAGE_BREAK}}">
                  {{HEADER_HTML}}
                  <table style="width:100%;border:1.5px solid #1e40af;font-size:11px">
                    <thead>
                      <tr style="background:#e0e7ff;color:#1e40af;font-size:10px;font-weight:bold;text-align:center">
                        <th style="width:7%;padding:4px 2px;border:1px solid #1e40af">#</th>
                        <th style="width:17%;padding:4px 2px;border:1px solid #1e40af">CANT.</th>
                        <th style="width:38%;padding:4px 4px;border:1px solid #1e40af;text-align:left">DESCRIPCION ARTICULO.</th>
                        <th style="width:19%;padding:4px 2px;border:1px solid #1e40af;text-align:right">VR. UNIT</th>
                        <th style="width:19%;padding:4px 2px;border:1px solid #1e40af;text-align:right">VR. TOTAL</th>
                      </tr>
                    </thead>
                    <tbody>
                      {{ROWS_HTML}}
                    </tbody>
                  </table>
                  {{FOOTER_HTML}}
                </div>
                """
                .replace("{{PAGE_BREAK}}", pageBreak)
                .replace("{{HEADER_HTML}}", headerHtml)
                .replace("{{ROWS_HTML}}", rowsHtml.toString())
                .replace("{{FOOTER_HTML}}", footerHtml));
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <style>
              @page { size: A5 portrait; margin: 6mm 7mm 3mm 7mm; }
              body { font-family: Arial, sans-serif; margin: 0; padding: 0; color: #000; background: #fff; }
              table { width: 100%; border-collapse: collapse; }
              tr { page-break-inside: avoid; break-inside: avoid; }
              .page-container { width: 100%; box-sizing: border-box; }
            </style>
            </head>
            <body>
              {{PAGES_HTML}}
            </body>
            </html>
            """.replace("{{PAGES_HTML}}", pagesHtml.toString());
    }
}
