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
     * Finds a field value in the JSON.
     *
     * This method implementation is complex, but it simply looks for the quoted field
     * and then iterates through the following characters until it has captured a value.
     *
     * The comments include hints about where in the search string each section is active.
     *
     * @param fieldName the field name
     * @return the field value
     */
    public String get(String fieldName) {
        try {
            // First finds the first index of the desired (quoted) field name
            // ....'fieldName'  :  'fieldValue'....
            int fi = json.indexOf("'" + fieldName + "'");
            if (fi == -1) {
                fi = json.indexOf('"' + fieldName + '"');
            }
            if (fi == -1) {
                throw new IllegalStateException("Found no field: " + fieldName + " in: " + json);
            }

            // Now fi is the index of the field.
            // ....'fieldName'  :  'fieldValue'....
            //     ^

            boolean valueStarted = false; // flag for value capture having started
            char quote = 0; // the active quote character if any
            boolean escaped = false; // flag for previous character being an escape
            StringBuilder value = new StringBuilder();

            // Starting a index i, look for the (quoted) value string that should follow.
            // ....'fieldName'  :  'fieldValue'....
            //                ^
            for (int i = fi + fieldName.length() + 2; i < json.length(); i++) {
                char c = json.charAt(i);
                if (!valueStarted && (c == ':' || Character.isWhitespace(c))) {
                    // Skip until value starts
                    continue;
                }

                if (!valueStarted) {
                    // ....'fieldName'  :  'fieldValue'....
                    //                     ^

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
                boolean noQuteEnd = quote == 0 && (',' == c || '}' == c || Character.isWhitespace(c));
                boolean quoteEnd = quote == c && !escaped;
                if (noQuteEnd || quoteEnd) {
                    break;
                }

                value.append(c);

                // If current character is escape, prevents next quoteEnd from matching
                escaped = c == '\\';
            }

            return value.toString();
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalStateException("Failed to extract field: " + fieldName + " from: " + json, e);
        }
    }
}
