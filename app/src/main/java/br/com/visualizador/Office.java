package br.com.visualizador;

import static br.com.visualizador.Converter.*;

import java.net.URI;
import java.util.*;
import org.w3c.dom.*;

/** Formatos baseados em ZIP + XML: Office, OpenDocument e EPUB. */
final class Office {

    // ---------- Word ----------
    static String docx(Map<String, byte[]> z) throws Exception {
        Map<String, String> rels = rels(z, "word/_rels/document.xml.rels");
        StringBuilder h = new StringBuilder();
        for (Element body : all(xml(z.get("word/document.xml")), "w:body")) word(body, h, z, rels);
        return h.toString();
    }

    private static void word(Element node, StringBuilder h, Map<String, byte[]> z, Map<String, String> rels) {
        for (Element e : children(node)) {
            switch (e.getTagName()) {
                case "w:p": {
                    String tag = "p";
                    for (Element s : all(e, "w:pStyle")) {
                        String v = s.getAttribute("w:val").toLowerCase(Locale.ROOT);
                        if (v.matches("(heading|ttulo|titulo|título)[1-6]")) tag = "h" + v.charAt(v.length() - 1);
                        else if (v.equals("title")) tag = "h1";
                    }
                    boolean list = all(e, "w:numPr").size() > 0;
                    h.append('<').append(tag).append('>').append(list ? "• " : "");
                    word(e, h, z, rels);
                    h.append("</").append(tag).append('>');
                    break;
                }
                case "w:r": {
                    String open = "", close = "";
                    for (Element pr : children(e)) {
                        if (!pr.getTagName().equals("w:rPr")) continue;
                        for (Element f : children(pr)) {
                            String v = f.getAttribute("w:val");
                            if (v.equals("0") || v.equals("false") || v.equals("none")) continue;
                            String t = f.getTagName().equals("w:b") ? "b" : f.getTagName().equals("w:i") ? "i"
                                : f.getTagName().equals("w:u") ? "u" : null;
                            if (t != null) { open += "<" + t + ">"; close = "</" + t + ">" + close; }
                        }
                    }
                    h.append(open);
                    word(e, h, z, rels);
                    h.append(close);
                    break;
                }
                case "w:t": h.append(esc(e.getTextContent())); break;
                case "w:tab": h.append("&emsp;"); break;
                case "w:br": h.append("<br>"); break;
                case "w:tbl": h.append("<div class=t><table>"); word(e, h, z, rels); h.append("</table></div>"); break;
                case "w:tr": h.append("<tr>"); word(e, h, z, rels); h.append("</tr>"); break;
                case "w:tc": h.append("<td>"); word(e, h, z, rels); h.append("</td>"); break;
                case "a:blip": picture(z, "word/", rels.get(e.getAttribute("r:embed")), h); break;
                case "w:pPr": case "w:rPr": case "w:del": case "w:instrText": case "mc:Fallback": break;
                default: word(e, h, z, rels);
            }
        }
    }

    // ---------- Excel ----------
    static String xlsx(Map<String, byte[]> z) throws Exception {
        List<String> shared = new ArrayList<>();
        if (z.containsKey("xl/sharedStrings.xml"))
            for (Element si : all(xml(z.get("xl/sharedStrings.xml")), "si")) shared.add(cellText(si));
        BitSet dates = dateStyles(z), percents = percentStyles(z);
        Map<String, String> rels = rels(z, "xl/_rels/workbook.xml.rels");
        StringBuilder h = new StringBuilder();
        for (Element sheet : all(xml(z.get("xl/workbook.xml")), "sheet")) {
            h.append("<h3>").append(esc(sheet.getAttribute("name"))).append("</h3><div class=t><table>");
            byte[] data = z.get(resolve("xl/", rels.get(sheet.getAttribute("r:id"))));
            if (data != null) {
                int rows = 0;
                for (Element row : all(xml(data), "row")) {
                    if (rows++ == MAX_ROWS || h.length() > MAX_CHARS * 4) { h.append("<tr><td>… planilha truncada</td></tr>"); break; }
                    h.append("<tr><th>").append(esc(row.getAttribute("r"))).append("</th>");
                    int col = 0;
                    for (Element c : children(row)) {
                        int at = column(c.getAttribute("r"), col);
                        while (col < at) { h.append("<td></td>"); col++; }
                        String type = c.getAttribute("t"), value = "";
                        for (Element v : children(c)) {
                            if (v.getTagName().equals("v")) value = v.getTextContent();
                            if (v.getTagName().equals("is")) value = cellText(v);
                        }
                        if (type.equals("s") && !value.isEmpty()) value = shared.get(Integer.parseInt(value));
                        else if (type.equals("b")) value = value.equals("1") ? "VERDADEIRO" : "FALSO";
                        else if ((type.isEmpty() || type.equals("n")) && !value.isEmpty() && dates.get(number(c.getAttribute("s"))))
                            value = date(Double.parseDouble(value));
                        else if ((type.isEmpty() || type.equals("n")) && percents.get(number(c.getAttribute("s"))))
                            value = percent(value);
                        h.append("<td>").append(esc(value)).append("</td>");
                        col++;
                    }
                    h.append("</tr>");
                }
            }
            h.append("</table></div>");
        }
        return h.toString();
    }

