package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.util.Map;

/**
 * Validates an HTTP response body using an XPath 1.0 expression.
 *
 * <p>Reads the {@code xpath} parameter containing the XPath expression.
 * The response body is parsed as XML and the expression is evaluated.
 * The result is considered a PASS if:
 * <ul>
 *   <li>Node-set: non-empty</li>
 *   <li>String: non-empty</li>
 *   <li>Boolean: {@code true}</li>
 *   <li>Number: non-zero</li>
 * </ul>
 *
 * <p>XML parse errors and XPath evaluation errors result in FAIL.
 * External entity processing is disabled to prevent XXE attacks.
 */
@Slf4j
public class XPathValidator implements Validator {

    private static final String ALIAS = "xpath";

    /**
     * Error handler that silently swallows parse errors. The validator catches
     * the resulting {@link SAXException} and returns {@code FAIL} with the error
     * message — the default handler's {@code [Fatal Error]} stderr output is
     * suppressed.
     */
    private static final ErrorHandler SILENT_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) throws SAXException {
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private final DocumentBuilderFactory documentBuilderFactory;

    /**
     * Creates a new XPath validator with XXE-protected document builder factory.
     */
    public XPathValidator() {
        this.documentBuilderFactory = createSecureFactory();
    }

    /**
     * Creates a {@link DocumentBuilderFactory} with XXE protections enabled.
     */
    private static DocumentBuilderFactory createSecureFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            log.warn("Could not configure all XXE protections: {}", e.getMessage());
        }
        return factory;
    }

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public ValidationResult validate(MonitorResult result, Map<String, String> params) {
        String xpathExpr = params.get(ALIAS);
        if (xpathExpr == null || xpathExpr.isBlank()) {
            return ValidationResult.fail(
                    "Validator 'xpath' is configured but parameter 'xpath' is missing");
        }

        String body = result.getResponseBody();
        if (body == null || body.isBlank()) {
            return ValidationResult.fail("Response body is empty; cannot evaluate XPath");
        }

        Document document;
        try {
            DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
            // Suppress default ErrorHandler that prints [Fatal Error] to stderr;
            // parse errors are caught and returned as ValidationResult.FAIL
            builder.setErrorHandler(SILENT_ERROR_HANDLER);
            document = builder.parse(new InputSource(new StringReader(body)));
        } catch (Exception e) {
            return ValidationResult.fail("XML parse error: " + e.getMessage());
        }

        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            XPathExpression expression = xpath.compile(xpathExpr);

            // XPath 1.0 boolean coercion handles all result types correctly:
            //   non-empty node-set → true, non-empty string → true,
            //   true → true, non-zero number → true.
            // This covers the spec: "non-empty node-set/string/true/non-zero = PASS"
            Boolean boolResult = (Boolean) expression.evaluate(document, XPathConstants.BOOLEAN);
            if (Boolean.TRUE.equals(boolResult)) {
                return ValidationResult.pass();
            }

            return ValidationResult.fail(
                    "XPath expression '" + xpathExpr + "' did not match (evaluated to false)");

        } catch (XPathExpressionException e) {
            return ValidationResult.fail(
                    "XPath evaluation error for '" + xpathExpr + "': " + e.getMessage());
        }
    }
}