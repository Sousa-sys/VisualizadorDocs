package br.com.visualizador;

import static br.com.visualizador.Converter.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.*;

final class TextFormats {

    static String csv(String s, char sep) {
        // Planilhas em português costumam usar ';'.
        int end = s.indexOf('\n');
        String first = end < 0 ? s : s.substring(0, end);
        if (sep == ',' && count(first, ';') > count(first, ',')) sep = ';';
        StringBuilder h = new StringBuilder("<div class=t><table><tr>"), cell = new StringBuilder();
        boolean quoted = false, open = true;
        int rows = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!open) {
                if (++rows >= MAX_ROWS) { h.append("<tr><td>… arquivo truncado</td></tr>"); break; }
                h.append("<tr>");
                open = true;
            }
            if (quoted) {
                if (c != '"') cell.append(c);
                else if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = false;
            } else if (c == '"' && cell.length() == 0) quoted = true;
            else if (c == sep) flush(h, cell);
            else if (c == '\n') { flush(h, cell); h.append("</tr>"); open = false; }
            else if (c != '\r') cell.append(c);
        }
        if (open && rows < MAX_ROWS) { flush(h, cell); h.append("</tr>"); }
        return h.append("</table></div>").toString();
    }

    private static long count(String s, char c) { return s.chars().filter(x -> x == c).count(); }

    private static void flush(StringBuilder h, StringBuilder cell) {
        h.append("<td>").append(esc(cell.toString())).append("</td>");
        cell.setLength(0);
    }

    /** Subconjunto de Markdown: títulos, listas, código, negrito e itálico. */
    static String markdown(String s) {
        StringBuilder h = new StringBuilder();
        boolean code = false, list = false;
        for (String line : s.split("\r?\n")) {
            if (line.startsWith("```")) {
                if (list) { h.append("</ul>"); list = false; }
                h.append(code ? "</pre>" : "<pre>");
                code = !code;
                continue;
            }
            if (code) { h.append(esc(line)).append('\n'); continue; }
            boolean item = line.matches("\\s*[-*+] .*");
            if (item != list) { h.append(item ? "<ul>" : "</ul>"); list = item; }
            int level = 0;
            while (level < line.length() && line.charAt(level) == '#') level++;
            if (item) h.append("<li>").append(inline(line.replaceFirst("\\s*[-*+] ", ""))).append("</li>");
            else if (level > 0 && level <= 6 && line.startsWith(" ", level))
                h.append("<h" + level + ">").append(inline(line.substring(level + 1))).append("</h" + level + ">");
            else if (line.trim().isEmpty()) h.append("<p></p>");
            else h.append(inline(line)).append('\n');
        }
        if (list) h.append("</ul>");
        if (code) h.append("</pre>");
        return h.toString();
    }

    private static String inline(String s) {
        return esc(s).replaceAll("`([^`]+)`", "<code>$1</code>")
            .replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>")
            .replaceAll("\\*([^*]+)\\*", "<i>$1</i>");
    }

    /** Extrai o texto de um RTF (sem a formatação). */
    static String rtf(String s) {
        StringBuilder out = new StringBuilder();
        Deque<Boolean> stack = new ArrayDeque<>();
        Set<String> hidden = new HashSet<>(Arrays.asList("fonttbl", "colortbl", "stylesheet", "info", "pict",
            "header", "footer", "listtable", "listoverridetable", "themedata", "datastore"));
        boolean skip = false;
        int fallback = 0; // caracteres a ignorar após \\uN
        Charset ansi = Charset.forName("windows-1252");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') stack.push(skip);
            else if (c == '}') { if (!stack.isEmpty()) skip = stack.pop(); }
            else if (c == '\r' || c == '\n') continue;
            else if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == '\'' && i + 2 < s.length()) {
                    String hex = s.substring(i + 1, i + 3);
                    i += 2;
                    if (fallback > 0) fallback--;
                    else if (!skip && hex.matches("[0-9a-fA-F]{2}"))
                        out.append(new String(new byte[] {(byte) Integer.parseInt(hex, 16)}, ansi));
                } else if (n == '*') skip = true;
                else if (!Character.isLetter(n)) { if (!skip) out.append(n == '~' ? ' ' : n); }
                else {
                    int j = i;
                    while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                    String word = s.substring(i, j);
                    int k = j;
                    if (k < s.length() && s.charAt(k) == '-') k++;
                    while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                    String arg = s.substring(j, k);
                    i = (k < s.length() && s.charAt(k) == ' ') ? k : k - 1;
                    if (hidden.contains(word)) skip = true;
                    else if (skip) continue;
                    else if (word.equals("par") || word.equals("line") || word.equals("row")) out.append('\n');
                    else if (word.equals("tab") || word.equals("cell")) out.append('\t');
                    else if (word.equals("u") && arg.matches("-?\\d{1,6}")) {
                        out.append((char) Integer.parseInt(arg));
                        fallback = 1;
                    }
                }
            } else if (fallback > 0) fallback--;
            else if (!skip) out.append(c);
        }
        return out.toString();
    }
}
