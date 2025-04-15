package dk.mada.action.util;

/**
 * Crude XML-field extractor.
 *
 * There is so much configuration to be done when using a full XML parser. So shoot me for cheating. This only works
 * because the input is known (assumed!) to be regular.
 */
public class XmlExtractor {
    /** The XML document. */
    private final String xml;

    /**
     * Constructs a new instance.
     *
     * @param xml the XML text
     */
    public XmlExtractor(String xml) {
        this.xml = xml;
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
            String fieldBegin = "<" + fieldName + ">";
            String fieldEnd = "</" + fieldName + ">";
            int start = xml.indexOf(fieldBegin) + fieldBegin.length();
            int end = xml.indexOf(fieldEnd);

            return xml.substring(start, end);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalStateException("Failed to extract field: " + fieldName + " from: " + xml, e);
        }
    }
}
