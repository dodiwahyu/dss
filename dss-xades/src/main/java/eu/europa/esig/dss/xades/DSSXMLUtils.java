/**
 * DSS - Digital Signature Services
 * Copyright (C) 2015 European Commission, provided under the CEF programme
 * 
 * This file is part of the "DSS - Digital Signature Services" project.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package eu.europa.esig.dss.xades;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.apache.xml.security.c14n.CanonicalizationException;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.exceptions.XMLSecurityException;
import org.apache.xml.security.exceptions.XMLSecurityRuntimeException;
import org.apache.xml.security.keys.KeyInfo;
import org.apache.xml.security.signature.Reference;
import org.apache.xml.security.signature.ReferenceNotInitializedException;
import org.apache.xml.security.transforms.Transforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import eu.europa.esig.dss.DomUtils;
import eu.europa.esig.dss.definition.AbstractPaths;
import eu.europa.esig.dss.definition.DSSElement;
import eu.europa.esig.dss.definition.xmldsig.XMLDSigAttribute;
import eu.europa.esig.dss.definition.xmldsig.XMLDSigPaths;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.model.Digest;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.xades.definition.XAdESNamespaces;
import eu.europa.esig.dss.xades.definition.XAdESPaths;
import eu.europa.esig.dss.xades.definition.xades111.XAdES111Paths;
import eu.europa.esig.dss.xades.definition.xades132.XAdES132Element;
import eu.europa.esig.dss.xades.signature.PrettyPrintTransformer;
import eu.europa.esig.xmldsig.XSDAbstractUtils;

/**
 * Utility class that contains some XML related method.
 *
 */
public final class DSSXMLUtils {

	private static final Logger LOG = LoggerFactory.getLogger(DSSXMLUtils.class);

	private static final Set<String> transforms;

	private static final Set<String> canonicalizers;
	
	private static final String TRANSFORMATION_EXCLUDE_SIGNATURE = "not(ancestor-or-self::ds:Signature)";
	private static final String TRANSFORMATION_XPATH_NODE_NAME = "XPath";
	
	/**
	 * This is the default canonicalization method for XMLDSIG used for signatures and timestamps (see XMLDSIG 4.4.3.2). 
	 * 
	 * Another complication arises because of the way that the default canonicalization algorithm handles namespace declarations; 
	 * frequently a signed XML document needs to be embedded in another document; 
	 * in this case the original canonicalization algorithm will not yield the same result 
	 * as if the document is treated alone. For this reason, the so-called Exclusive Canonicalization,
	 * which serializes XML namespace declarations independently of the surrounding XML, was created.
	 */
	public static final String DEFAULT_CANONICALIZATION_METHOD = CanonicalizationMethod.EXCLUSIVE;
	
	static {
		SantuarioInitializer.init();

		transforms = new HashSet<>();
		registerDefaultTransforms();

		canonicalizers = new HashSet<>();
		registerDefaultCanonicalizers();
	}

	/**
	 * This method registers the default transforms.
	 */
	private static void registerDefaultTransforms() {

		registerTransform(Transforms.TRANSFORM_BASE64_DECODE);
		registerTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
		registerTransform(Transforms.TRANSFORM_XPATH);
		registerTransform(Transforms.TRANSFORM_XPATH2FILTER);
		registerTransform(Transforms.TRANSFORM_XPOINTER);
		registerTransform(Transforms.TRANSFORM_XSLT);
	}

