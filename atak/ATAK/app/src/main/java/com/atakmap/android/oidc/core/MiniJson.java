package com.atakmap.android.oidc.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict JSON parser for the small OIDC documents used by the client. */
public final class MiniJson {

    private MiniJson() {
    }

    public static Object parse(String json) throws OidcException {
        if (json == null) {
            throw invalidJson();
        }
        Parser parser = new Parser(json);
        Object result = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw invalidJson();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) throws OidcException {
        Object value = parse(json);
        if (!(value instanceof Map<?, ?>)) {
            throw invalidJson();
        }
        return (Map<String, Object>) value;
    }

    private static OidcException invalidJson() {
        return new OidcException(OidcError.DISCOVERY_FAILED, "invalid JSON");
    }

    private static final class Parser {

        private final String input;
        private int index;

        Parser(String input) {
            this.input = input;
        }

        Object readValue() throws OidcException {
            skipWhitespace();
            if (atEnd()) {
                throw invalidJson();
            }
            char c = input.charAt(index);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't') {
                readLiteral("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                readLiteral("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                readLiteral("null");
                return null;
            }
            if (c == '-' || c >= '0' && c <= '9') {
                return readNumber();
            }
            throw invalidJson();
        }

        private Map<String, Object> readObject() throws OidcException {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || input.charAt(index) != '"') {
                    throw invalidJson();
                }
                String name = readString();
                skipWhitespace();
                require(':');
                Object value = readValue();
                result.put(name, value);
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                require(',');
            }
        }

        private List<Object> readArray() throws OidcException {
            ArrayList<Object> result = new ArrayList<>();
            index++;
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                require(',');
            }
        }

        private String readString() throws OidcException {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char c = input.charAt(index++);
                if (c == '"') {
                    return result.toString();
                }
                if (c == '\\') {
                    result.append(readEscape());
                } else {
                    if (c < 0x20) {
                        throw invalidJson();
                    }
                    result.append(c);
                }
            }
            throw invalidJson();
        }

        private char readEscape() throws OidcException {
            if (atEnd()) {
                throw invalidJson();
            }
            char escaped = input.charAt(index++);
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    return escaped;
                case 'b':
                    return '\b';
                case 'f':
                    return '\f';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'u':
                    return readUnicodeEscape();
                default:
                    throw invalidJson();
            }
        }

        private char readUnicodeEscape() throws OidcException {
            if (index + 4 > input.length()) {
                throw invalidJson();
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = hex(input.charAt(index++));
                if (digit < 0) {
                    throw invalidJson();
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Number readNumber() throws OidcException {
            int start = index;
            consume('-');
            if (consume('0')) {
                if (!atEnd() && isDigit(input.charAt(index))) {
                    throw invalidJson();
                }
            } else {
                readDigits();
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                readDigits();
            }
            if (!atEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!atEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                readDigits();
            }
            String value = input.substring(start, index);
            try {
                if (!decimal) {
                    return Long.valueOf(value);
                }
                Double number = Double.valueOf(value);
                if (number.isInfinite() || number.isNaN()) {
                    throw invalidJson();
                }
                return number;
            } catch (NumberFormatException e) {
                throw invalidJson();
            }
        }

        private void readDigits() throws OidcException {
            int start = index;
            while (!atEnd() && isDigit(input.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw invalidJson();
            }
        }

        private void readLiteral(String literal) throws OidcException {
            if (!input.regionMatches(index, literal, 0, literal.length())) {
                throw invalidJson();
            }
            index += literal.length();
        }

        private void require(char expected) throws OidcException {
            if (!consume(expected)) {
                throw invalidJson();
            }
        }

        private boolean consume(char expected) {
            if (!atEnd() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = input.charAt(index);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    index++;
                } else {
                    return;
                }
            }
        }

        boolean atEnd() {
            return index == input.length();
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static int hex(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }
    }
}
