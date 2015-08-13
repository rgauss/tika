/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.metadata;

import static org.apache.tika.utils.DateUtils.MIDDAY;
import static org.apache.tika.utils.DateUtils.UTC;
import static org.apache.tika.utils.DateUtils.formatDate;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.tika.metadata.Property.PropertyType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A multi-valued metadata container.
 */
public class Metadata implements CreativeCommons, Geographic, HttpHeaders,
        Message, MSOffice, ClimateForcast, TIFF, TikaMetadataKeys, TikaMimeKeys,
        Serializable {

    /** Serial version UID */
    private static final long serialVersionUID = 5623926545693153182L;

    /**
     * A map of all metadata attributes.
     */
    private Document metadata = null;

    /**
     * The common delimiter used between the namespace abbreviation and the property name
     */
    public static final String NAMESPACE_PREFIX_DELIMITER = ":";

    /** @deprecated use TikaCoreProperties#FORMAT */
    public static final String FORMAT = "format";
    /** @deprecated use TikaCoreProperties#IDENTIFIER */
    public static final String IDENTIFIER = "identifier";
    /** @deprecated use TikaCoreProperties#MODIFIED */
    public static final String MODIFIED = "modified";
    /** @deprecated use TikaCoreProperties#CONTRIBUTOR */
    public static final String CONTRIBUTOR = "contributor";
    /** @deprecated use TikaCoreProperties#COVERAGE */
    public static final String COVERAGE = "coverage";
    /** @deprecated use TikaCoreProperties#CREATOR */
    public static final String CREATOR = "creator";
    /** @deprecated use TikaCoreProperties#CREATED */
    public static final Property DATE = Property.internalDate("date");
    /** @deprecated use TikaCoreProperties#DESCRIPTION */
    public static final String DESCRIPTION = "description";
    /** @deprecated use TikaCoreProperties#LANGUAGE */
    public static final String LANGUAGE = "language";
    /** @deprecated use TikaCoreProperties#PUBLISHER */
    public static final String PUBLISHER = "publisher";
    /** @deprecated use TikaCoreProperties#RELATION */
    public static final String RELATION = "relation";
    /** @deprecated use TikaCoreProperties#RIGHTS */
    public static final String RIGHTS = "rights";
    /** @deprecated use TikaCoreProperties#SOURCE */
    public static final String SOURCE = "source";
    /** @deprecated use TikaCoreProperties#KEYWORDS */
    public static final String SUBJECT = "subject";
    /** @deprecated use TikaCoreProperties#TITLE */
    public static final String TITLE = "title";
    /** @deprecated use TikaCoreProperties#TYPE */
    public static final String TYPE = "type";

    private static final String DOM_NODE_NAME_ROOT = "metadata";
    private static final String DOM_NODE_NAME_TOP_LEVEL_ELEMENT = "item";
    private static final String DOM_ATTRIBUTE_NAME = "name";

    /**
     * Some parsers will have the date as a ISO-8601 string
     *  already, and will set that into the Metadata object.
     * So we can return Date objects for these, this is the
     *  list (in preference order) of the various ISO-8601
     *  variants that we try when processing a date based
     *  property.
     */
    private static final DateFormat[] iso8601InputFormats = new DateFormat[] {
        // yyyy-mm-ddThh...
        createDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", UTC),   // UTC/Zulu
        createDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", null),    // With timezone
        createDateFormat("yyyy-MM-dd'T'HH:mm:ss", null),     // Without timezone
        // yyyy-mm-dd hh...
        createDateFormat("yyyy-MM-dd' 'HH:mm:ss'Z'", UTC),   // UTC/Zulu
        createDateFormat("yyyy-MM-dd' 'HH:mm:ssZ", null),    // With timezone
        createDateFormat("yyyy-MM-dd' 'HH:mm:ss", null),     // Without timezone
        // Date without time, set to Midday UTC
        createDateFormat("yyyy-MM-dd", MIDDAY),              // Normal date format
        createDateFormat("yyyy:MM:dd", MIDDAY),              // Image (IPTC/EXIF) format
    };

    private static DateFormat createDateFormat(String format, TimeZone timezone) {
        SimpleDateFormat sdf =
            new SimpleDateFormat(format, new DateFormatSymbols(Locale.US));
        if (timezone != null) {
            sdf.setTimeZone(timezone);
        }
        return sdf;
    }

    /**
     * Parses the given date string. This method is synchronized to prevent
     * concurrent access to the thread-unsafe date formats.
     *
     * @see <a href="https://issues.apache.org/jira/browse/TIKA-495">TIKA-495</a>
     * @param date date string
     * @return parsed date, or <code>null</code> if the date can't be parsed
     */
    private static synchronized Date parseDate(String date) {
        // Java doesn't like timezones in the form ss+hh:mm
        // It only likes the hhmm form, without the colon
        int n = date.length();
        if (date.charAt(n - 3) == ':'
            && (date.charAt(n - 6) == '+' || date.charAt(n - 6) == '-')) {
            date = date.substring(0, n - 3) + date.substring(n - 2);
        }

        // Try several different ISO-8601 variants
        for (DateFormat format : iso8601InputFormats) {
            try {
                return format.parse(date);
            } catch (ParseException ignore) {
            }
        }
        return null;
    }

    /**
     * Constructs a new, empty metadata.
     */
    public Metadata() {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            metadata = documentBuilder.newDocument();
            // TODO namespace our root node
            Node rootNode = metadata.createElement(DOM_NODE_NAME_ROOT);
            metadata.appendChild(rootNode);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Could not instantiate metadata store", e);
        }
    }

    /**
     * Returns true if named value is multivalued.
     * 
     * @param property
     *          metadata property
     * @return true is named value is multivalued, false if single value or null
     */
    public boolean isMultiValued(final Property property) {
        return _getValues(property.getName()).length > 1;
    }
    
    /**
     * Returns true if named value is multivalued.
     * 
     * @param name
     *          name of metadata
     * @return true is named value is multivalued, false if single value or null
     */
    public boolean isMultiValued(final String name) {
        return _getValues(name).length > 1;
    }

    /**
     * Returns an array of the names contained in the metadata.
     * 
     * @return Metadata names
     */
    public String[] names() {
        ArrayList<String> names = new ArrayList<String>();
        NodeList list = metadata.getElementsByTagName(DOM_NODE_NAME_TOP_LEVEL_ELEMENT);
        for (int i = 0; i < list.getLength(); i++) {
            Node item = list.item(i);
            String name = getAttribute(item, DOM_ATTRIBUTE_NAME);
            if (!names.contains(name))
            {
                names.add(name);
            }
        }
        return names.toArray(new String[names.size()]);
    }
    
    /**
     * Determines if the given node is a text node (vs element, etc.)
     * 
     * @param node
     * @return true if node is a text node
     */
    protected boolean isTextNode(Node node) {
        if (node != null && node.hasChildNodes() && node.getChildNodes().getLength() == 1)
        {
            Node childNode = node.getChildNodes().item(0);
            return (childNode.getNodeType() == Node.TEXT_NODE);
        }
        return false;
    }
    
    /**
     * Gets the value of the given node (often the child text node)
     * 
     * @param node
     * @return the node string value
     */
    protected String getNodeValue(Node node) {
        if (node == null)
        {
            return null;
        }
        String value = node.getNodeValue();
        if (value == null && isTextNode(node))
        {
            value = node.getChildNodes().item(0).getNodeValue();
        }
        if (value != null)
        {
            value = unescapeNodeValue(value);
        }
        return value;
    }
    
    /**
     * Escapes the given value (for valid XML)
     * 
     * @param value
     * @return the escaped value
     */
    protected String escapeNodeValue(String value) {
        if (value == null) {
            return null;
        }
        return StringEscapeUtils.escapeXml(value);
    }
    
    /**
     * Unescapes the given value (from XML-escaped)
     * 
     * @param value
     * @return the unescpaed value
     */
    protected String unescapeNodeValue(String value) {
        if (value == null) {
            return null;
        }
        return StringEscapeUtils.unescapeXml(value);
    }
    
    /**
     * Gets the given attribute value from the given node
     * 
     * @param node
     * @param attribute
     * @return the attribute value
     */
    protected String getAttribute(Node node, String attribute) {
        if (node == null || attribute == null || !node.hasAttributes()) {
            return null;
        }
        // TODO namespacing
        Node attributeNode = node.getAttributes().getNamedItem(attribute);
        if (attributeNode == null) {
            return null;
        }
        return attributeNode.getNodeValue();
    }

    /**
     * Get the value associated to a metadata name. If many values are assiociated
     * to the specified name, then the first one is returned.
     * 
     * @param name
     *          of the metadata.
     * @return the value associated to the specified metadata name.
     */
    public String get(final String name) {
        Node foundNode = null;
        NodeList list = metadata.getElementsByTagName(DOM_NODE_NAME_TOP_LEVEL_ELEMENT);
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.getLength(); i++) {
            String metadataName = getAttribute(list.item(i), DOM_ATTRIBUTE_NAME);
            if (name.equals(metadataName)) {
                foundNode = list.item(i);
                break;
            }
        }
        
        if (foundNode == null) {
            return null;
        } else {
            return getNodeValue(foundNode);
        }
    }

    /**
     * Returns the value (if any) of the identified metadata property.
     *
     * @since Apache Tika 0.7
     * @param property property definition
     * @return property value, or <code>null</code> if the property is not set
     */
    public String get(Property property) {
        return get(property.getName());
    }
    
    /**
     * Returns the value of the identified Integer based metadata property.
     * 
     * @since Apache Tika 0.8
     * @param property simple integer property definition
     * @return property value as a Integer, or <code>null</code> if the property is not set, or not a valid Integer
     */
    public Integer getInt(Property property) {
        if(property.getPrimaryProperty().getPropertyType() != Property.PropertyType.SIMPLE) {
            return null;
        }
        if(property.getPrimaryProperty().getValueType() != Property.ValueType.INTEGER) {
            return null;
        }
        
        String v = get(property);
        if(v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch(NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the value of the identified Date based metadata property.
     * 
     * @since Apache Tika 0.8
     * @param property simple date property definition
     * @return property value as a Date, or <code>null</code> if the property is not set, or not a valid Date
     */
    public Date getDate(Property property) {
        if(property.getPrimaryProperty().getPropertyType() != Property.PropertyType.SIMPLE) {
            return null;
        }
        if(property.getPrimaryProperty().getValueType() != Property.ValueType.DATE) {
            return null;
        }
        
        String v = get(property);
        if (v != null) {
            return parseDate(v);
        } else {
            return null;
        }
    }
    
    /**
     * Get the values associated to a metadata name.
     * 
     * @param property
     *          of the metadata.
     * @return the values associated to a metadata name.
     */
    public String[] getValues(final Property property) {
        return _getValues(property.getName());
    }

    /**
     * Get the values associated to a metadata name.
     * 
     * @param name
     *          of the metadata.
     * @return the values associated to a metadata name.
     */
    public String[] getValues(final String name) {
        return _getValues(name);
    }

    private String[] _getValues(final String name) {
        ArrayList<String> values = new ArrayList<String>();
        NodeList list = metadata.getElementsByTagName(DOM_NODE_NAME_TOP_LEVEL_ELEMENT);
        for (int i = 0; i < list.getLength(); i++) {
            String metadataName = getAttribute(list.item(i), DOM_ATTRIBUTE_NAME);
            if (name.equals(metadataName)) {
                String value = getNodeValue(list.item(i));
                if (value != null)
                {
                    values.add(value);
                }
            }
        }
        return values.toArray(new String[values.size()]);
    }
    
    /**
     * Add a metadata name/value mapping. Add the specified value to the list of
     * values associated to the specified metadata name.
     * 
     * @param name
     *          the metadata name.
     * @param value
     *          the metadata value.
     */
    public void add(final String name, final String value) {
        if (name == null || value == null) {
            return;
        }
        Element element = metadata.createElement(DOM_NODE_NAME_TOP_LEVEL_ELEMENT);
        // TODO namespacing
        element.setAttribute(DOM_ATTRIBUTE_NAME, name);
        element.appendChild(metadata.createTextNode(escapeNodeValue(value)));
        metadata.getFirstChild().appendChild(element);
    }
    
    /**
     * Add a metadata property/value mapping. Add the specified value to the list of
     * values associated to the specified metadata property.
     * 
     * @param property
     *          the metadata property.
     * @param value
     *          the metadata value.
     */
    public void add(final Property property, final String value) {
        if (property == null || value == null)
        {
            return;
        }
        if (property.getPropertyType() == PropertyType.COMPOSITE) {
            add(property.getPrimaryProperty(), value);
            if (property.getSecondaryExtractProperties() != null) {
                for (Property secondaryExtractProperty : property.getSecondaryExtractProperties()) {
                    try {
                        add(secondaryExtractProperty, value);
                    } catch (PropertyTypeException e) {
                        // TODO this is only to mimic current behavior, is it what we want?
                        set(secondaryExtractProperty.getName(), value);
                    }
                }
            }
        } else {
            String currentValue = get(property);
            if (!property.isMultiValuePermitted() && currentValue != null)
            {
                throw new PropertyTypeException(property.getPropertyType());
            }
            add(property.getName(), value);
        }
    }

    /**
     * Copy All key-value pairs from properties.
     * 
     * @param properties
     *          properties to copy from
     */
    @SuppressWarnings("unchecked")
    public void setAll(Properties properties) {
        Enumeration<String> names =
            (Enumeration<String>) properties.propertyNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = properties.getProperty(name);
            set(name, value);
        }
    }

    /**
     * Set metadata name/value. Associate the specified value to the specified
     * metadata name. If some previous values were associated to this name,
     * they are removed. If the given value is <code>null</code>, then the
     * metadata entry is removed.
     *
     * @param name the metadata name.
     * @param value  the metadata value, or <code>null</code>
     */
    public void set(String name, String value) {
        remove(name);
        if (value != null) {
            add(name, value);
        }
    }

    /**
     * Sets the value of the identified metadata property.
     *
     * @since Apache Tika 0.7
     * @param property property definition
     * @param value    property value
     */
    public void set(Property property, String value) {
        if (property == null) {
            throw new NullPointerException("property must not be null");
        }
        if (property.getPropertyType() == PropertyType.COMPOSITE) {
            set(property.getPrimaryProperty(), value);
            if (property.getSecondaryExtractProperties() != null) {
                for (Property secondaryExtractProperty : property.getSecondaryExtractProperties()) {
                    set(secondaryExtractProperty, value);
                }
            }
        } else {
            set(property.getName(), value);
        }
    }
    
    /**
     * Sets the values of the identified metadata property.
     *
     * @since Apache Tika 1.2
     * @param property property definition
     * @param values    property values
     */
    public void set(Property property, String[] values) {
        if (property == null) {
            throw new NullPointerException("property must not be null");
        }
        if (property.getPropertyType() == PropertyType.COMPOSITE) {
            set(property.getPrimaryProperty(), values);
            if (property.getSecondaryExtractProperties() != null) {
                for (Property secondaryExtractProperty : property.getSecondaryExtractProperties()) {
                    set(secondaryExtractProperty, values);
                }
            }
        } else {
            for (int i = 0; i < values.length; i++) {
                add(property, values[i]);
            }
        }
    }

    /**
     * Sets the integer value of the identified metadata property.
     *
     * @since Apache Tika 0.8
     * @param property simple integer property definition
     * @param value    property value
     */
    public void set(Property property, int value) {
        if(property.getPrimaryProperty().getPropertyType() != Property.PropertyType.SIMPLE) {
            throw new PropertyTypeException(Property.PropertyType.SIMPLE, property.getPrimaryProperty().getPropertyType());
        }
        if(property.getPrimaryProperty().getValueType() != Property.ValueType.INTEGER) {
            throw new PropertyTypeException(Property.ValueType.INTEGER, property.getPrimaryProperty().getValueType());
        }
        set(property, Integer.toString(value));
    }

    /**
     * Sets the real or rational value of the identified metadata property.
     *
     * @since Apache Tika 0.8
     * @param property simple real or simple rational property definition
     * @param value    property value
     */
    public void set(Property property, double value) {
        if(property.getPrimaryProperty().getPropertyType() != Property.PropertyType.SIMPLE) {
            throw new PropertyTypeException(Property.PropertyType.SIMPLE, property.getPrimaryProperty().getPropertyType());
        }
        if(property.getPrimaryProperty().getValueType() != Property.ValueType.REAL &&
              property.getPrimaryProperty().getValueType() != Property.ValueType.RATIONAL) {
            throw new PropertyTypeException(Property.ValueType.REAL, property.getPrimaryProperty().getValueType());
        }
        set(property, Double.toString(value));
    }

    /**
     * Sets the date value of the identified metadata property.
     *
     * @since Apache Tika 0.8
     * @param property simple integer property definition
     * @param date     property value
     */
    public void set(Property property, Date date) {
        if(property.getPrimaryProperty().getPropertyType() != Property.PropertyType.SIMPLE) {
            throw new PropertyTypeException(Property.PropertyType.SIMPLE, property.getPrimaryProperty().getPropertyType());
        }
        if(property.getPrimaryProperty().getValueType() != Property.ValueType.DATE) {
            throw new PropertyTypeException(Property.ValueType.DATE, property.getPrimaryProperty().getValueType());
        }
        String dateString = null;
        if (date != null) {
            dateString = formatDate(date);
        }
        set(property, dateString);
    }

    /**
     * Sets the date value of the identified metadata property.
     *
     * @since Apache Tika 0.8
     * @param property simple integer property definition
     * @param date     property value
     */
    public void set(Property property, Calendar date) {
        if(property.getPrimaryProperty().getPropertyType() != Property.PropertyType.SIMPLE) {
            throw new PropertyTypeException(Property.PropertyType.SIMPLE, property.getPrimaryProperty().getPropertyType());
        }
        if(property.getPrimaryProperty().getValueType() != Property.ValueType.DATE) {
            throw new PropertyTypeException(Property.ValueType.DATE, property.getPrimaryProperty().getValueType());
        }
        String dateString = null;
        if (date != null) {
            dateString = formatDate(date);
        }
        set(property, dateString);
    }

    /**
     * Remove a metadata and all its associated values.
     * 
     * @param name
     *          metadata name to remove
     */
    public void remove(String name) {
        NodeList list = metadata.getElementsByTagName(DOM_NODE_NAME_TOP_LEVEL_ELEMENT);
        Set<Node> toRemove = new HashSet<Node>();
        int found = list.getLength();
        for (int i = 0; i < found; i++) {
            Node item = list.item(i);
            if (name.equals(getAttribute(item, DOM_ATTRIBUTE_NAME))) {
                toRemove.add(item);
            }
        }
        for (Node node : toRemove) {
            node.getParentNode().removeChild(node);
        }
    }

    /**
     * Returns the number of metadata names in this metadata.
     * 
     * @return number of metadata names
     */
    public int size() {
        return names().length;
    }

    public boolean equals(Object o) {

        if (o == null) {
            return false;
        }

        Metadata other = null;
        try {
            other = (Metadata) o;
        } catch (ClassCastException cce) {
            return false;
        }

        if (other.size() != size()) {
            return false;
        }

        String[] names = names();
        for (int i = 0; i < names.length; i++) {
            String[] otherValues = other._getValues(names[i]);
            String[] thisValues = _getValues(names[i]);
            if (otherValues.length != thisValues.length) {
                return false;
            }
            for (int j = 0; j < otherValues.length; j++) {
                if (!otherValues[j].equals(thisValues[j])) {
                    return false;
                }
            }
        }
        return true;
    }

    public String toString() {
        Node parent = metadata.getFirstChild();
        return toString(parent, 0);
    }
    
    protected String toString(Node node, int indent) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            return null;
        }
        StringBuffer buf = new StringBuffer("");
        for (int i = 0; i < indent; i++) {
            buf.append("\t");
        }
        String value = node.getNodeValue();
        if (value == null) {
            NodeList children = node.getChildNodes();
            if (children != null && children.getLength() == 1)
            {
                value = children.item(0).getNodeValue();
            }
        }
        buf.append("{")
                .append("type:" + node.getNodeType())
                .append(", namespace:" + node.getNamespaceURI())
                .append(", prefix:" + node.getPrefix())
                .append(", nodeName:" + node.getNodeName())
                .append(", name:\"" + getAttribute(node, DOM_ATTRIBUTE_NAME) + "\"")
                .append(", textValue:\"" + value +"\"")
                .append("}");
        if (node.hasChildNodes()) {
            NodeList list = node.getChildNodes();
            for (int i=0; i < list.getLength(); i++) {
                Node child = list.item(i);
                String nodeOutput = toString(child, indent + 1);
                if (nodeOutput != null) {
                    buf.append("\n").append(nodeOutput);
                }
            }
        }
        return buf.toString();
    }

}