    static String cellText(Element e) {
        StringBuilder s = new StringBuilder();
        for (Element c : children(e)) {
            if (c.getTagName().equals("t")) s.append(c.getTextContent());
            else if (c.getTagName().equals("r")) s.append(cellText(c));
        }
        return s.toString();
    }

    private static int column(String ref, int fallback) {
        int n = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) n = n * 26 + ref.charAt(i) - 'A' + 1;
        return n == 0 ? fallback : Math.min(n - 1, 16383);
    }

    static int number(String s) { return s.matches("\\d{1,6}") ? Integer.parseInt(s) : 0; }

    /** Índices de estilo que usam os formatos de data embutidos ou personalizados. */
    static BitSet dateStyles(Map<String, byte[]> z) throws Exception {
        BitSet out = new BitSet();
        if (!z.containsKey("xl/styles.xml")) return out;
        Element root = xml(z.get("xl/styles.xml"));
        Set<Integer> ids = new HashSet<>(Arrays.asList(14, 15, 16, 17, 22));
        for (Element f : all(root, "numFmt")) {
            String code = f.getAttribute("formatCode").replaceAll("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\.", "").toLowerCase(Locale.ROOT);
            if (code.contains("y") || code.contains("d")) ids.add(number(f.getAttribute("numFmtId")));
        }
        for (Element xfs : all(root, "cellXfs")) {
            int i = 0;
            for (Element xf : children(xfs)) out.set(i++, ids.contains(number(xf.getAttribute("numFmtId"))));
        }
        return out;
    }

    /** Índices de estilo com formato de porcentagem. */
    static BitSet percentStyles(Map<String, byte[]> z) throws Exception {
        BitSet out = new BitSet();
        if (!z.containsKey("xl/styles.xml")) return out;
        Element root = xml(z.get("xl/styles.xml"));
        Set<Integer> ids = new HashSet<>(Arrays.asList(9, 10));
        for (Element f : all(root, "numFmt"))
            if (f.getAttribute("formatCode").replaceAll("\"[^\"]*\"|\\\\.", "").contains("%"))
                ids.add(number(f.getAttribute("numFmtId")));
        for (Element xfs : all(root, "cellXfs")) {
            int i = 0;
            for (Element xf : children(xfs)) out.set(i++, ids.contains(number(xf.getAttribute("numFmtId"))));
        }
        return out;
    }

    static String percent(String value) {
        try { return new java.math.BigDecimal(value).movePointRight(2).stripTrailingZeros().toPlainString() + "%"; }
        catch (NumberFormatException e) { return value; }
    }

    private static String date(double serial) {
        if (!(serial >= 0 && serial <= 2958465)) return String.valueOf(serial);
        return java.time.LocalDate.of(1899, 12, 30).plusDays((long) serial)
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    // ---------- PowerPoint ----------
    static String pptx(Map<String, byte[]> z) throws Exception {
        Map<String, String> rels = rels(z, "ppt/_rels/presentation.xml.rels");
        StringBuilder h = new StringBuilder();
        int n = 1;
        for (Element id : all(xml(z.get("ppt/presentation.xml")), "p:sldId")) {
            String path = resolve("ppt/", rels.get(id.getAttribute("r:id")));
            Element slide = xml(z.get(path));
            h.append("<section><small>Slide ").append(n++).append("</small>");
            for (Element p : all(slide, "a:p")) {
                StringBuilder text = new StringBuilder();
                for (Element t : all(p, "a:t")) text.append(t.getTextContent());
                if (text.length() > 0) h.append("<p>").append(esc(text.toString())).append("</p>");
            }
            int slash = path.lastIndexOf('/') + 1;
            String dir = path.substring(0, slash);
            Map<String, String> sr = rels(z, dir + "_rels/" + path.substring(slash) + ".rels");
            for (Element blip : all(slide, "a:blip")) picture(z, dir, sr.get(blip.getAttribute("r:embed")), h);
            h.append("</section>");
        }
        return h.toString();
    }

    // ---------- OpenDocument ----------
    static String odf(Map<String, byte[]> z) throws Exception {
        StringBuilder h = new StringBuilder();
        for (Element body : all(xml(z.get("content.xml")), "office:body")) odfNode(body, h, z);
        return h.toString();
    }

    private static void odfNode(Node node, StringBuilder h, Map<String, byte[]> z) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.TEXT_NODE) { h.append(esc(c.getNodeValue())); continue; }
            if (!(c instanceof Element)) continue;
            Element e = (Element) c;
            String open = "", close = "";
            switch (e.getTagName()) {
                case "text:h": open = "<h3>"; close = "</h3>"; break;
                case "text:p": open = "<p>"; close = "</p>"; break;
                case "text:list": open = "<ul>"; close = "</ul>"; break;
                case "text:list-item": open = "<li>"; close = "</li>"; break;
                case "text:line-break": open = "<br>"; break;
                case "text:tab": open = "&emsp;"; break;
                case "text:s": open = " "; break;
                case "table:table":
                    open = "<h4>" + esc(e.getAttribute("table:name")) + "</h4><div class=t><table>"; close = "</table></div>"; break;
                case "table:table-row": open = "<tr>"; close = "</tr>"; break;
                case "table:table-cell": {
                    // Células repetidas: limita para não gerar milhares de colunas vazias.
                    String r = e.getAttribute("table:number-columns-repeated");
                    int times = r.matches("\\d{1,6}") ? Integer.parseInt(r) : 1;
                    if (e.getFirstChild() == null && e.getNextSibling() == null) continue;
                    for (int i = 0; i < Math.min(times, 50); i++) { h.append("<td>"); odfNode(e, h, z); h.append("</td>"); }
                    continue;
                }
                case "draw:page": open = "<section>"; close = "</section>"; break;
                case "draw:image": picture(z, "", e.getAttribute("xlink:href"), h); continue;
                case "office:annotation": case "text:tracked-changes": continue;
            }
            if (h.length() > MAX_CHARS * 4) return;
            h.append(open);
            odfNode(e, h, z);
            h.append(close);
        }
    }

    // ---------- EPUB ----------
    static String epub(Map<String, byte[]> z) throws Exception {
        String opf = all(xml(z.get("META-INF/container.xml")), "rootfile").get(0).getAttribute("full-path");
        String base = opf.substring(0, opf.lastIndexOf('/') + 1);
        Element root = xml(z.get(opf));
        Map<String, String> items = new HashMap<>();
        for (Element i : all(root, "item")) items.put(i.getAttribute("id"), i.getAttribute("href"));
        StringBuilder h = new StringBuilder();
        for (Element ref : all(root, "itemref")) {
            String path = resolve(base, items.get(ref.getAttribute("idref")));
            byte[] data = z.get(path);
            if (data == null) continue;
            String s = decode(data);
            java.util.regex.Matcher body = java.util.regex.Pattern.compile("(?is)<body[^>]*>(.*)</body>").matcher(s);
            if (!body.find()) continue;
            h.append(inlineImages(body.group(1), z, path.substring(0, path.lastIndexOf('/') + 1))).append("<hr>");
            if (h.length() > MAX_CHARS * 8) { h.append("<p><b>Livro grande: exibindo apenas o início.</b></p>"); break; }
        }
        return h.toString();
    }

    private static String inlineImages(String html, Map<String, byte[]> z, String dir) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(<img\\b[^>]*?\\bsrc=\")([^\"]+)\"").matcher(html);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String path = resolve(dir, m.group(2));
            byte[] b = z.get(path);
            String src = b == null ? "" : "data:" + mime(path) + ";base64," + Base64.getEncoder().encodeToString(b);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(1) + src + "\""));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------- Utilitários ----------
    static Map<String, String> rels(Map<String, byte[]> z, String path) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (z.containsKey(path))
            for (Element r : all(xml(z.get(path)), "Relationship")) m.put(r.getAttribute("Id"), r.getAttribute("Target"));
        return m;
    }

    static String resolve(String base, String target) {
        if (target == null) return "";
        try {
            String path = URI.create("file:///" + base.replace(" ", "%20")).resolve(target.replace(" ", "%20")).getPath();
            return path.substring(1);
        } catch (IllegalArgumentException e) {
            return base + target;
        }
    }

    private static String mime(String path) {
        String e = extension(path);
        return e.equals("svg") ? "image/svg+xml" : e.equals("jpg") ? "image/jpeg" : "image/" + e;
    }

    private static void picture(Map<String, byte[]> z, String base, String target, StringBuilder h) {
        String path = resolve(base, target);
        byte[] b = z.get(path);
        if (b != null) h.append(image(mime(path), b));
    }
}
