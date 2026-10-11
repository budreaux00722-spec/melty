package dev.scrapsky.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small lenient JSON reader (accepts // and block comments and trailing commas, as Scrap Mechanic's files use). */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text.startsWith("﻿") ? text.substring(1) : text);
        Object v = p.value();
        p.ws();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }

    public static String str(Object o) { return o instanceof String x ? x : null; }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; }
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') { int e = s.indexOf("*/", i + 2); i = e < 0 ? s.length() : e + 2; }
            else break;
        }
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            default:
                if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
                if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
                if (s.startsWith("null", i)) { i += 4; return null; }
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        while (true) {
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            String k = string();
            ws();
            if (s.charAt(i) != ':') throw new IllegalArgumentException("expected : at " + i);
            i++;
            m.put(k, value());
            ws();
            char c = s.charAt(i);
            if (c == ',') i++;
            else if (c == '}') { i++; return m; }
            else throw new IllegalArgumentException("expected , or } at " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        while (true) {
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            l.add(value());
            ws();
            char c = s.charAt(i);
            if (c == ',') i++;
            else if (c == ']') { i++; return l; }
            else throw new IllegalArgumentException("expected , or ] at " + i);
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            } else b.append(c);
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' at " + i);
        return Double.parseDouble(s.substring(st, i));
    }
}
