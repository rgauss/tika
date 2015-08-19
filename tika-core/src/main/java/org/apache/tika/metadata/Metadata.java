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
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.tika.metadata.Property.PropertyType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

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
     * An XPath object for querying metadata
     */
    private transient XPath xPath;

    /**
     * A namespace context or registry
     */
    private NamespaceContextImpl namespaceContext;

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

    private static final String DOM_TIKA_NAMESPACE = "http://tika.apache.org/";
    private static final String DOM_TIKA_NAMESPACE_PREFIX = "tika";
    private static final String DOM_NODE_ROOT_LOCAL_NAME = "metadata";
    private static final String DOM_NODE_ENTRY_LOCAL_NAME = "entry";
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
     * Converts the given simple name to a QName with no namespaceURI.
     * <p>
     * If the name contains a single ":" delimiter then the first part
     * is used as as the prefix and the second as the localPart.
     * 
     * @param name
     * @return the converted QName
     */
    protected static QName convertToQName(String name) {
        if (name == null) {
            return null;
        }
        String namespacePrefix = XMLConstants.DEFAULT_NS_PREFIX;
        String localName = name;
        if (name.contains(Metadata.NAMESPACE_PREFIX_DELIMITER) 
                && name.split(Metadata.NAMESPACE_PREFIX_DELIMITER).length == 2) {
            namespacePrefix = name.split(Metadata.NAMESPACE_PREFIX_DELIMITER)[0];
            localName = name.split(Metadata.NAMESPACE_PREFIX_DELIMITER)[1];
        }
        return new QName(null, localName, namespacePrefix);
    }

    /**
     * Constructs a new, empty metadata.
     */
    public Metadata() {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            xPath =  XPathFactory.newInstance().newXPath();
            namespaceContext = new NamespaceContextImpl();
            namespaceContext.register(DOM_TIKA_NAMESPACE_PREFIX, DOM_TIKA_NAMESPACE);
            xPath.setNamespaceContext(namespaceContext);
            
            metadata = documentBuilder.newDocument();
            Node rootNode = metadata.createElementNS(
                    DOM_TIKA_NAMESPACE, 
                    DOM_TIKA_NAMESPACE_PREFIX + NAMESPACE_PREFIX_DELIMITER + DOM_NODE_ROOT_LOCAL_NAME);
            metadata.appendChild(rootNode);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Could not instantiate metadata store", e);
        }
    }

    /**
     * Creates a DOM element node from the underlying metadata store
     * 
     * @param qName
     * @return the DOM element node
     */
    public Element createDomElement(QName qName) {
        return metadata.createElementNS(qName.getNamespaceURI(), getQualifiedName(qName));
    }

    /**
     * Creates a DOM text node from the underlying metadata store
     * 
     * @param value
     * @return the DOM text node
     */
    public Text createDomText(String value) {
        return metadata.createTextNode(escapeNodeValue(value));
    }

    /**
     * Returns true if named value is multivalued.
     * 
     * @param property
     *          metadata property
     * @return true is named value is multivalued, false if single value or null
     */
    public boolean isMultiValued(final Property property) {
        return getValues(property.getQName()).length > 1;
    }
    
    /**
     * Returns true if named value is multivalued.
     * 
     * @param name
     *          name of metadata
     * @return true is named value is multivalued, false if single value or null
     */
    public boolean isMultiValued(final String name) {
        return getValues(convertToQName(name)).length > 1;
    }

    /**
     * Returns an array of the names contained in the metadata.
     * 
     * @return Metadata names
     */
    public String[] names() {
        ArrayList<String> names = new ArrayList<String>();
        NodeList rootList = metadata.getElementsByTagNameNS(
                DOM_TIKA_NAMESPACE, DOM_NODE_ROOT_LOCAL_NAME);
        if (rootList == null || rootList.getLength() == 0) {
            // TODO Could not find root node, throw error instead?
            return new String[] {};
        }
        if (rootList.getLength() > 1) {
            // TODO Found more than one root node, throw error?
        }
        Node rootNode = rootList.item(0);
        NodeList list = rootNode.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node item = list.item(i);
            if (item.getNodeType() != Node.TEXT_NODE) {
                String name = getMetadataName(item);
                if (!names.contains(name))
                {
                    names.add(name);
                }
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
     * Determines if the given QName has a non-null namespace URI.
     * 
     * @param qName
     * @return true if the QName is namespaced
     */
    protected static boolean isNamespaced(QName qName) {
        if (qName == null) {
            return false;
        }
        return !qName.getNamespaceURI().equals(XMLConstants.NULL_NS_URI);
    }
    
    /**
     * Gets the qualified name (prefix:localPart) for the given
     * QName.
     * 
     * @param qName
     * @return the qualified name
     */
    protected static String getQualifiedName(QName qName) {
        if (qName == null) {
            return null;
        }
        StringBuffer name = new StringBuffer("");
        if (!qName.getPrefix().equals(XMLConstants.DEFAULT_NS_PREFIX)) {
            name.append(qName.getPrefix()).append(NAMESPACE_PREFIX_DELIMITER);
        }
        return name.append(qName.getLocalPart()).toString();
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
    protected static String escapeNodeValue(String value) {
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
    protected static String unescapeNodeValue(String value) {
        if (value == null) {
            return null;
        }
        return StringEscapeUtils.unescapeXml(value);
    }
    
    /**
     * Gets the metadata name attribute value from the given node
     * 
     * @param node
     * @return the attribute value
     */
    protected static String getMetadataName(Node node) {
        if (node == null) {
            return null;
        }
        if (node.getNodeName().equals(DOM_TIKA_NAMESPACE_PREFIX + NAMESPACE_PREFIX_DELIMITER + DOM_NODE_ENTRY_LOCAL_NAME)) {
            if (!node.hasAttributes()) {
                return null;
            }
            Node attributeNode = 
                    node.getAttributes().getNamedItem(
                            DOM_TIKA_NAMESPACE_PREFIX + NAMESPACE_PREFIX_DELIMITER + DOM_ATTRIBUTE_NAME);
            if (attributeNode == null) {
                return null;
            }
            return attributeNode.getNodeValue();
        } else {
            return node.getNodeName();
        }
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
        return getValue(convertToQName(name));
    }

    /**
     * Returns the value (if any) of the identified metadata property.
     *
     * @since Apache Tika 0.7
     * @param property property definition
     * @return property value, or <code>null</code> if the property is not set
     */
    public String get(Property property) {
        return getValue(property.getQName());
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
        return getValues(property.getQName());
    }

    /**
     * Get the values associated to a metadata name.
     * 
     * @param name
     *          of the metadata.
     * @return the values associated to a metadata name.
     */
    public String[] getValues(final String name) {
        return getValues(convertToQName(name));
    }

    /**
     * Gets or creates the XPath object
     * 
     * @return the XPath object
     */
    protected XPath getXPath() {
        if (xPath == null) {
            xPath = XPathFactory.newInstance().newXPath();
        }
        return xPath;
    }

    /**
     * Gets the metadata String value from the given XPath expression
     * 
     * @param expression
     * @return the string value
     * @throws XPathExpressionException
     */
    public String getValueByXPath(final String expression) throws XPathExpressionException {
        if (expression == null) {
            return null;
        }
        return getXPath().compile(expression).evaluate(metadata);
    }

    /**
     * Gets the metadata DOM nodes for the given preoperty
     * 
     * @param property
     * @return the found DOM nodes
     */
    public Set<Node> getDomNodes(final Property property) {
        if (property == null) {
            return null;
        }
        return findNodes(property.getQName(), -1);
    }

    /**
     * Converts the given QName into an appropriate QName
     * that may be contained in the metadata store.
     * <p>
     * If the given QName has a namespace URI it is simply passed through.
     * <p>
     * If the given QName has no namespace URI but does have a prefix, 
     * a corresponding known, namespaced property is looked for.
     * <p>
     * If no known, namespaced property is found the given QName is converted
     * to a "tika-entry" QName.
     * 
     * @param qName
     * @return the converted QName for the metadata store
     */
    private QName getLookupQName(final QName qName) {
        if (!isNamespaced(qName)) {
            String lookupNamespaceURI = null;
            String lookupLocalPart = null;
            String lookupPrefix = null;
            Property knownProperty = Property.get(getQualifiedName(qName));
            if (knownProperty != null 
                    && !knownProperty.getQName().getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
                lookupNamespaceURI = knownProperty.getQName().getNamespaceURI();
                lookupLocalPart = knownProperty.getQName().getLocalPart();
                lookupPrefix = knownProperty.getQName().getPrefix();
            } else {
                lookupNamespaceURI = DOM_TIKA_NAMESPACE;
                lookupLocalPart = DOM_NODE_ENTRY_LOCAL_NAME;
                lookupPrefix = DOM_TIKA_NAMESPACE_PREFIX;
            }
            return new QName(lookupNamespaceURI, lookupLocalPart, lookupPrefix);
        } else {
            return qName;
        }
    }

    /**
     * Gets elements in the metadata document corresponding to the
     * given QName, size constrained by the given limit.
     * <p>
     * Uses {@link #getLookupQName(QName)} to convert the given QName
     * and inspects tike-entry tika-name attributes where needed.
     * 
     * @param qName
     * @param limit
     * @return the found DOM nodes
     */
    private Set<Node> findNodes(final QName qName, int limit) {
        Set<Node> foundNodes = new LinkedHashSet<Node>();
        if (qName == null) {
            return foundNodes;
        }
        
        QName lookupQName = getLookupQName(qName);
        NodeList list = metadata.getElementsByTagNameNS(
                lookupQName.getNamespaceURI(), lookupQName.getLocalPart());
        for (int i = 0; i < list.getLength(); i++) {
            if (lookupQName.getLocalPart().equals(DOM_NODE_ENTRY_LOCAL_NAME)) {
                String qualifiedName = getQualifiedName(qName);
                String metadataName = getMetadataName(list.item(i));
                if (qualifiedName.equals(metadataName)) {
                    foundNodes.add(list.item(i));
                }
            } else {
                foundNodes.add(list.item(i));
            }
            if (foundNodes.size() == limit) {
                break;
            }
        }
        return foundNodes;
    }
    
    /**
     * Gets the set of values for the given QName.
     *  
     * @param qName
     * @return the values
     */
    private String[] getValues(final QName qName) {
        ArrayList<String> values = new ArrayList<String>();
        Set<Node> foundNodes = findNodes(qName, -1);
        for (Node node : foundNodes) {
            values.add(getNodeValue(node));
        }
        return values.toArray(new String[values.size()]);
    }
    
    /**
     * Gets a single value for the given QName.
     * @param qName
     * @return the value
     */
    private String getValue(final QName qName) {
        Set<Node> foundNodes = findNodes(qName, 1);
        if (foundNodes.size() == 1) {
            return getNodeValue(foundNodes.iterator().next());
        }
        return null;
    }
    
    /**
     * Adds the given value to the given QName's values
     * 
     * @param qName
     * @param value
     */
    private void add(final QName qName, final String value) {
        if (qName == null || value == null) {
            return;
        }
        
        QName lookupQName = getLookupQName(qName);
        
        // Register any namespace added
        if (lookupQName.getPrefix() != XMLConstants.DEFAULT_NS_PREFIX 
                && lookupQName.getNamespaceURI() != XMLConstants.NULL_NS_URI) {
            namespaceContext.register(lookupQName.getPrefix(), lookupQName.getNamespaceURI());
        }
        
        Element element = metadata.createElementNS(
                lookupQName.getNamespaceURI(), 
                getQualifiedName(lookupQName));
        if (lookupQName.getLocalPart().equals(DOM_NODE_ENTRY_LOCAL_NAME)) {
            element.setAttributeNS(
                    DOM_TIKA_NAMESPACE, 
                    DOM_TIKA_NAMESPACE_PREFIX + NAMESPACE_PREFIX_DELIMITER + DOM_ATTRIBUTE_NAME, 
                    getQualifiedName(qName));
        }
        element.appendChild(metadata.createTextNode(escapeNodeValue(value)));
        metadata.getFirstChild().appendChild(element);
    }
    
    /**
     * Sets the given value for the given QName.
     * 
     * @param qName
     * @param value
     */
    private void set(QName qName, String value) {
        remove(qName);
        if (value != null) {
            add(qName, value);
        }
    }
    
    /**
     * Removes all values for the given QName.
     * 
     * @param qName
     */
    private void remove(QName qName) {
        Set<Node> foundNodes = findNodes(qName, -1);
        for (Node node : foundNodes) {
            node.getParentNode().removeChild(node);
        }
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
        add(convertToQName(name), value);
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
                        set(secondaryExtractProperty.getQName(), value);
                    }
                }
            }
        } else {
            String currentValue = get(property);
            if (!property.isMultiValuePermitted() && currentValue != null)
            {
                throw new PropertyTypeException(property.getPropertyType());
            }
            add(property.getQName(), value);
        }
    }

    /**
     * Adds the given DOM element value for the given property to the metadata
     * store
     * 
     * @param property
     * @param value
     */
    public void add(final Property property, final Element value) {
        if (property == null || property.getQName() == null || value == null)
        {
            return;
        }
        // TODO throw type exception on null QName?
        if (property.getQName().getPrefix() != XMLConstants.DEFAULT_NS_PREFIX 
                && property.getQName().getNamespaceURI() != XMLConstants.NULL_NS_URI) {
            namespaceContext.register(property.getQName().getPrefix(), property.getQName().getNamespaceURI());
        }
        metadata.getFirstChild().appendChild(value);
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
        set(convertToQName(name), value);
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
            set(property.getQName(), value);
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
        remove(convertToQName(name));
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
            String[] otherValues = other.getValues(names[i]);
            String[] thisValues = getValues(names[i]);
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
        try {
            DOMSource domSource = new DOMSource(metadata);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer;
            transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(domSource, result);
            return writer.toString();
        } catch (TransformerException e) {
            return "Error serializing metadata: " + e.getMessage();
        }
    }
    
    /**
     * Class for maintaining a namespace registry
     */
    protected class NamespaceContextImpl implements NamespaceContext, Serializable {
        
        private static final long serialVersionUID = -8304252584743304759L;
        
        private Map<String, String> namespaceRegistry = new HashMap<String, String>();
        
        @Override
        public String getNamespaceURI(String prefix) {
            return namespaceRegistry.get(prefix);
        }

        @Override
        public String getPrefix(String namespaceURI) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<?> getPrefixes(String namespaceURI) {
            throw new UnsupportedOperationException();
        }
        
        protected void register(String prefix, String namespaceUri) {
            namespaceRegistry.put(prefix, namespaceUri);
        }
    }
}
