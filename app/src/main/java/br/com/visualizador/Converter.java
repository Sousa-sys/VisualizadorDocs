package br.com.visualizador;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Converte documentos em HTML. Java puro: não depende do Android. */
public final class Converter {
    static final int MAX_BYTES = 40 * 1024 * 1024;
    static final int MAX_CHARS = 1_500_000;
    static final int MAX_ROWS = 3000;

    public static String toHtml(String name, byte[] data) throws Exception {
        String ext = extension(name);
        switch (ext) {
            case "docx": case "docm": case "dotx": return page(Office.docx(unzip(data)));
            case "xlsx": case "xlsm": return page(Office.xlsx(unzip(data)));
            case "pptx": case "ppsx": return page(Office.pptx(unzip(data)));
            case "odt": case "ods": case "odp": return page(Office.odf(unzip(data)));
            case "epub": return page(Office.epub(unzip(data)));
            case "zip": case "jar": case "apk": return page(listZip(data));
            case "doc": case "xls": case "ppt":
                throw new IOException("Formato antigo do Office (." + ext + ") não é suportado. Salve como ." + ext + "x.");
            case "html": case "htm": case "xhtml": return decode(data);
            case "svg": return page(image("image/svg+xml", data));
            case "png": case "gif": case "webp": case "bmp": return page(image("image/" + ext, data));
            case "jpg": case "jpeg": return page(image("image/jpeg", data));
            case "csv": return page(TextFormats.csv(decode(data), ','));
            case "tsv": return page(TextFormats.csv(decode(data), '\t'));
            case "md": case "markdown": return page(TextFormats.markdown(decode(data)));
            case "rtf": return page(pre(TextFormats.rtf(new String(data, StandardCharsets.ISO_8859_1))));
            default:
                if (looksBinary(data)) throw new IOException("Formato não reconhecido (arquivo binário).");
                return page(pre(decode(data)));
        }
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String page(String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
            + "body{font-family:sans-serif;margin:16px;line-height:1.5;color:#111;background:#fff;overflow-wrap:break-word}"
            + "img{max-width:100%;height:auto}pre{white-space:pre-wrap}"
            + ".t{overflow-x:auto}table{border-collapse:collapse;margin:8px 0}"
            + "td,th{border:1px solid #bbb;padding:4px 8px;vertical-align:top;overflow-wrap:normal}"
            + "section{border:1px solid #ddd;border-radius:8px;padding:8px 16px;margin-bottom:16px}"
            + "</style></head><body>" + body + "</body></html>";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String pre(String s) {
        boolean cut = s.length() > MAX_CHARS;
        return "<pre>" + esc(cut ? s.substring(0, MAX_CHARS) : s) + "</pre>"
            + (cut ? "<p><b>Arquivo grande: exibindo apenas o início.</b></p>" : "");
    }

    static String decode(byte[] d) {
        if (d.length >= 2 && ((d[0] == -1 && d[1] == -2) || (d[0] == -2 && d[1] == -1)))
            return new String(d, StandardCharsets.UTF_16);
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(d)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(d, Charset.forName("windows-1252"));
        }
    }

    static boolean looksBinary(byte[] d) {
        if (d.length >= 2 && ((d[0] == -1 && d[1] == -2) || (d[0] == -2 && d[1] == -1))) return false;
        for (int i = 0; i < Math.min(d.length, 4000); i++) if (d[i] == 0) return true;
        return false;
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (out.size() + n > limit) throw new IOException("Arquivo grande demais para este visualizador.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Descompacta em memória, com limite total contra "zip bombs". */
    static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        int left = MAX_BYTES * 2;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                byte[] b = readAll(z, left);
                left -= b.length;
                files.put(e.getName(), b);
            }
        }
        if (files.isEmpty()) throw new IOException("Arquivo corrompido ou protegido por senha.");
        return files;
    }

    static String listZip(byte[] data) throws IOException {
        StringBuilder h = new StringBuilder("<h3>Conteúdo do arquivo compactado</h3><ul>");
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            int count = 0;
            for (ZipEntry e; (e = z.getNextEntry()) != null && count < MAX_ROWS; count++)
                h.append("<li>").append(esc(e.getName())).append("</li>");
        }
        return h.append("</ul>").toString();
    }

    static String image(String mime, byte[] data) {
        return "<img alt=\"\" src=\"data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data) + "\">";
    }

    static Element xml(byte[] data) throws Exception {
        if (data == null) throw new IOException("Documento incompleto ou inválido.");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((p, s) -> new InputSource(new StringReader("")));
        return b.parse(new ByteArrayInputStream(data)).getDocumentElement();
    }

    static List<Element> children(Node n) {
        List<Element> out = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element) out.add((Element) c);
        return out;
    }

    static List<Element> all(Element root, String tag) {
        NodeList l = root.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(l.getLength());
        for (int i = 0; i < l.getLength(); i++) out.add((Element) l.item(i));
        return out;
    }
}
