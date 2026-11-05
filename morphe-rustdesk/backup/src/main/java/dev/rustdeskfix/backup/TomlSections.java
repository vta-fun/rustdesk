package dev.rustdeskfix.backup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and edits selected fields in the TOML emitted by RustDesk's confy writer. */
final class TomlSections {
    private final List<Section> sections = new ArrayList<>();
    private final Map<String, Section> selected = new LinkedHashMap<>();

    TomlSections(String source) throws IOException {
        Section section = addSection("", "");
        for (int start = 0; start < source.length();) {
            int end = statementEnd(source, start);
            String original = source.substring(start, end);
            String line = original.trim();
            if (line.startsWith("[")) {
                int close = line.lastIndexOf(']');
                if (close < 0) throw new IOException("Invalid TOML table");
                String name = line.substring(1, close).trim();
                for (String simple : new String[]{"options", "info"}) {
                    String header = "[" + simple + "]";
                    if (line.startsWith(header) && new Cursor(line.substring(header.length())).finished()) {
                        name = simple;
                    }
                }
                section = addSection(name, original);
            } else {
                int equals = line.indexOf('=');
                String key = null;
                String value = null;
                if (selected.containsValue(section) && !line.startsWith("#") && equals >= 0) {
                    key = line.substring(0, equals).trim();
                    if (key.startsWith("'") || key.startsWith("\"")) {
                        Cursor input = new Cursor(line);
                        key = input.string();
                        if (input.take('=')) {
                            value = line.substring(input.position).trim();
                        } else {
                            key = null;
                        }
                    } else {
                        value = line.substring(equals + 1).trim();
                    }
                }
                section.lines.add(new Line(original, key, value));
            }
            start = end;
        }
    }

    String string(String section, String key) throws IOException {
        String raw = value(section, key);
        if (raw == null) return "";
        Cursor input = new Cursor(raw);
        String result = input.string();
        if (!input.finished()) throw new IOException("Invalid TOML string");
        return result;
    }

    List<String> strings(String section, String key) throws IOException {
        String raw = value(section, key);
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        Cursor input = new Cursor(raw);
        if (!input.take('[')) throw new IOException("Invalid TOML array");
        while (!input.take(']')) {
            result.add(input.string());
            if (!input.take(',') && !input.peek(']')) throw new IOException("Invalid TOML array");
        }
        if (!input.finished()) throw new IOException("Invalid TOML array");
        return result;
    }

