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
        int i = findEndOfFieldName(fieldName);

        // Starting at index i, look for the (quoted) value string that should follow.
        // ....'fieldName'  :  'fieldValue'....
        //                ^
        i = findFieldValueStart(i);
        
        // ....'fieldName'  :  'fieldValue'....
        //                     ^
        char quote; // the active quote character if any
        char c = json.charAt(i);
        // 1st character should be a start quote...
        if ('\'' == c || '"' == c) {
            quote = c;
            i++;
        } else if (c == 't' || c == 'f') {
            // or true or false
            quote = 0;
        } else {
            throw new IllegalStateException("Value of field " + fieldName + " is not quoted, and not true/false");
        }

        // ....'fieldName'  :  'fieldValue'....
        //                      ^
        // Capture the value now, looking for its end
        StringBuilder value = new StringBuilder();
        boolean escaped = false; // flag for previous character being an escape
        for (; i < json.length(); i++) {
            c = json.charAt(i);
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
    }

    private int findEndOfFieldName(String fieldName) {
        // First finds the first index of the desired (quoted) field name
        // ....'fieldName'  :  'fieldValue'....
        int i = json.indexOf("'" + fieldName + "'");
        if (i == -1) {
            i = json.indexOf('"' + fieldName + '"');
        }
        if (i == -1) {
            throw new IllegalStateException("Found no field: " + fieldName + " in: " + json);
        }

        // Now i is the index of the field.
        // ....'fieldName'  :  'fieldValue'....
        //     ^
        // Return the index after the last quote
        // ....'fieldName'  :  'fieldValue'....
        //                ^
        return i + fieldName.length() + 2;
    }

    private int findFieldValueStart(int i) {
        // Starting at the end of the fieldName, look for the (quoted) value string that should follow.
        // ....'fieldName'  :  'fieldValue'....
        //                ^
        char c = 0;
        for (; i < json.length(); i++) {
            c = json.charAt(i);
            if (c != ':' && !Character.isWhitespace(c)) {
                break;
            }
        }
        return i;
    }
}
