/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package app.epistola.valtimo.richtext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Splits an HTML fragment into tags and text, the way a browser would read a fragment it is shown.
 *
 * <p>Deliberately small, and written here rather than taken from a library (ADR 0008 adds no
 * dependencies): it serves converting what an editor or a person wrote into a document, not
 * rendering a page, so it needs tags, attributes, text and character references — and to never
 * fail on malformed input. Comments, doctypes and processing instructions are skipped; the
 * contents of {@code script} and {@code style} are read as raw text so a {@code <} inside them
 * does not open a tag.
 */
final class HtmlTokenizer {

    sealed interface Token permits StartTag, EndTag, Text {
    }

    record StartTag(String name, Map<String, String> attributes, boolean selfClosing) implements Token {
    }

    record EndTag(String name) implements Token {
    }

    record Text(String text) implements Token {
    }

    private static final Set<String> RAW_TEXT = Set.of("script", "style", "textarea", "title");

    /** The Latin-1 named references, in code point order from U+00A0. */
    private static final String LATIN1 = "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy "
            + "reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 "
            + "iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute "
            + "Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute "
            + "THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave "
            + "iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc "
            + "uuml yacute thorn yuml";

    static final Map<String, String> ENTITIES = entities();

    private HtmlTokenizer() {
    }

    static List<Token> tokenize(String html) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        int length = html.length();
        while (i < length) {
            char c = html.charAt(i);
            if (c != '<') {
                text.append(c);
                i++;
                continue;
            }
            if (html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                i = end < 0 ? length : end + 3;
                continue;
            }
            if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
                int end = html.indexOf('>', i);
                i = end < 0 ? length : end + 1;
                continue;
            }
            boolean closing = i + 1 < length && html.charAt(i + 1) == '/';
            int nameStart = i + (closing ? 2 : 1);
            int nameEnd = nameStart;
            while (nameEnd < length && isNameChar(html.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == nameStart || !Character.isLetter(html.charAt(nameStart))) {
                // "a < b": not a tag, just text.
                text.append(c);
                i++;
                continue;
            }
            flushText(text, tokens);
            String name = html.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
            if (closing) {
                int end = html.indexOf('>', nameEnd);
                tokens.add(new EndTag(name));
                i = end < 0 ? length : end + 1;
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            int[] position = {nameEnd};
            boolean selfClosing = readAttributes(html, position, attributes);
            tokens.add(new StartTag(name, attributes, selfClosing));
            i = position[0];
            if (RAW_TEXT.contains(name) && !selfClosing) {
                int end = indexOfIgnoreCase(html, "</" + name, i);
                String raw = html.substring(i, end < 0 ? length : end);
                if (!raw.isEmpty()) {
                    tokens.add(new Text(name.equals("textarea") || name.equals("title") ? decode(raw) : raw));
                }
                if (end < 0) {
                    i = length;
                } else {
                    int close = html.indexOf('>', end);
                    tokens.add(new EndTag(name));
                    i = close < 0 ? length : close + 1;
                }
            }
        }
        flushText(text, tokens);
        return tokens;
    }

    /** Read attributes up to the end of the tag; returns whether the tag closed itself. */
    private static boolean readAttributes(String html, int[] position, Map<String, String> attributes) {
        int i = position[0];
        int length = html.length();
        boolean selfClosing = false;
        while (i < length) {
            char c = html.charAt(i);
            if (c == '>') {
                i++;
                break;
            }
            if (c == '/' ) {
                selfClosing = i + 1 < length && html.charAt(i + 1) == '>';
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int nameStart = i;
            while (i < length && !Character.isWhitespace(html.charAt(i))
                    && "=>/".indexOf(html.charAt(i)) < 0) {
                i++;
            }
            String name = html.substring(nameStart, i).toLowerCase(Locale.ROOT);
            while (i < length && Character.isWhitespace(html.charAt(i))) {
                i++;
            }
            String value = "";
            if (i < length && html.charAt(i) == '=') {
                i++;
                while (i < length && Character.isWhitespace(html.charAt(i))) {
                    i++;
                }
                if (i < length && (html.charAt(i) == '"' || html.charAt(i) == '\'')) {
                    char quote = html.charAt(i);
                    int end = html.indexOf(quote, i + 1);
                    value = html.substring(i + 1, end < 0 ? length : end);
                    i = end < 0 ? length : end + 1;
                } else {
                    int valueStart = i;
                    while (i < length && !Character.isWhitespace(html.charAt(i)) && html.charAt(i) != '>') {
                        i++;
                    }
                    value = html.substring(valueStart, i);
                }
            }
            if (!name.isEmpty()) {
                attributes.putIfAbsent(name, decode(value));
            }
        }
        position[0] = i;
        return selfClosing;
    }

    /** Resolve character references: named ones this class knows, and every numeric one. */
    static String decode(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int semicolon = c == '&' ? text.indexOf(';', i) : -1;
            if (semicolon < 0 || semicolon - i > 32) {
                out.append(c);
                i++;
                continue;
            }
            String reference = text.substring(i + 1, semicolon);
            String resolved = resolve(reference);
            if (resolved == null) {
                out.append(c);
                i++;
            } else {
                out.append(resolved);
                i = semicolon + 1;
            }
        }
        return out.toString();
    }

    private static String resolve(String reference) {
        if (reference.startsWith("#")) {
            try {
                int codePoint = reference.length() > 1 && (reference.charAt(1) == 'x' || reference.charAt(1) == 'X')
                        ? Integer.parseInt(reference.substring(2), 16)
                        : Integer.parseInt(reference.substring(1));
                return codePoint > 0 && Character.isValidCodePoint(codePoint)
                        && (codePoint < 0xD800 || codePoint > 0xDFFF)
                        ? new String(Character.toChars(codePoint))
                        : "�";
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ENTITIES.get(reference);
    }

    private static Map<String, String> entities() {
        Map<String, String> entities = new HashMap<>();
        String[] names = LATIN1.split(" ");
        for (int i = 0; i < names.length; i++) {
            entities.put(names[i], String.valueOf((char) (0xA0 + i)));
        }
        entities.put("amp", "&");
        entities.put("lt", "<");
        entities.put("gt", ">");
        entities.put("quot", "\"");
        entities.put("apos", "'");
        entities.put("ndash", "–");
        entities.put("mdash", "—");
        entities.put("lsquo", "‘");
        entities.put("rsquo", "’");
        entities.put("sbquo", "‚");
        entities.put("ldquo", "“");
        entities.put("rdquo", "”");
        entities.put("bdquo", "„");
        entities.put("hellip", "…");
        entities.put("bull", "•");
        entities.put("euro", "€");
        entities.put("trade", "™");
        entities.put("dagger", "†");
        entities.put("permil", "‰");
        entities.put("lsaquo", "‹");
        entities.put("rsaquo", "›");
        entities.put("OElig", "Œ");
        entities.put("oelig", "œ");
        entities.put("Scaron", "Š");
        entities.put("scaron", "š");
        entities.put("Yuml", "Ÿ");
        entities.put("ensp", " ");
        entities.put("emsp", " ");
        entities.put("thinsp", " ");
        entities.put("zwnj", "‌");
        entities.put("zwj", "‍");
        return Map.copyOf(entities);
    }

    private static void flushText(StringBuilder text, List<Token> tokens) {
        if (!text.isEmpty()) {
            tokens.add(new Text(decode(text.toString())));
            text.setLength(0);
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_';
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int limit = haystack.length() - needle.length();
        for (int i = from; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
