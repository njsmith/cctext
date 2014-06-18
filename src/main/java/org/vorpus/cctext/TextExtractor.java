package org.vorpus.cctext;

import java.lang.Throwable;
import java.lang.StringBuilder;
import java.lang.System;

import java.io.InputStream;
import java.io.StringReader;
import java.io.IOException;

import javax.xml.parsers.SAXParserFactory;

import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.sax.HtmlParser;

import org.xml.sax.XMLReader;
import org.xml.sax.SAXException;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;

import org.apache.commons.lang3.StringUtils;

public class TextExtractor extends DefaultHandler
{
    public static TextExtractor parse(InputStream stream,
                                      boolean xhtml,
                                      String declared_encoding)
        throws Throwable
    {
        XMLReader parser;

        if (xhtml) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            parser = factory.newSAXParser().getXMLReader();
        } else {
            // ALLOW means to follow the HTML5 spec rather than the SAX spec
            // when they differ -- this just means to allow little things like
            // comments containing double-hyphens.
            // XX set any attributes (e.g. sniffing?)
            parser = new HtmlParser(XmlViolationPolicy.ALLOW);
        }


        TextExtractor extractor = new TextExtractor();
        parser.setContentHandler(extractor);
        // It's important to install a null entity resolver, so that the
        // parser won't go trying to download DTDs from the internet.
        parser.setEntityResolver(extractor);

        // Error handling:
        // The SAX API allows parser to report three types of errors: "error",
        // "fatalError", and "warning". In general, "error" and "warning" are
        // recoverable (the parser may keep going), and in fact looking at
        // htmlparser/impl/Tokenizer.java there are tons of calls to error
        // which then get cleaned up after and parsing continues. OTOH there
        // are very few *fatal* errors, and when a fatal error is encountered
        // the parser throws an exception regardless. So we don't bother
        // setting an ErrorHandler.
        // parser.setErrorHandler(XX);

        InputSource source = new InputSource(stream);
        source.setEncoding(declared_encoding);
        parser.parse(source);

        return extractor;
    }

    private int invisibilityDepth = 1;
    private StringBuilder in_progress = new StringBuilder();

    private int depth = 0;

    private String indent()
    {
        return StringUtils.repeat("  ", depth);
    }

    public void startElement(String uri, String localName, String qName,
                      Attributes atts) throws SAXException
    {
        if (localName == "body") {
            invisibilityDepth -= 1;
        }

        // Unicode-aware whitespace normalization: replace \p{IsWhite_Space}+
        // with " ".

        // if uri != "http://www.w3.org/1999/xhtml" then invisibility + 1?
        // I guess it's also possible to embed html inside non-html, though
        // probably it never happens in practice.
        // XX...

        // System.out.format("%s-> uri: %s, localName: %s, qname: %s\n",
        //                   indent(), uri, localName, qName);
        depth += 1;
    }

    public void endElement(String uri, String localName, String qName)
        throws SAXException
    {
        depth -= 1;
        // System.out.format("%s<- uri: %s, localName: %s, qname: %s\n",
        //                   indent(), uri, localName, qName);
    }

    public void ignorableWhitespace(char[] ch, int start, int length)
         throws SAXException
    {
        //System.out.format("%s+ ignorableWhitespace\n", indent());
        characters(ch, start, length);
    }

    public void characters(char[] ch, int start, int length)
        throws SAXException
    {
        //System.out.format("%s+ characters\n", indent());
    }

    public InputSource resolveEntity(String publicId, String systemId)
        throws SAXException, IOException
    {
        return new InputSource(new StringReader(""));
    }
}