	/**
	 * This method registers the default canonicalizers.
	 */
	private static void registerDefaultCanonicalizers() {

		registerCanonicalizer(Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);
		registerCanonicalizer(Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
		registerCanonicalizer(Canonicalizer.ALGO_ID_C14N11_OMIT_COMMENTS);
		registerCanonicalizer(Canonicalizer.ALGO_ID_C14N_PHYSICAL);
		registerCanonicalizer(Canonicalizer.ALGO_ID_C14N_WITH_COMMENTS);
		registerCanonicalizer(Canonicalizer.ALGO_ID_C14N_EXCL_WITH_COMMENTS);
		registerCanonicalizer(Canonicalizer.ALGO_ID_C14N11_WITH_COMMENTS);
	}

	/**
	 * This class is an utility class and cannot be instantiated.
	 */
	private DSSXMLUtils() {
	}

	/**
	 * This method allows to register a transformation.
	 *
	 * @param transformURI
	 *            the URI of transform
	 * @return true if this set did not already contain the specified element
	 */
	public static boolean registerTransform(final String transformURI) {
		final boolean added = transforms.add(transformURI);
		return added;
	}

	/**
	 * This method allows to register a canonicalizer.
	 *
	 * @param c14nAlgorithmURI
	 *            the URI of canonicalization algorithm
	 * @return true if this set did not already contain the specified element
	 */
	public static boolean registerCanonicalizer(final String c14nAlgorithmURI) {
		final boolean added = canonicalizers.add(c14nAlgorithmURI);
		return added;
	}
	
	/**
	 * Indents the given node and replaces it with a new one on the document
	 * @param document {@link Document} to indent the node in
	 * @param node {@link Node} to be indented
	 * @return the indented {@link Node}
	 */
	public static Node indentAndReplace(final Document document, Node node) {
		Node indentedNode = getIndentedNode(document, node);
		Node importedNode = document.importNode(indentedNode, true);
		node.getParentNode().replaceChild(importedNode, node);
		return importedNode;
	}
	
	/**
	 * Extends the given oldNode by appending new indented childs from the given newNode
	 * @param document owner {@link Document} of the node
	 * @param newNode new {@link Node} to indent
	 * @param oldNode old {@link Node} to extend with new indented elements
	 * @return the extended {@link Node}
	 */
	public static Node indentAndExtend(final Document document, Node newNode, Node oldNode) {
		Node indentedNode = getIndentedNode(document, newNode);
		indentedNode = alignChildrenIndents(indentedNode);
		Node importedNode = document.importNode(indentedNode, true);
		NodeList nodeList = importedNode.getChildNodes();
		for (int i = getPositionToStartExtension(oldNode, importedNode); i < nodeList.getLength(); i++) {
			Node nodeToAppend = nodeList.item(i).cloneNode(true);
			if (Node.ELEMENT_NODE != nodeToAppend.getNodeType() || !checkIfExists(oldNode, nodeToAppend)) {
				oldNode.appendChild(nodeToAppend);
			}
		}
		newNode.getParentNode().replaceChild(oldNode, newNode);
		return oldNode;
	}
	
	private static int getPositionToStartExtension(Node oldNode, Node indentedNode) {
		NodeList nodeList = oldNode.getChildNodes();
		int startPosition = nodeList.getLength();
		Node child = null;
		while(oldNode.hasChildNodes()) {
			child = oldNode.getLastChild();
			if (Node.TEXT_NODE == child.getNodeType()) {
				oldNode.removeChild(child);
			} else {
				break;
			}
		}
		Integer position = getPosition(indentedNode, child);
		if (position != null) {
			return position;
		}
		return startPosition;
	}
	
	private static boolean checkIfExists(Node parentNode, Node childToCheck) {
		return getPosition(parentNode, childToCheck) != null;
	}
	
	private static Integer getPosition(Node parentNode, Node childToCheck) {
		if (parentNode != null && childToCheck != null) {
			String nodeName = childToCheck.getLocalName();
			NodeList newNodeChildList = parentNode.getChildNodes();
			for (int i = 0; i < newNodeChildList.getLength(); i++) {
				Node newChildNode = newNodeChildList.item(i);
				if (nodeName.equals(newChildNode.getLocalName())) {
					String idIdentifier = getIDIdentifier(childToCheck);
					if (idIdentifier == null || idIdentifier.equals(getIDIdentifier(newChildNode))) {
						return i + 1;
					}
				}
			}
		}
		return null;
	}
	
	public static Document getDocWithIndentedSignatures(final Document documentDom, String signatureId, List<String> noIndentObjectIds) {
		NodeList signatures = DomUtils.getNodeList(documentDom, XMLDSigPaths.ALL_SIGNATURES_PATH);
		for (int i = 0; i < signatures.getLength(); i++) {
			Element signature = (Element) signatures.item(i);
			String signatureAttrIdValue = getIDIdentifier(signature);
			if (Utils.isStringNotEmpty(signatureAttrIdValue) && signatureAttrIdValue.contains(signatureId)) {
				Node unsignedSignatureProperties = DomUtils.getNode(signature,
						AbstractPaths.allFromCurrentPosition(XAdES132Element.UNSIGNED_SIGNATURE_PROPERTIES));
				Node indentedSignature = getIndentedSignature(signature, noIndentObjectIds);
				Node importedSignature = documentDom.importNode(indentedSignature, true);
				signature.getParentNode().replaceChild(importedSignature, signature);
				if (unsignedSignatureProperties != null) {
					Node newUnsignedSignatureProperties = DomUtils.getNode(signature,
							AbstractPaths.allFromCurrentPosition(XAdES132Element.UNSIGNED_SIGNATURE_PROPERTIES));
					newUnsignedSignatureProperties.getParentNode().replaceChild(unsignedSignatureProperties, newUnsignedSignatureProperties);
				}
			}
		}
		return documentDom;
	}
	
	private static Node getIndentedSignature(final Node signature, List<String> noIndentObjectIds) {
		Node indentedSignature = getIndentedNode(signature);
		NodeList sigChildNodes = signature.getChildNodes();
		for (int i = 0; i < sigChildNodes.getLength(); i++) {
			Node childNode = sigChildNodes.item(i);
			if (childNode.getNodeType() == Node.ELEMENT_NODE) {
				Element sigChild = (Element) childNode;
				String idAttribute = getIDIdentifier(sigChild);
				if (noIndentObjectIds.contains(idAttribute)) {
					Node nodeToReplace = DomUtils.getNode(indentedSignature, ".//*" + DomUtils.getXPathByIdAttribute(idAttribute));
					Node importedNode = indentedSignature.getOwnerDocument().importNode(sigChild, true);
					indentedSignature.replaceChild(importedNode, nodeToReplace);
				}
			}
		}
		return indentedSignature;
	}
	
	/**
	 * Returns an indented xmlNode
	 * @param documentDom is an owner {@link Document} of the xmlNode
	 * @param xmlNode {@link Node} to indent
	 * @return an indented {@link Node} xmlNode
	 */
	public static Node getIndentedNode(final Node documentDom, final Node xmlNode) {
		NodeList signatures = DomUtils.getNodeList(documentDom, XMLDSigPaths.ALL_SIGNATURES_PATH);

		String pathAllFromCurrentPosition = null;
		// TODO handle by namespace
		DSSElement element = XAdES132Element.fromTagName(xmlNode.getLocalName());
		if (element != null) {
			pathAllFromCurrentPosition = AbstractPaths.allFromCurrentPosition(element);
		} else {
			pathAllFromCurrentPosition = ".//" + xmlNode.getNodeName();
		}

		for (int i = 0; i < signatures.getLength(); i++) {
			Node signature = signatures.item(i);
			NodeList candidateList;
			String idAttribute = getIDIdentifier(xmlNode);
			if (idAttribute != null) {
				candidateList = DomUtils.getNodeList(signature, ".//*" + DomUtils.getXPathByIdAttribute(idAttribute));
			} else {
				candidateList = DomUtils.getNodeList(signature, pathAllFromCurrentPosition);
			}
			if (isNodeListContains(candidateList, xmlNode)) {
				Node indentedSignature = getIndentedNode(signature);
				Node indentedXmlNode;
				if (idAttribute != null) {
					indentedXmlNode = DomUtils.getNode(indentedSignature, ".//*" + DomUtils.getXPathByIdAttribute(idAttribute));
				} else {
					indentedXmlNode = DomUtils.getNode(indentedSignature, pathAllFromCurrentPosition);
				}
				if (indentedXmlNode != null) {
					return indentedXmlNode;
				}
			}
		}
		return xmlNode;
	}
	
	private static Node getIndentedNode(final Node xmlNode) {
		PrettyPrintTransformer prettyPrintTransformer = new PrettyPrintTransformer();
		return prettyPrintTransformer.transform(xmlNode);
	}
	
	private static boolean isNodeListContains(final NodeList nodeList, final Node node) {
		for (int i = 0; i < nodeList.getLength(); i++) {
			Node child = nodeList.item(i);
			if (child == node) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Aligns indents for all children of the given node
	 * @param parentNode {@link Node} to align children into
	 * @return the given {@link Node} with aligned children
	 */
	public static Node alignChildrenIndents(Node parentNode) {
		if (parentNode.hasChildNodes()) {
			NodeList nodeChildren = parentNode.getChildNodes();
			String targetIndent = getTargetIndent(nodeChildren);
			if (targetIndent != null) {
				for (int i = 0; i < nodeChildren.getLength() - 1; i++) {
					Node node = nodeChildren.item(i);
					if (Node.TEXT_NODE == node.getNodeType()) {
						node.setNodeValue(targetIndent);
					}
				}
				Node lastChild = parentNode.getLastChild();
				targetIndent = targetIndent.substring(0, targetIndent.length() - DomUtils.TRANSFORMER_INDENT_NUMBER);
				switch (lastChild.getNodeType()) {
				case Node.ELEMENT_NODE:
					DomUtils.setTextNode(parentNode.getOwnerDocument(), (Element) parentNode, targetIndent);
					break;
				case Node.TEXT_NODE:
					lastChild.setNodeValue(targetIndent);
					break;
				default:
					break;
				}
			}
		}
		return parentNode;
	}
	
	private static String getTargetIndent(NodeList nodeChildren) {
		for (int i = 0; i < nodeChildren.getLength() - 1; i++) {
			Node node = nodeChildren.item(i);
			if (Node.TEXT_NODE == node.getNodeType()) {
				return node.getNodeValue();
			}
		}
		return null;
	}

	/**
	 * This method performs the serialization of the given node
	 *
	 * @param xmlNode
	 *            The node to be serialized.
	 * @return the serialized bytes
	 */
	public static byte[] serializeNode(final Node xmlNode) {
		try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
			Transformer transformer = DomUtils.getSecureTransformer();
			Document document = null;
			if (Node.DOCUMENT_NODE == xmlNode.getNodeType()) {
				document = (Document) xmlNode;
			} else {
				document = xmlNode.getOwnerDocument();
			}

			if (document != null) {
				String xmlEncoding = document.getXmlEncoding();
				if (Utils.isStringNotBlank(xmlEncoding)) {
					transformer.setOutputProperty(OutputKeys.ENCODING, xmlEncoding);
				}
			}

			StreamResult result = new StreamResult(bos);
			Source source = new DOMSource(xmlNode);
			transformer.transform(source, result);
			
			return bos.toByteArray();
		} catch (Exception e) {
			throw new DSSException("An error occurred during a node serialization.", e);
		}
	}

	/**
	 * This method says if the framework can canonicalize an XML data with the provided method.
	 *
	 * @param canonicalizationMethod
	 *            the canonicalization method to be checked
	 * @return true if it is possible to canonicalize false otherwise
	 */
	public static boolean canCanonicalize(final String canonicalizationMethod) {
		return canonicalizers.contains(canonicalizationMethod);
	}

	/**
	 * This method canonicalizes the given array of bytes using the {@code canonicalizationMethod} parameter.
	 *
	 * @param canonicalizationMethod
	 *            canonicalization method
	 * @param toCanonicalizeBytes
	 *            array of bytes to canonicalize
	 * @return array of canonicalized bytes
	 * @throws DSSException
	 *             if any error is encountered
	 */
	public static byte[] canonicalize(final String canonicalizationMethod, final byte[] toCanonicalizeBytes) throws DSSException {
		try {
			final Canonicalizer c14n = Canonicalizer.getInstance(getCanonicalizationMethod(canonicalizationMethod));
			return c14n.canonicalize(toCanonicalizeBytes);
		} catch (Exception e) {
			throw new DSSException("Cannot canonicalize the binaries", e);
		}
	}

	/**
	 * This method canonicalizes the given {@code Node}.
	 * If canonicalization method is not provided, the {@code DEFAULT_CANONICALIZATION_METHOD} is being used
	 *
	 * @param canonicalizationMethod
	 *            canonicalization method (can be null)
	 * @param node
	 *            {@code Node} to canonicalize
	 * @return array of canonicalized bytes
	 */
	public static byte[] canonicalizeSubtree(String canonicalizationMethod, final Node node) {
		try {
			final Canonicalizer c14n = Canonicalizer.getInstance(getCanonicalizationMethod(canonicalizationMethod));
			return c14n.canonicalizeSubtree(node);
		} catch (Exception e) {
			throw new DSSException("Cannot canonicalize the subtree", e);
		}
	}
	
	/**
	 * Returns the {@code canonicalizationMethod} if provided, otherwise returns the DEFAULT_CANONICALIZATION_METHOD
	 * 
	 * @param canonicalizationMethod {@link String} canonicalization method (can be null)
	 * @return canonicalizationMethod to be used
	 */
	public static String getCanonicalizationMethod(String canonicalizationMethod) {
		if (Utils.isStringEmpty(canonicalizationMethod)) {
			LOG.warn("Canonicalization method is not defined. A default canonicalization '{}' will be used.", DEFAULT_CANONICALIZATION_METHOD);
			return DEFAULT_CANONICALIZATION_METHOD;
		}
		return canonicalizationMethod;
	}

	/**
	 * An ID attribute can only be dereferenced if it is declared in the validation context. This behaviour is caused by
	 * the fact that the attribute does not have attached type of information. Another solution is to parse the XML
	 * against some DTD or XML schema. This process adds the necessary type of information to each ID attribute.
	 *
	 * @param element
	 */
	public static void recursiveIdBrowse(final Element element) {

		for (int ii = 0; ii < element.getChildNodes().getLength(); ii++) {

			final Node node = element.getChildNodes().item(ii);
			if (node.getNodeType() == Node.ELEMENT_NODE) {

				final Element childElement = (Element) node;
				setIDIdentifier(childElement);
				recursiveIdBrowse(childElement);
			}
		}
	}

	/**
	 * If this method finds an attribute with the name ID (case-insensitive) then it is
	 * returned. If there is more than one ID attributes then the first one is
	 * returned.
	 *
	 * @param node
	 *             the node to be checked
	 * @return the ID attribute value or null
	 */
	public static String getIDIdentifier(final Node node) {
		return getAttribute(node, XMLDSigAttribute.ID.getAttributeName());
	}
	
	/**
	 * Returns attribute value for the given attribute name if exist, otherwise returns NULL
	 * @param node {@link Node} to get attribute value from
	 * @param attributeName {@link String} name of the attribute to get value for
	 * @return {@link String} value of the attribute
	 */
	public static String getAttribute(final Node node, final String attributeName) {
		final NamedNodeMap attributes = node.getAttributes();
		for (int jj = 0; jj < attributes.getLength(); jj++) {
			final Node item = attributes.item(jj);
			final String localName = item.getLocalName() != null ? item.getLocalName() : item.getNodeName();
			if (localName != null) {
				if (Utils.areStringsEqualIgnoreCase(attributeName, localName)) {
					return item.getTextContent();
				}
			}
		}
		return null;
	}

	/**
	 * If this method finds an attribute with names ID (case-insensitive) then declares it to be a user-determined ID
	 * attribute.
	 *
	 * @param childElement
	 */
	public static void setIDIdentifier(final Element childElement) {

		final NamedNodeMap attributes = childElement.getAttributes();
		for (int jj = 0; jj < attributes.getLength(); jj++) {

			final Node item = attributes.item(jj);
			final String localName = item.getLocalName();
			final String nodeName = item.getNodeName();
			if (localName != null) {
				if (Utils.areStringsEqualIgnoreCase(XMLDSigAttribute.ID.getAttributeName(), localName)) {
					childElement.setIdAttribute(nodeName, true);
					break;
				}
			}
		}
	}

	/**
	 * This method allows to validate an XML against the XAdES XSD schema.
	 *
	 * @param xsdUtils
	 *                 the XSD Utils class to be used
	 * @param source
	 *                 {@code Source} XML to validate
	 * @return null if the XSD validates the XML, error message otherwise
	 */
	public static String validateAgainstXSD(XSDAbstractUtils xsdUtils, final Source source) {
		return xsdUtils.validateAgainstXSD(source);
	}

	public static boolean isOid(String policyId) {
		return policyId != null && policyId.matches("^(?i)urn:oid:.*$");
	}
	
	/**
	 * Keeps only code of the oid string
	 * e.g. "urn:oid:1.2.3" to "1.2.3"
	 * @param oid {@link String} Oid
	 * @return Oid Code
	 */
	public static String getOidCode(String oid) {
		if (oid == null) {
			return null;
		}
		return oid.substring(oid.lastIndexOf(':') + 1);
	}

	/**
	 * This method is used to detect duplicate id values
	 * 
	 * @param doc
	 *            the document to be analyzed
	 * @return TRUE if a duplicate id is detected
	 */
	public static boolean isDuplicateIdsDetected(DSSDocument doc) {
		try {
			Document dom = DomUtils.buildDOM(doc);
			Element root = dom.getDocumentElement();
			recursiveIdBrowse(root);
			XPathExpression xPathExpression = DomUtils.createXPathExpression("//*/@*");
			NodeList nodeList = (NodeList) xPathExpression.evaluate(root, XPathConstants.NODESET);
			for (int i = 0; i < nodeList.getLength(); i++) {
				Attr attr = (Attr) nodeList.item(i);
				if (Utils.areStringsEqualIgnoreCase(XMLDSigAttribute.ID.getAttributeName(), attr.getName())) {
					XPathExpression xpathAllById = DomUtils.createXPathExpression("//*[@" + attr.getName() + "='" + attr.getValue() + "']");
					NodeList nodeListById = (NodeList) xpathAllById.evaluate(root, XPathConstants.NODESET);
					if (nodeListById.getLength() != 1) {
						LOG.warn("Problem detected with Id '{}', nb occurences = {}", attr.getValue(), nodeListById.getLength());
						return true;
					}
				}
			}
		} catch (XPathExpressionException e) {
			throw new DSSException("Unable to check if duplicate ids are present", e);
		}
		return false;
	}
	
	/**
	 * Returns bytes of the given {@code node}
	 * @param node {@link Node} to get bytes for
	 * @return byte array
	 */
	public static byte[] getNodeBytes(Node node) {
		if (node.getNodeType() == Node.ELEMENT_NODE) {
			byte[] bytes = serializeNode(node);
			String str = new String(bytes);
			// TODO: better
			// remove <?xml version="1.0" encoding="UTF-8"?>
			if (str.startsWith("<?")) {
				str = str.substring(str.indexOf("?>") + 2);
			}
			return str.getBytes();
		} else if (node.getNodeType() == Node.TEXT_NODE) {
			String textContent = node.getTextContent();
			if (Utils.isBase64Encoded(textContent)) {
				return Utils.fromBase64(node.getTextContent());
			} else {
				return textContent.getBytes();
			}
		}
		return null;
	}
	
	/**
	 * Returns bytes of the original referenced data
	 * @param reference {@link Reference} to get bytes from
	 * @return byte array containing original data
	 */
	public static byte[] getReferenceOriginalContentBytes(Reference reference) {
		
		try {
			// returns bytes after transformation in case of enveloped signature
			Transforms transforms = reference.getTransforms();
			if (transforms != null) {
				Element transformsElement = transforms.getElement();
				NodeList transformChildNodes = transformsElement.getChildNodes();
				if (transformChildNodes != null && transformChildNodes.getLength() > 0) {
					for (int i = 0; i < transformChildNodes.getLength(); i++) {
						Node transformation = transformChildNodes.item(i);
						if (isEnvelopedTransform(transformation)) {
							return reference.getReferencedBytes();
						}
					    // if enveloped transformations are not applied to the signature go further and 
						// return bytes before transformation
					}
				}
			}
			
		} catch (XMLSecurityException | XMLSecurityRuntimeException e) {
			// if exception occurs during the transformations
			LOG.warn("Signature reference with id [{}] is corrupted or has an invalid format. "
					+ "Original data cannot be obtained. Reason: [{}]", reference.getId(), e.getMessage());
			
		}
		// otherwise bytes before transformation
		return getBytesBeforeTransformation(reference);
	}
	
	private static boolean isEnvelopedTransform(Node transformation) {
		final String algorithm = DomUtils.getValue(transformation, "@Algorithm");
		if (Transforms.TRANSFORM_ENVELOPED_SIGNATURE.equals(algorithm)) {
			return true;
		} else if (Transforms.TRANSFORM_XPATH.equals(algorithm) || 
				Transforms.TRANSFORM_XPATH2FILTER.equals(algorithm)) {
			NodeList childNodes = transformation.getChildNodes();
			for (int j = 0; j < childNodes.getLength(); j++) {
				Node item = childNodes.item(j);
				if (Node.ELEMENT_NODE == item.getNodeType() && TRANSFORMATION_XPATH_NODE_NAME.equals(item.getLocalName()) &&
						TRANSFORMATION_EXCLUDE_SIGNATURE.equals(item.getTextContent())) {
					return true;
				}
			}
		}
		return false;
	}
	
	private static byte[] getBytesBeforeTransformation(Reference reference) {
		try {
			return reference.getContentsBeforeTransformation().getBytes();
		} catch (ReferenceNotInitializedException e) {
			// if exception occurs during an attempt to access reference original data
			LOG.warn("Original data is not provided for the reference with id [{}]. Reason: [{}]", reference.getId(), e.getMessage());
		} catch (IOException | CanonicalizationException e) {
			// if exception occurs by another reason
			LOG.error("Unable to retrieve the content of reference with id [{}].", reference.getId(), e);
		}
		// in case of exceptions return null value
		return null;
	}

	/**
	 * This method extracts the Digest algorithm and value from an element of type
	 * DigestAlgAndValueType
	 * 
	 * @param element
	 *                an Element of type DigestAlgAndValueType
	 * @return an instance of Digest
	 */
	public static Digest getDigestAndValue(Element element) {
		if (element == null) {
			return null;
		}

		String digestAlgorithmUri = null;
		String digestValueBase64 = null;
		if (XAdESNamespaces.XADES_111.isSameUri(element.getNamespaceURI())) {
			digestAlgorithmUri = DomUtils.getValue(element, XAdES111Paths.DIGEST_METHOD_ALGORITHM_PATH);
			digestValueBase64 = DomUtils.getValue(element, XAdES111Paths.DIGEST_VALUE_PATH);
		} else {
			digestAlgorithmUri = DomUtils.getValue(element, XMLDSigPaths.DIGEST_METHOD_ALGORITHM_PATH);
			digestValueBase64 = DomUtils.getValue(element, XMLDSigPaths.DIGEST_VALUE_PATH);
		}

		final DigestAlgorithm digestAlgorithm = getDigestAlgorithm(digestAlgorithmUri);
		final byte[] digestValue = getDigestValue(digestValueBase64);

		if (digestAlgorithm == null || digestValue == null) {
			LOG.warn("Unable to read object DigestAlgAndValueType (XMLDSig or XAdES 1.1.1)");
			return null;
		} else {
			return new Digest(digestAlgorithm, digestValue);
		}

	}

	private static byte[] getDigestValue(String digestValueBase64) {
		byte[] result = null;
		if (Utils.isStringNotEmpty(digestValueBase64)) {
			result = Utils.fromBase64(digestValueBase64);
		}
		return result;
	}

	private static DigestAlgorithm getDigestAlgorithm(String digestAlgorithmUri) {
		DigestAlgorithm result = null;
		if (Utils.isStringNotEmpty(digestAlgorithmUri)) {
			try {
				result = DigestAlgorithm.forXML(digestAlgorithmUri);
			} catch (IllegalArgumentException e) {
				LOG.warn("Unable to retrieve the used digest algorithm", e);
			}
		}
		return result;
	}

	/**
	 * Determines if the given {@code reference} refers to SignedProperties element
	 * @param reference {@link Reference} to check
	 * @return TRUE if the reference refers to the SignedProperties, FALSE otherwise
	 */
	public static boolean isSignedProperties(final Reference reference, final XAdESPaths xadesPaths) {
		return xadesPaths.getSignedPropertiesUri().equals(reference.getType());
	}

	/**
	 * Determines if the given {@code reference} refers to CounterSignature element
	 * @param reference {@link Reference} to check
	 * @return TRUE if the reference refers to the CounterSignature, FALSE otherwise
	 */
	public static boolean isCounterSignature(final Reference reference, final XAdESPaths xadesPaths) {
		return xadesPaths.getCounterSignatureUri().equals(reference.getType());
	}
	
	/**
	 * Checks if the given reference is linked to a KeyInfo element
	 * 
	 * @param reference
	 *                  the {@link Reference} to check
	 * @param signature
	 *                  the {@link Element} signature the given reference belongs to
	 * @return TRUE if the reference is a KeyInfo reference, FALSE otherwise
	 */
	public static boolean isKeyInfoReference(final Reference reference, final Element signature) {
		String uri = reference.getURI();
		uri = DomUtils.getId(uri);
		Element element = DomUtils.getElement(signature, XMLDSigPaths.KEY_INFO_PATH + DomUtils.getXPathByIdAttribute(uri));
		if (element != null) {
			return true;
		}
		return false;
	}
	
	/**
	 * Checks if the given {@code referenceType} is an xmldsig Object type
	 * @param referenceType {@link String} to check the type for
	 * @return TRUE if the provided {@code referenceType} is an Object type, FALSE otherwise
	 */
	public static boolean isObjectReferenceType(String referenceType) {
		return XMLDSigPaths.OBJECT_TYPE.equals(referenceType);
	}
	
	/**
	 * Checks if the given {@code referenceType} is an xmldsig Manifest type
	 * @param referenceType {@link String} to check the type for
	 * @return TRUE if the provided {@code referenceType} is a Manifest type, FALSE otherwise
	 */
	public static boolean isManifestReferenceType(String referenceType) {
		return XMLDSigPaths.MANIFEST_TYPE.equals(referenceType);
	}
	
	/**
	 * Checks if the given {@code referenceType} is an etsi Countersignature type
	 * @param referenceType {@link String} to check the type for
	 * @return TRUE if the provided {@code referenceType} is a Countersignature type, FALSE otherwise
	 */
	public static boolean isCounterSignatureReferenceType(String referenceType) {
		return XMLDSigPaths.COUNTER_SIGNATURE_TYPE.equals(referenceType);
	}
	
	/**
	 * Extracts signing certificate's public key from KeyInfo element of a given signature if present
	 * NOTE: can return null (the value is optional)
	 * 
	 * @param signatureElement {@link Element} representing a signature to get KeyInfo signing certificate for
	 * @return {@link PublicKey} of the signature extracted from KeyInfo element if present
	 */
	public static PublicKey getKeyInfoSigningCertificatePublicKey(final Element signatureElement) {
		Element keyInfoElement = DomUtils.getElement(signatureElement, XMLDSigPaths.KEY_INFO_PATH);
		if (keyInfoElement != null) {
			try {
				KeyInfo keyInfo = new KeyInfo(keyInfoElement, "");
				return keyInfo.getPublicKey();
			} catch (XMLSecurityException e) {
				LOG.warn("Unable to extract signing certificate's public key. Reason : {}", e.getMessage(), e);
			}
		}
		LOG.warn("Unable to extract the public key. Reason : KeyInfo element is null");
		return null;
	}

}
