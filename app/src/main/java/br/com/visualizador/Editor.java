package br.com.visualizador;

import static br.com.visualizador.Converter.*;

import java.io.*;
import java.nio.charset.*;
import java.time.LocalDate;
import java.time.format.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Edição preservando o arquivo: só o que foi alterado é regravado. Java puro. */
public final class Editor {
    static final int MAX_TEXT = 200_000;
    static final int MAX_FIELDS = 20_000;

    public static final class Field {
        public final String label;
        public final String original;
        public String value;
        private final Consumer<String> apply;
        private final String part;   // arquivo XML dentro do ZIP
        Field(String label, String value, String part, Consumer<String> apply) {
            this.label = label; this.original = value; this.value = value;
            this.part = part; this.apply = apply;
        }
    }

    /** true: um único campo com o arquivo inteiro. */
    public final boolean plainText;
    public final List<Field> fields = new ArrayList<>();
    private Map<String, byte[]> zip;
    private final Map<String, Element> parts = new HashMap<>();
    private Charset charset;
    private byte[] bom = {};
    private boolean crlf;
    private final Set<Field> touched = new HashSet<>();

    public static boolean supports(String name) {
        return isText(name) || Arrays.asList("docx", "docm", "xlsx", "xlsm", "pptx", "odt", "ods", "odp")
            .contains(extension(name));
    }

    private static boolean isText(String name) {
        return Arrays.asList("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "xhtml", "svg",
            "log", "ini", "yml", "yaml", "properties", "java", "kt", "py", "js", "ts", "c", "h", "cpp", "css",
            "sql", "sh", "gradle", "toml", "conf").contains(extension(name));
    }

    public Editor(String name, byte[] data) throws Exception {
        if (!supports(name)) throw new IOException("Este formato só pode ser visualizado.");
        plainText = isText(name);
        if (plainText) { openText(data); return; }
        zip = unzip(data);
        String ext = extension(name);
        if (ext.startsWith("doc")) paragraphs("word/document.xml", "w:p", "w:t", "Parágrafo ");
        else if (ext.equals("pptx")) {
            Map<String, String> rels = Office.rels(zip, "ppt/_rels/presentation.xml.rels");
            int slide = 0;
            for (Element id : all(xml(zip.get("ppt/presentation.xml")), "p:sldId"))
                paragraphs(Office.resolve("ppt/", rels.get(id.getAttribute("r:id"))), "a:p", "a:t",
                    "Slide " + (++slide) + ", trecho ");
        } else if (ext.startsWith("xls")) sheets();
        else {
            int[] n = {0};
            for (Element body : all(part("content.xml"), "office:body")) odf(body, n);
        }
        if (fields.isEmpty()) throw new IOException("Não há texto editável neste documento.");
    }

    private void add(String label, String value, String part, Consumer<String> apply) {
        if (fields.size() >= MAX_FIELDS)
            throw new IllegalStateException("Documento grande demais para editar neste aparelho.");
        fields.add(new Field(label, value, part, apply));
    }

    private Element part(String path) throws Exception {
        Element e = parts.get(path);
        if (e == null) parts.put(path, e = xml(zip.get(path)));
        return e;
    }

