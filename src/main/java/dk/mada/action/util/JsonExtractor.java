package dk.mada.action.util;

/**
 * Crude JSON-field extractor.
 *
 * There is so much configuration to be done when using a full JSON parser.
 * And it adds a complicated dependency. So shoot me for cheating.
 *
 * This only works
 * because the input is known (assumed!) to be regular.
 */
public class JsonExtractor {
    /** The JSON document. */
    private final String json;

    /**
     * Constructs a new instance.
     *
     * @param json the JSON text
     */
    public JsonExtractor(String json) {
        this.json = json;
    }

    /**
     * {@return the field value as an integer}
     *
     * @param fieldName the field name
     */
    public int getInt(String fieldName) {
        String value = get(fieldName);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Failed to convert integer field " + fieldName + " value: " + value, e);
        }
    }

    /**
     * {@return the field value as a boolean}
     *
     * @param fieldName the field name
     */
    public boolean getBool(String fieldName) {
        return Boolean.parseBoolean(get(fieldName));
    }

    /**
     * {@return the field value}
     *
     * @param fieldName the field name
     */
    public String get(String fieldName) {
        try {
            int fi = json.indexOf("'" + fieldName + "'");
            if (fi == -1) {
                fi = json.indexOf('"' + fieldName + '"');
            }
            if (fi == -1) {
                throw new IllegalStateException("Found no field: " + fieldName + " in: " + json);
            }

            // fi is the index of the field. Look for a (quoted) value string after.
            boolean valueStarted = false;
            char quote = 0;
            boolean escaped = false;
            String value = "";
            for (int i = fi + fieldName.length() + 2; i < json.length(); i++) {
                char c = json.charAt(i);
                if (!valueStarted && (c == ':' || Character.isWhitespace(c))) {
                    // Skip until value starts
                    continue;
                }

                if (!valueStarted) {
                    // 1st character should be a start quote...
                    valueStarted = true;
                    if ('\'' == c || '"' == c) {
                        quote = c;
                        continue;
                    }
                    // or true or false
                    if (c != 't' && c != 'f') {
                        throw new IllegalStateException(
                                "Value of field " + fieldName + " is not quoted, and not true/false");
                    }
                }

                // Look for the end of the value now
                if ((quote == 0 && (',' == c || Character.isWhitespace(c))) || (quote == c && !escaped)) {
                    break;
                }

                value = value + c;
                escaped = c == '\\';
            }

            return value;
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalStateException("Failed to extract field: " + fieldName + " from: " + json, e);
        }
    }
}
