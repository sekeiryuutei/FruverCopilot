package com.fruver.infrastructure.printing;

import com.fruver.application.service.TicketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class TalonarioRasterService {

    private static final Logger log = LoggerFactory.getLogger(TalonarioRasterService.class);
    private static final DecimalFormat MONEY_FMT = new DecimalFormat("#,##0");

    public byte[] renderTicketToPwgRaster(TicketData ticket) throws IOException {
        String html = buildHtml(ticket);
        return convertHtmlToPwgRaster(html);
    }

    public byte[] renderTicketToA4CenteredPdf(TicketData ticket) throws IOException {
        String html = buildA4CenteredHtml(ticket);
        String id = UUID.randomUUID().toString();
        File tmpHtml = new File("/tmp/talonario_pdf_" + id + ".html");
        File tmpPdf = new File("/tmp/talonario_pdf_" + id + ".pdf");

        try {
            Files.writeString(tmpHtml.toPath(), html);

            ProcessBuilder pbPdf = new ProcessBuilder(
                    "wkhtmltopdf", "-q", "-s", "A4",
                    "-B", "6mm", "-T", "0", "-L", "0", "-R", "0",
                    tmpHtml.getAbsolutePath(), tmpPdf.getAbsolutePath()
            );
            pbPdf.redirectErrorStream(true);
            Process p1 = pbPdf.start();
            int exit1 = p1.waitFor();
            if (exit1 != 0) {
                throw new IOException("wkhtmltopdf falló con código " + exit1);
            }

            return Files.readAllBytes(tmpPdf.toPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversión a PDF interrumpida", e);
        } finally {
            if (tmpHtml.exists()) tmpHtml.delete();
            if (tmpPdf.exists()) tmpPdf.delete();
        }
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

    public String buildA4CenteredHtml(TicketData t) {
        String customer = (t.customer() != null ? t.customer() : "CLIENTE GENERAL").toUpperCase();
        String rawPhone = (t.phone() != null ? t.phone() : "").trim();
        String phone = rawPhone.replaceFirst("^\\+?57", "");
        if (phone.length() > 10 || !phone.matches("\\d{7,10}")) {
            phone = "__________________";
        }
        String address = (t.address() != null && !t.address().isBlank()) ? t.address().trim().toUpperCase() : "________________________________________________";
        String originInfo = "";
        if (t.inputType() != null && !t.inputType().isBlank() && !"TEXT".equalsIgnoreCase(t.inputType())) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            String upper = t.inputType().toUpperCase();
            if (upper.contains("AUDIO")) parts.add("&#127908; AUDIO");
            if (upper.contains("IMAGE") || upper.contains("IMAGEN")) parts.add("&#128247; IMAGEN");
            if (upper.contains("TEXT") || upper.contains("TEXTO")) parts.add("&#128221; TEXTO");
            if (!parts.isEmpty()) {
                originInfo = "<b>ORIGEN:</b> " + String.join(" + ", parts);
            }
        }
        String orderNum = String.format("%06d", t.orderNumber());

        var dt = t.at();
        String day = dt != null ? String.format("%02d", dt.getDayOfMonth()) : "";
        String month = dt != null ? String.format("%02d", dt.getMonthValue()) : "";
        String year = dt != null ? String.valueOf(dt.getYear() % 100) : "";

        List<TicketData.Line> lines = t.lines() != null ? t.lines() : List.of();
        int maxPerPage = 22;
        int pagesCount = Math.max(1, (int) Math.ceil((double) lines.size() / maxPerPage));

        StringBuilder pagesHtml = new StringBuilder();

        for (int p = 0; p < pagesCount; p++) {
            int from = p * maxPerPage;
            int to = Math.min(lines.size(), (p + 1) * maxPerPage);
            List<TicketData.Line> pageLines = lines.subList(from, to);
            boolean isFirstPage = (p == 0);
            boolean isLastPage = (p == pagesCount - 1);

            StringBuilder rowsHtml = new StringBuilder();
            for (int i = 0; i < pageLines.size(); i++) {
                int globalIdx = from + i + 1;
                TicketData.Line l = pageLines.get(i);
                String name = l.name() != null ? l.name().toUpperCase() : "";

                // Regla de negocio: pedidos por valor en dinero (ej: "10.000 de cebolla")
                boolean isMoneyOrder = isMoneyOrderLine(l);

                String cant;
                String vrUnit = "&nbsp;";
                String vrTotal = "&nbsp;";

                if (isMoneyOrder) {
                    cant = ""; // Casilla en blanco para escribir cantidad pesada a mano en bodega
                    BigDecimal val = l.subtotal() != null && l.subtotal().signum() > 0 ? l.subtotal() :
                                     (l.price() != null && l.price().signum() > 0 ? l.price() : l.quantity());
                    if (val != null && val.signum() > 0) {
                        vrTotal = "$ " + MONEY_FMT.format(val);
                    }
                } else {
                    String u = (l.unit() != null && !l.unit().isBlank() && !l.unit().equalsIgnoreCase("NO_UNIT")) ? (" " + l.unit()) : "";
                    cant = (l.quantity() != null ? l.quantity().stripTrailingZeros().toPlainString() : "") + u;
                    if (l.price() != null && l.price().signum() > 0) {
                        vrUnit = "$" + MONEY_FMT.format(l.price());
                    }
                    if (l.subtotal() != null && l.subtotal().signum() > 0) {
                        vrTotal = "$" + MONEY_FMT.format(l.subtotal());
                    }
                }

                rowsHtml.append("<tr style=\"height:27px\">\n")
                        .append("  <td style=\"text-align:center;font-weight:bold;padding:3px 2px;border:1.5px solid #000;font-size:12.5px;color:#111\">").append(globalIdx).append("</td>\n")
                        .append("  <td style=\"text-align:center;font-weight:900;padding:3px 3px;border:1.5px solid #000;font-size:15px\">").append(cant).append("</td>\n")
                        .append("  <td style=\"padding:3px 6px;border:1.5px solid #000;text-transform:uppercase;font-size:14px;font-weight:900;white-space:nowrap;overflow:hidden\">").append(name).append("</td>\n")
                        .append("  <td style=\"text-align:right;padding:3px 4px;border:1.5px solid #000;font-size:12.5px;font-weight:bold\">").append(vrUnit).append("</td>\n")
                        .append("  <td style=\"text-align:right;padding:3px 5px;border:1.5px solid #000;font-size:14px;font-weight:900\">").append(vrTotal).append("</td>\n")
                        .append("</tr>\n");
            }

            String headerHtml;
            if (isFirstPage) {
                headerHtml = """
                    <div style="border-bottom:2px solid #000;padding-bottom:6px;margin-bottom:7px">
                      <table style="width:100%;border-collapse:collapse">
                        <tr>
                          <td style="vertical-align:middle">
                            <div style="font-size:27px;font-weight:900;letter-spacing:0.3px;color:#000;line-height:1.1">FRUVER EL OASIS</div>
                            <div style="font-size:12px;font-weight:bold;color:#222;margin-top:3px">Frutas y Verduras al por Mayor &bull; TEL: 3128947392</div>
                          </td>
                          <td style="text-align:right;vertical-align:middle">
                            <div style="display:inline-block;padding:6px 11px;border:2px solid #000;border-radius:3px;font-weight:900;font-size:14px;color:#000;margin-right:6px">
                              FACTURA N° {{ORDER_NUM}} <span style="font-size:11px;color:#444">(1/{{PAGES_COUNT}})</span>
                            </div>
                            <table style="display:inline-table;border-collapse:collapse;border:1.5px solid #000;font-size:10.5px;text-align:center;width:108px;vertical-align:middle">
                              <tr style="background:#e5e7eb;color:#000;font-weight:bold">
                                <th style="padding:2px;border:1px solid #000">D</th>
                                <th style="padding:2px;border:1px solid #000">M</th>
                                <th style="padding:2px;border:1px solid #000">A</th>
                              </tr>
                              <tr style="font-size:11.5px;font-weight:bold;height:20px;color:#000">
                                <td style="border:1px solid #000">{{DAY}}</td>
                                <td style="border:1px solid #000">{{MONTH}}</td>
                                <td style="border:1px solid #000">{{YEAR}}</td>
                              </tr>
                            </table>
                          </td>
                        </tr>
                      </table>
                    </div>
                    <div style="border:2px solid #000;border-radius:3px;padding:6px 10px;font-size:13px;margin-bottom:6px;line-height:1.35">
                      <table style="width:100%;border-collapse:collapse;font-size:13px">
                        <tr>
                          <td style="width:65%"><b>CLIENTE:</b> <span style="font-size:14px;font-weight:900">{{CUSTOMER}}</span></td>
                          <td style="width:35%"><b>TEL:</b> <span style="font-weight:bold">{{PHONE}}</span></td>
                        </tr>
                        <tr>
                          <td style="width:65%;padding-top:3px"><b>DIR:</b> <span style="font-weight:bold">{{ADDRESS}}</span></td>
                          <td style="width:35%;padding-top:3px;font-size:12px">{{ORIGIN_INFO}}</td>
                        </tr>
                      </table>
                    </div>
                    """
                    .replace("{{ORDER_NUM}}", orderNum)
                    .replace("{{PAGES_COUNT}}", String.valueOf(pagesCount))
                    .replace("{{DAY}}", day)
                    .replace("{{MONTH}}", month)
                    .replace("{{YEAR}}", year)
                    .replace("{{CUSTOMER}}", customer)
                    .replace("{{PHONE}}", phone)
                    .replace("{{ADDRESS}}", address)
                    .replace("{{ORIGIN_INFO}}", originInfo);
            } else {
                headerHtml = """
                    <div style="border-bottom:2px solid #000;padding-bottom:5px;margin-bottom:7px">
                      <table style="width:100%;border-collapse:collapse">
                        <tr>
                          <td style="vertical-align:middle">
                            <span style="font-size:21px;font-weight:900;color:#000">FRUVER EL OASIS</span>
                            <span style="font-size:12px;color:#333;margin-left:8px;font-weight:bold">— Continuación Pedido &bull; TEL: 3128947392</span>
                          </td>
                          <td style="text-align:right;vertical-align:middle">
                            <div style="display:inline-block;padding:4px 10px;border:2px solid #000;border-radius:3px;font-weight:900;font-size:13px;color:#000">
                              FACTURA N° {{ORDER_NUM}} (Pág {{P_CURR}}/{{PAGES_COUNT}})
                            </div>
                          </td>
                        </tr>
                      </table>
                    </div>
                    """
                    .replace("{{ORDER_NUM}}", orderNum)
                    .replace("{{P_CURR}}", String.valueOf(p + 1))
                    .replace("{{PAGES_COUNT}}", String.valueOf(pagesCount));
            }

            String footerHtml = "";
            if (isLastPage) {
                // Regla estricta: Solo si el cliente pidió TODO por precios se imprime el total general
                boolean allMoneyOrders = !lines.isEmpty() && lines.stream().allMatch(this::isMoneyOrderLine);
                String totalStr = "&nbsp;";
                if (allMoneyOrders && t.total() != null && t.total().signum() > 0) {
                    totalStr = "$" + MONEY_FMT.format(t.total());
                } else if (allMoneyOrders) {
                    BigDecimal sum = lines.stream()
                            .map(line -> line.subtotal() != null ? line.subtotal() : (line.price() != null ? line.price() : BigDecimal.ZERO))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (sum.signum() > 0) totalStr = "$" + MONEY_FMT.format(sum);
                }
                footerHtml = """
                    <div style="margin-top:9px;display:flex;justify-content:flex-end;align-items:center">
                      <div style="width:40%;text-align:right">
                        <table style="width:100%;border-collapse:collapse;border:2px solid #000;font-size:14px;font-weight:bold">
                          <tr style="background:#e5e7eb;color:#000">
                            <td style="padding:6px 8px;border:1px solid #000;font-size:13px">TOTAL $</td>
                            <td style="padding:6px 8px;border:1px solid #000;min-width:85px">{{TOTAL_STR}}</td>
                          </tr>
                        </table>
                      </div>
                    </div>
                    """.replace("{{TOTAL_STR}}", totalStr);
            }

            String breakStyle = (p < pagesCount - 1) ? "page-break-after: always;" : "";
            pagesHtml.append("""
                <div class="a4-sheet" style="{{BREAK_STYLE}}">
                  <div class="a5-invoice-container">
                    {{HEADER_HTML}}
                    <table class="items-table" style="width:100%;border-collapse:collapse;border:2px solid #000">
                      <thead>
                        <tr style="background:#e5e7eb;color:#000;font-size:12.5px;font-weight:900;height:27px">
                          <th style="padding:3px;border:1.5px solid #000;width:6%;text-align:center">#</th>
                          <th style="padding:3px;border:1.5px solid #000;width:18%;text-align:center">CANT.</th>
                          <th style="padding:3px 5px;border:1.5px solid #000;width:38%;text-align:left">DESCRIPCIÓN</th>
                          <th style="padding:3px 5px;border:1.5px solid #000;width:19%;text-align:right">VR. UNIT</th>
                          <th style="padding:3px 5px;border:1.5px solid #000;width:19%;text-align:right">VR. TOTAL</th>
                        </tr>
                      </thead>
                      <tbody>
                        {{ROWS_HTML}}
                      </tbody>
                    </table>
                    {{FOOTER_HTML}}
                  </div>
                </div>
                """
                .replace("{{BREAK_STYLE}}", breakStyle)
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
            {{PAGES_HTML}}
            </body>
            </html>
            """.replace("{{PAGES_HTML}}", pagesHtml.toString());
    }

    private boolean isMoneyOrderLine(TicketData.Line l) {
        if (l == null) return false;
        String u = l.unit() != null ? l.unit().trim().toUpperCase() : "";
        if (u.equals("VALOR") || u.equals("PESOS") || u.equals("COP") || u.equals("$") || u.equals("DINERO") || u.equals("PLATA")) {
            return true;
        }
        if (l.quantity() == null && ((l.subtotal() != null && l.subtotal().signum() > 0) || (l.price() != null && l.price().signum() > 0))) {
            return true;
        }
        // Si cantidad es >= 1000 sin unidad de peso/unidad típica (ej: "10.000 de cebolla")
        if (l.quantity() != null && l.quantity().compareTo(new BigDecimal("1000")) >= 0
                && (u.isBlank() || !List.of("LB", "LBS", "KG", "KGS", "KL", "KLS", "UN", "UND", "UNIDAD", "UNIDADES", "CAJA", "CAJAS", "CJ", "BULTO", "BULTOS", "BL", "ARROBA", "@", "ATADO", "BOLSA", "PAQUETE", "MANOJO").contains(u))) {
            return true;
        }
        return false;
    }

    private String buildHtml(TicketData t) {
        return buildA4CenteredHtml(t);
    }
}
