package dk.mada.action.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple JSON-field extractor.
 *
 * There is no JSON parser in the JDK, and I do not want the burden of reviewing an established, full-featured JSON parser.
 * So thus this cheap and cheerful JSON field value extractor.
 *
 * Note that it only handles booleans, strings, arrays (of strings), and maps (keyed by strings, containing the previous types).
 *
 * It uses synchronized methods to guard the fields used while parsing the document.
 */
public class JsonExtractor {
    /** The string matching boolean true. */
    private static final String TRUE_STR = "true";
    /** Length of the true string. */
    private static final int TRUE_LENGTH = TRUE_STR.length();
    /** The string matching boolean false. */
    private static final String FALSE_STR = "false";
    /** Length of the false string. */
    private static final int FALSE_LENGTH = FALSE_STR.length();

    /** The JSON document. */
    private final String json;
    /** The current parsing index. */
    private int ix = 0;
    /** The field currently being read. */
    private String field = "";

    /** The types returned by this extractor. */
    public sealed interface JsonType permits JsonBoolean, JsonMap, JsonString, JsonListString {}

    /**
     * A string.
     *
     * @param value the string value
     */
    public record JsonString(String value) implements JsonType {}

    /** A boolean.
     *
     * @param value the boolean value
     */
    public record JsonBoolean(boolean value) implements JsonType {}

    /**
     * A list of strings.
     *
     * @param values the list of strings
     **/
    public record JsonListString(List<String> values) implements JsonType {}

    /**
     * A map of supported types, keyed by strings.
     *
     * @param values the map of values
     **/
    public record JsonMap(Map<String, JsonType> values) implements JsonType {}

    /**
     * Constructs a new instance.
     *
     * @param json the JSON text
     */
    public JsonExtractor(String json) {
        this.json = json;
    }

    /**
     * {@return a boolean value from a field}
     * @param fieldName the field name
     */
    public synchronized JsonBoolean getBoolean(String fieldName) {
        setIndexToFieldValueStart(fieldName);
        return getBoolean();
    }

    /**
     * {@return a string value from a field}
     * @param fieldName the field name
     */
    public synchronized JsonString getString(String fieldName) {
        setIndexToFieldValueStart(fieldName);
        return getString();
    }

    /**
     * {@return a list of strings from a field}
     * @param fieldName the field name
     */
    public synchronized JsonListString getListString(String fieldName) {
        setIndexToFieldValueStart(fieldName);

        char c = json.charAt(ix);
        if (c != '[') {
            throw makeParseException("does not contain a list");
        }

        ix++;
        return getListString();
    }

    /**
     * {@return a map from a field, keyed by strings}
     * @param fieldName the field name
     */
    public synchronized JsonMap getMap(String fieldName) {
        setIndexToFieldValueStart(fieldName);

        char c = json.charAt(ix);
        if (c != '{') {
            throw makeParseException("does not contain map");
        }
        ix++;

        return getMap();
    }

    /**
     * {@return true if the document contains the field}
     * @param fieldName the name of the field
     */
    public boolean hasField(String fieldName) {
        return indexOfField(fieldName) != -1;
    }

    /**
     * Detect and return the type at the index.
     *
     * @return the type
     */
    private JsonType getType() {
        char c = json.charAt(ix);
        if (c == '{') {
            ix++;
            return getMap();
        } else if (c == '[') {
            ix++;
            return getListString();
        } else if (c == 't' || c == 'f') {
            return getBoolean();
        } else {
            return getString();
        }
    }

    /** {@return a list of strings from the index} */
    private JsonListString getListString() {
        List<String> values = new ArrayList<>();
        for (; ix < json.length(); ix++) {
            char c = json.charAt(ix);
            if (Character.isWhitespace(c) || ',' == c) {
                continue;
            }
            if (']' == c) {
                ix++;
                return new JsonListString(values);
            }
            JsonString t = getString();
            values.add(t.value);
        }
        throw makeParseException("unexpected end of list");
    }