    // ---------- Texto puro ----------
    private void openText(byte[] data) throws IOException {
        if (data.length > MAX_TEXT * 3 || looksBinary(data))
            throw new IOException("Arquivo grande demais (ou binário) para editar.");
        if (data.length >= 2 && data[0] == -1 && data[1] == -2) { charset = StandardCharsets.UTF_16LE; bom = new byte[] {-1, -2}; }
        else if (data.length >= 2 && data[0] == -2 && data[1] == -1) { charset = StandardCharsets.UTF_16BE; bom = new byte[] {-2, -1}; }
        else {
            if (data.length >= 3 && data[0] == -17 && data[1] == -69 && data[2] == -65) bom = new byte[] {-17, -69, -65};
            try {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data));
                charset = StandardCharsets.UTF_8;
            } catch (CharacterCodingException e) {
                charset = Charset.forName("windows-1252");
            }
        }
        String s = new String(data, bom.length, data.length - bom.length, charset);
        if (s.length() > MAX_TEXT) throw new IOException("Arquivo grande demais para editar.");
        crlf = s.contains("\r\n");
        add("Texto", s.replace("\r\n", "\n"), null, v -> {});
    }

    // ---------- Word e PowerPoint ----------
    private void paragraphs(String path, String pTag, String tTag, String label) throws Exception {
        int n = 0;
        for (Element p : all(part(path), pTag)) {
            List<Element> texts = new ArrayList<>();
            for (Element t : all(p, tTag)) {
                Node a = t.getParentNode();
                while (a != p && !a.getNodeName().equals(pTag)) a = a.getParentNode();
                if (a == p) texts.add(t);
            }
            if (texts.isEmpty()) continue;
            n++;
            // Com tabulação ou quebra de linha, edita trecho a trecho para não deslocá-las.
            boolean split = all(p, "w:tab").stream().anyMatch(e -> e.getParentNode().getNodeName().equals("w:r"))
                || all(p, "w:br").size() > 0 || all(p, "a:br").size() > 0;
            if (split) {
                for (Element t : texts) add(label + n, t.getTextContent(), path, v -> set(t, v));
            } else {
                StringBuilder all = new StringBuilder();
                for (Element t : texts) all.append(t.getTextContent());
                add(label + n, all.toString(), path, v -> {
                    for (int i = 0; i < texts.size(); i++) set(texts.get(i), i == 0 ? v : "");
                });
            }
        }
    }

    private static void set(Element t, String v) {
        t.setTextContent(v);
        if (t.getTagName().equals("w:t")) t.setAttribute("xml:space", "preserve");
    }

    // ---------- Excel ----------
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);

    private void sheets() throws Exception {
        List<String> shared = new ArrayList<>();
        if (zip.containsKey("xl/sharedStrings.xml"))
            for (Element si : all(part("xl/sharedStrings.xml"), "si")) shared.add(Office.cellText(si));
        BitSet dates = Office.dateStyles(zip), percents = Office.percentStyles(zip);
        Map<String, String> rels = Office.rels(zip, "xl/_rels/workbook.xml.rels");
        for (Element sheet : all(part("xl/workbook.xml"), "sheet")) {
            String path = Office.resolve("xl/", rels.get(sheet.getAttribute("r:id")));
            if (!zip.containsKey(path)) continue;
            for (Element c : all(part(path), "c")) {
                String type = c.getAttribute("t"), value = "";
                boolean formula = false;
                for (Element v : children(c)) {
                    if (v.getTagName().equals("f")) formula = true;
                    if (v.getTagName().equals("v")) value = v.getTextContent();
                    if (v.getTagName().equals("is")) value = Office.cellText(v);
                }
                // Fórmulas, erros e booleanos não são editáveis.
                if (formula || value.isEmpty() || type.equals("b") || type.equals("e")) continue;
                if (type.equals("s")) value = shared.get(Integer.parseInt(value));
                boolean numeric = type.isEmpty() || type.equals("n");
                boolean date = numeric && dates.get(Office.number(c.getAttribute("s"))) && value.matches("\\d{1,6}");
                boolean percent = numeric && !date && percents.get(Office.number(c.getAttribute("s")));
                if (percent) value = Office.percent(value).replace('.', ',');
                else if (date) value = EPOCH.plusDays(Long.parseLong(value)).format(DATE);
                else if (numeric) value = value.replace('.', ',');
                add(sheet.getAttribute("name") + "!" + c.getAttribute("r"), value, path,
                    v -> setCell(c, v, numeric, date, percent));
            }
        }
    }

    /** Aceita "1234,5", "1.234,50" e "1234.5"; devolve null se não for número. */
    static String parseNumber(String s) {
        s = s.trim();
        if (s.matches("-?\\d{1,3}(\\.\\d{3})+(,\\d+)?")) s = s.replace(".", "");
        s = s.replace(',', '.');
        return s.matches("-?\\d+(\\.\\d+)?([eE]-?\\d+)?") ? s : null;
    }

    private static void setCell(Element c, String text, boolean numeric, boolean date, boolean percent) {
        String number = numeric ? parseNumber(text) : null;
        if (percent) {
            String n = parseNumber(text.replace("%", ""));
            number = n == null ? null : new java.math.BigDecimal(n).movePointLeft(2).toPlainString();
        }
        if (date) {
            try { number = String.valueOf(ChronoUnit.DAYS.between(EPOCH, LocalDate.parse(text.trim(), DATE))); }
            catch (DateTimeParseException e) { number = null; }
        }
        while (c.getFirstChild() != null) c.removeChild(c.getFirstChild());
        Document d = c.getOwnerDocument();
        if (number != null) {
            c.removeAttribute("t");
            c.appendChild(d.createElement("v")).setTextContent(number);
        } else if (!text.isEmpty()) {
            c.setAttribute("t", "inlineStr");
            Element t = d.createElement("t");
            t.setAttribute("xml:space", "preserve");
            t.setTextContent(text);
            c.appendChild(d.createElement("is")).appendChild(t);
        } else c.removeAttribute("t");
    }

    /** Pede ao Excel que recalcule as fórmulas ao abrir. */
    private void recalculate() {
        Element book = parts.get("xl/workbook.xml");
        if (book == null) return;
        List<Element> found = all(book, "calcPr");
        Element calc = found.isEmpty() ? book.getOwnerDocument().createElement("calcPr") : found.get(0);
        if (found.isEmpty()) {
            Node after = null;
            for (Element e : children(book))
                if (e.getTagName().matches("sheets|functionGroups|externalReferences|definedNames")) after = e;
            book.insertBefore(calc, after == null ? null : after.getNextSibling());
        }
        calc.setAttribute("fullCalcOnLoad", "1");
    }

    // ---------- OpenDocument ----------
    private void odf(Node node, int[] n) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            String tag = c.getNodeName();
            if (tag.equals("office:annotation") || tag.equals("text:tracked-changes")) continue;
            if (c instanceof Element) {
                Element e = (Element) c;
                if (e.hasAttribute("table:formula") || !expand(e)) continue;
            }
            if (c.getNodeType() == Node.TEXT_NODE && !c.getNodeValue().trim().isEmpty()) {
                Node text = c;
                add("Trecho " + (++n[0]), c.getNodeValue(), "content.xml", v -> {
                    text.setNodeValue(v);
                    retype(text, v);
                });
            } else odf(c, n);
        }
    }

    /**
     * Linhas e células iguais são gravadas uma só vez com um contador. Separa as cópias para que a
     * edição de uma não altere as outras. Devolve false se forem cópias demais para editar.
     */
    private static boolean expand(Element e) {
        String attr = e.getTagName().equals("table:table-cell") ? "table:number-columns-repeated"
            : e.getTagName().equals("table:table-row") ? "table:number-rows-repeated" : null;
        if (attr == null || !e.hasAttribute(attr) || e.getTextContent().trim().isEmpty()) return true;
        int times = Office.number(e.getAttribute(attr));
        if (times < 1 || times > 50) return false;
        e.removeAttribute(attr);
        for (int i = 1; i < times; i++) e.getParentNode().insertBefore(e.cloneNode(true), e.getNextSibling());
        return true;
    }

    /** Células numéricas guardam o valor também em atributo: mantém os dois coerentes. */
    private static void retype(Node text, String v) {
        Node a = text.getParentNode();
        while (a != null && !a.getNodeName().equals("table:table-cell")) a = a.getParentNode();
        if (a == null) return;
        Element cell = (Element) a;
        String type = cell.getAttribute("office:value-type");
        if (type.isEmpty() || type.equals("string")) return;
        String number = null, date = null;
        if (type.equals("float")) number = parseNumber(v);
        else if (type.equals("currency")) number = parseNumber(v.replaceAll("[^0-9.,\\-]", ""));
        else if (type.equals("percentage")) {
            String n = parseNumber(v.replace("%", ""));
            if (n != null) number = new java.math.BigDecimal(n).movePointLeft(2).toPlainString();
        } else if (type.equals("date")) date = parseDate(v);
        cell.removeAttribute("calcext:value-type");
        if (number != null) cell.setAttribute("office:value", number);
        else if (date != null) cell.setAttribute("office:date-value", date);
        else {
            for (String attr : new String[] {"office:value", "office:date-value", "office:time-value",
                    "office:boolean-value", "office:currency"}) cell.removeAttribute(attr);
            cell.setAttribute("office:value-type", "string");
        }
    }

    private static String parseDate(String s) {
        for (DateTimeFormatter f : new DateTimeFormatter[] {DATE, DateTimeFormatter.ISO_LOCAL_DATE}) {
            try { return LocalDate.parse(s.trim(), f).toString(); }
            catch (DateTimeParseException e) { /* tenta o próximo formato */ }
        }
        return null;
    }

    /** Remove caracteres que tornariam o XML inválido. */
    static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 0xD7FF)
            || (c >= 0xE000 && c <= 0xFFFD) || c >= 0x10000).forEach(out::appendCodePoint);
        return out.toString();
    }

    // ---------- Gravação ----------
    public boolean changed() {
        for (Field f : fields) if (!f.value.equals(f.original)) return true;
        return false;
    }

    /** Pode ser chamado mais de uma vez (ex.: se o usuário cancelar a escolha do local). */
    public byte[] save() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (plainText) {
            String s = fields.get(0).value;
            if (crlf) s = s.replace("\n", "\r\n");
            if (!charset.newEncoder().canEncode(s)) charset = StandardCharsets.UTF_8;
            out.write(bom);
            out.write(s.getBytes(charset));
            return out.toByteArray();
        }
        Set<String> dirty = new HashSet<>();
        for (Field f : fields)
            if (!f.value.equals(f.original) || touched.contains(f)) {
                f.apply.accept(clean(f.value));
                touched.add(f);
                dirty.add(f.part);
            }
        if (!dirty.isEmpty() && zip.containsKey("xl/workbook.xml")) { recalculate(); dirty.add("xl/workbook.xml"); }
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : zip.entrySet()) {
                byte[] bytes = entry.getValue();
                if (dirty.contains(entry.getKey())) {
                    ByteArrayOutputStream x = new ByteArrayOutputStream();
                    t.transform(new DOMSource(parts.get(entry.getKey()).getOwnerDocument()), new StreamResult(x));
                    bytes = x.toByteArray();
                }
                ZipEntry ze = new ZipEntry(entry.getKey());
                if (entry.getKey().equals("mimetype")) { // OpenDocument exige sem compressão
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(bytes.length);
                    ze.setCrc(crc.getValue());
                }
                z.putNextEntry(ze);
                z.write(bytes);
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
