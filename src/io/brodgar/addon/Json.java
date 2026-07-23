package io.brodgar.addon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader (Phase 1: addon manifests and saved variables — see
 * {@code specs/addons/decisions.md} D-016). The compact JSON *writer* lives in
 * {@link AddonManager} (the {@code :lua} REPL); this is the matching reader.
 *
 * <p>{@link #parse} returns plain Java values: {@link Map} (object, insertion-ordered),
 * {@link List} (array), {@link String}, {@link Double} (any number), {@link Boolean}, or
 * {@code null}. Malformed input throws a {@link RuntimeException} with an offset.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if(j.i < j.s.length())
            throw j.err("unexpected trailing content");
        return v;
    }

    private Object value() {
        char c = peek();
        switch(c) {
        case '{': return object();
        case '[': return array();
        case '"': return string();
        case 't': return literal("true", Boolean.TRUE);
        case 'f': return literal("false", Boolean.FALSE);
        case 'n': return literal("null", null);
        default:
            if((c == '-') || ((c >= '0') && (c <= '9')))
                return number();
            throw err("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; ws();                                  // consume '{'
        if(peek() == '}') { i++; return m; }
        while(true) {
            ws();
            if(peek() != '"') throw err("expected string key");
            String k = string();
            ws();
            if(peek() != ':') throw err("expected ':'");
            i++; ws();
            m.put(k, value());
            ws();
            char c = peek();
            if(c == ',') { i++; continue; }
            if(c == '}') { i++; return m; }
            throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<Object>();
        i++; ws();                                  // consume '['
        if(peek() == ']') { i++; return a; }
        while(true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            if(c == ',') { i++; continue; }
            if(c == ']') { i++; return a; }
            throw err("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;                                        // consume opening quote
        while(true) {
            if(i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if(c == '"') return b.toString();
            if(c == '\\') {
                if(i >= s.length()) throw err("unterminated escape");
                char e = s.charAt(i++);
                switch(e) {
                case '"':  b.append('"');  break;
                case '\\': b.append('\\'); break;
                case '/':  b.append('/');  break;
                case 'b':  b.append('\b'); break;
                case 'f':  b.append('\f'); break;
                case 'n':  b.append('\n'); break;
                case 'r':  b.append('\r'); break;
                case 't':  b.append('\t'); break;
                case 'u':
                    if(i + 4 > s.length()) throw err("bad \\u escape");
                    b.append((char)Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("bad escape '\\" + e + "'");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double number() {
        int start = i;
        if(peek() == '-') i++;
        while((i < s.length()) && ("0123456789+-.eE".indexOf(s.charAt(i)) >= 0))
            i++;
        try {
            return Double.valueOf(Double.parseDouble(s.substring(start, i)));
        } catch(NumberFormatException e) {
            throw err("bad number");
        }
    }

    private Object literal(String word, Object val) {
        if(!s.regionMatches(i, word, 0, word.length()))
            throw err("expected '" + word + "'");
        i += word.length();
        return val;
    }

    private char peek() {
        if(i >= s.length()) throw err("unexpected end of input");
        return s.charAt(i);
    }

    private void ws() {
        while(i < s.length()) {
            char c = s.charAt(i);
            if((c == ' ') || (c == '\t') || (c == '\n') || (c == '\r'))
                i++;
            else
                break;
        }
    }

    private RuntimeException err(String msg) {
        return new RuntimeException("JSON: " + msg + " at offset " + i);
    }
}