    /** {@return a map of string/types from the index} */
    private JsonMap getMap() {
        Map<String, JsonType> values = new HashMap<>();
        String key = null;
        for (; ix < json.length(); ix++) {
            char c = json.charAt(ix);
            if (Character.isWhitespace(c) || ',' == c || ':' == c) {
                continue;
            }
            if ('}' == c) {
                ix++;
                return new JsonMap(values);
            }

            JsonType t = getType();

            if (key == null) {
                if (t instanceof JsonString(String value)) {
                    key = value;
                } else {
                    throw makeParseException("can only use strings for map keys");
                }
            } else {
                values.put(key, t);
                key = null;
            }
        }
        throw makeParseException("unexpected end of map");
    }

    /** {@return a boolean from the index} */
    private JsonBoolean getBoolean() {
        int limit = json.length();
        char c = json.charAt(ix);
        if (c == 't' && ix + TRUE_LENGTH < limit && TRUE_STR.equals(json.substring(ix, ix + TRUE_LENGTH))) {
            ix += TRUE_LENGTH;
            return new JsonBoolean(true);
        } else if (c == 'f' && ix + FALSE_LENGTH < limit && FALSE_STR.equals(json.substring(ix, ix + FALSE_LENGTH))) {
            ix += FALSE_LENGTH;
            return new JsonBoolean(false);
        }
        throw makeParseException("expected boolean");
    }

    /** {@return a string from the index} */
    private JsonString getString() {
        char quote; // the active quote character if any
        char c = json.charAt(ix);
        // 1st character should be a start quote...
        if ('\'' == c || '"' == c) {
            quote = c;
            ix++;
        } else {
            throw makeParseException("expected a quote to start a string");
        }

        // Capture the value now, looking for its end
        StringBuilder value = new StringBuilder();
        boolean escaped = false; // flag for previous character being an escape
        for (; ix < json.length(); ix++) {
            c = json.charAt(ix);
            if (quote == c && !escaped) {
                break;
            }

            value.append(c);

            // If current character is escape, prevents next quoteEnd from matching
            escaped = c == '\\';
        }

        return new JsonString(value.toString());
    }

    /**
     * Creates a parser exception with line and column information about where parsing failed.
     *
     * @param msg the failure message
     * @return the created exception
     */
    private IllegalStateException makeParseException(String msg) {
        int line = 1;
        int lineStartedAtIndex = 0;
        for (int i = 0; i < ix; i++) {
            char c = json.charAt(i);
            if (c == '\n') {
                line += 1;
                lineStartedAtIndex = i;
            }
        }
        int col = ix - lineStartedAtIndex - 1;
        return new IllegalStateException("Failed parsing field " + field + " (line:" + line + ", column:" + col + "): "
                + msg + "\n---\n" + json + "\n---");
    }

    /**
     * Find field with the given name, and set the index to the start of the field's value.
     *
     * @param fieldName the name of the field
     */
    private void setIndexToFieldValueStart(String fieldName) {
        field = fieldName;
        ix = indexOfField(fieldName);
        if (ix == -1) {
            throw makeParseException("no such field name!?");
        }

        // Now i is the index of the field.
        // ....'fieldName'  :  'fieldValue'....
        //     ^
        // Return the index after the last quote
        // ....'fieldName'  :  'fieldValue'....
        //                ^
        ix = ix + field.length() + 2;

        // Starting at the end of the fieldName, look for the value that follows after the colon
        // ....'fieldName'  :  'fieldValue'....
        //                ^
        char c = 0;
        for (; ix < json.length(); ix++) {
            c = json.charAt(ix);
            if (c != ':' && !Character.isWhitespace(c)) {
                break;
            }
        }
    }

    /**
     * Finds index of a field.
     *
     * @param fieldName the field name to look for
     * @return the index of the field name, or -1 if not found
     */
    private int indexOfField(String fieldName) {
        // Finds the first index of the desired (quoted) field name
        // ....'fieldName'  :  'fieldValue'....
        int i = json.indexOf("'" + fieldName + "'");
        if (i == -1) {
            i = json.indexOf('"' + fieldName + '"');
        }
        return i;
    }
}