    String edit(Map<String, Map<String, String>> edits) throws IOException {
        for (Map.Entry<String, Map<String, String>> edit : edits.entrySet()) {
            String name = edit.getKey();
            if (!name.isEmpty() && value("", name) != null) {
                throw new IOException("Inline TOML tables are not supported for restore");
            }
            for (String key : edit.getValue().keySet()) value(name, key);
        }
        StringBuilder result = new StringBuilder();
        for (Section section : sections) {
            Map<String, String> changes = edits.get(section.name);
            if (changes == null) {
                result.append(section.header);
                for (Line line : section.lines) result.append(line.original);
                continue;
            }
            Map<String, String> remaining = new LinkedHashMap<>(changes);
            result.append(section.header);
            for (Line line : section.lines) {
                if (line.key != null && remaining.containsKey(line.key)) {
                    write(result, line.key, remaining.remove(line.key));
                } else {
                    result.append(line.original);
                }
            }
            for (Map.Entry<String, String> field : remaining.entrySet()) {
                write(result, field.getKey(), field.getValue());
            }
        }
        for (Map.Entry<String, Map<String, String>> addition : edits.entrySet()) {
            if (selected.containsKey(addition.getKey())) continue;
            if (addition.getValue().size() == Collections.frequency(addition.getValue().values(), null)) continue;
            newline(result);
            result.append('[').append(addition.getKey()).append("]\n");
            for (Map.Entry<String, String> field : addition.getValue().entrySet()) {
                write(result, field.getKey(), field.getValue());
            }
        }
        return result.toString();
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
            }
        }
        return result.append('"').toString();
    }

    static String array(Iterable<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (String value : values) {
            if (result.length() > 1) result.append(", ");
            result.append(quote(value));
        }
        return result.append(']').toString();
    }

    private Section addSection(String name, String header) throws IOException {
        Section section = new Section(name, header);
        sections.add(section);
        if (name.isEmpty() || name.equals("options") || name.equals("info")) {
            if (selected.put(name, section) != null) throw new IOException("Duplicate TOML table");
        }
        return section;
    }

    private String value(String name, String key) throws IOException {
        Section section = selected.get(name);
        if (section == null) return null;
        String found = null;
        for (Line line : section.lines) {
            if (key.equals(line.key)) {
                if (found != null) throw new IOException("Duplicate TOML field");
                found = line.value;
            }
        }
        return found;
    }

    private static void write(StringBuilder result, String key, String value) {
        if (value == null) return;
        newline(result);
        result.append(key).append(" = ").append(value).append('\n');
    }

    private static void newline(StringBuilder result) {
        if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
            result.append('\n');
        }
    }

    private static int statementEnd(String source, int start) throws IOException {
        int depth = 0;
        char quote = 0;
        boolean triple = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote && (!triple || threeQuotes(source, i, quote))) {
                    if (triple) {
                        i += 2;
                        while (i + 1 < source.length() && source.charAt(i + 1) == quote) i++;
                    }
                    quote = 0;
                } else if (c == '\n' && !triple) {
                    throw new IOException("Unterminated TOML string");
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                triple = threeQuotes(source, i, c);
                if (triple) i += 2;
            } else if (c == '#') {
                i = source.indexOf('\n', i);
                if (i < 0) return source.length();
                if (depth == 0) return i + 1;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                if (--depth < 0) throw new IOException("Invalid TOML bracket");
            } else if (c == '\n' && depth == 0) {
                return i + 1;
            }
        }
        if (quote != 0 || depth != 0) throw new IOException("Incomplete TOML statement");
        return source.length();
    }

    private static boolean threeQuotes(String text, int i, char quote) {
        return i + 2 < text.length() && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote;
    }

    private static final class Line {
        final String original;
        final String key;
        final String value;

        Line(String original, String key, String value) {
            this.original = original;
            this.key = key;
            this.value = value;
        }
    }

    private static final class Section {
        final String name;
        final String header;
        final List<Line> lines = new ArrayList<>();

        Section(String name, String header) {
            this.name = name;
            this.header = header;
        }
    }

    private static final class Cursor {
        final String text;
        int position;

        Cursor(String text) {
            this.text = text;
        }

        boolean finished() {
            skip();
            return position == text.length();
        }

        boolean peek(char c) {
            return !finished() && text.charAt(position) == c;
        }

        boolean take(char c) {
            if (!peek(c)) return false;
            position++;
            return true;
        }

        String string() throws IOException {
            if (finished()) throw new IOException("Missing TOML string");
            char quote = text.charAt(position);
            if (quote != '"' && quote != '\'') throw new IOException("Invalid TOML string");
            boolean triple = threeQuotes(text, position, quote);
            position += triple ? 3 : 1;
            if (triple) {
                if (text.startsWith("\r\n", position)) position += 2;
                else if (text.startsWith("\n", position)) position++;
            }
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                int start = position;
                char c = text.charAt(position++);
                if (c == quote && (!triple || threeQuotes(text, start, quote))) {
                    if (triple) {
                        position += 2;
                        while (position < text.length() && text.charAt(position) == quote) {
                            result.append(quote);
                            position++;
                        }
                    }
                    return result.toString();
                }
                if (quote == '"' && c == '\\') {
                    appendEscape(result, triple);
                } else {
                    result.append(c);
                }
            }
            throw new IOException("Unterminated TOML string");
        }

        private void appendEscape(StringBuilder result, boolean triple) throws IOException {
            if (position >= text.length()) throw new IOException("Incomplete TOML escape");
            char escape = text.charAt(position++);
            switch (escape) {
                case '"': case '\\': result.append(escape); return;
                case 'b': result.append('\b'); return;
                case 't': result.append('\t'); return;
                case 'n': result.append('\n'); return;
                case 'f': result.append('\f'); return;
                case 'r': result.append('\r'); return;
                case 'u': case 'U':
                    int digits = escape == 'u' ? 4 : 8;
                    if (position + digits > text.length()) throw new IOException("Incomplete TOML Unicode escape");
                    try {
                        int code = Integer.parseInt(text.substring(position, position + digits), 16);
                        if (!Character.isValidCodePoint(code) || (code >= 0xd800 && code <= 0xdfff)) {
                            throw new IOException("Invalid TOML Unicode escape");
                        }
                        result.appendCodePoint(code);
                        position += digits;
                        return;
                    } catch (NumberFormatException error) {
                        throw new IOException("Invalid TOML Unicode escape");
                    }
                default:
                    if (triple && Character.isWhitespace(escape)) {
                        while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
                        return;
                    }
                    throw new IOException("Invalid TOML escape");
            }
        }

        private void skip() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (Character.isWhitespace(c)) position++;
                else if (c == '#') {
                    int end = text.indexOf('\n', position);
                    position = end < 0 ? text.length() : end + 1;
                } else return;
            }
        }
    }
}
