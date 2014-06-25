package org.vorpus.cctext;

import java.io.InputStream;
import java.io.StringReader;
import java.io.IOException;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Collections;

import org.xml.sax.XMLReader;
import org.xml.sax.SAXException;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;

import javax.xml.parsers.SAXParserFactory;

import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.common.Heuristics;
import nu.validator.htmlparser.sax.HtmlParser;

import com.ibm.icu.text.Normalizer2;

public class HTMLToText
{
    public static Result parse(InputStream stream,
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
            // comments containing double-hyphens, and edge-cases involving
            // which characters count as white-space, stuff like that.
            HtmlParser htmlParser = new HtmlParser(XmlViolationPolicy.ALLOW);
            htmlParser.setHeuristics(Heuristics.ALL);
            parser = htmlParser;
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
        // parser.setErrorHandler(null);

        InputSource source = new InputSource(stream);
        source.setEncoding(declared_encoding);
        parser.parse(source);

        assert extractor.blockInProgress.length() == 0;

        Result result = new Result();
        result.content = extractor.documentInProgress.toString();
        assert result.content.charAt(result.content.length() - 1) == '\n';
        result.languageHints = extractor.languageHints;

        return result;
    }

    public static class Result
    {
        public String content;
        public Set<String> languageHints;
    }

    public static class TextExtractor extends DefaultHandler
    {
        static public String HTML_NAMESPACE = "http://www.w3.org/1999/xhtml";

        // Based on:
        // http://www.whatwg.org/specs/web-apps/current-work/#rendering

        private static final String[] invisibleTagsArr = new String[] {
            // Entities with display: none
            "area", "base", "basefont", "datalist", "head", "link",
            "meta", "noembed", "noframes", "param", "rp", "script", "source",
            "style", "template", "track", "title",
            // Entities that may or may not have display: none but will never have
            // useful text
            "embed", "noscript", "picture",
            // Form-related entities
            "form", "fieldset", "legend", "label", "input", "button", "select",
            "datalist", "optgroup", "option", "textarea", "keygen", "output",
            "progress", "meter", "details", "summary", "menuitem", "menu",
            "dialog",
            // Entities that represent embedded content ("replaceable entities")
            "img", "iframe", "object", "param", "video", "audio", "source",
            "track", "canvas", "map", "svg", "math", "applet", "bgsound",
            // Frames
            "frame", "frameset",
            // Struck-through text -- leaving this in while removing the
            // strikethrough will almost certainly always be misleading :-)
            "del", "strike", "s",
            // Entities that have specific uses for non-prose text:
            "figure", "footer", "pre", "address", "nav", "listing", "plaintext",
            "xmp",
            // I'm not prepared to parse ruby
            "ruby", "rt", "rp",
        };
        private static final Set<String> invisibleTags =
            new HashSet<String>(Arrays.asList(invisibleTagsArr));

        private static final String[] blockTagsArr = new String[] {
            "html", "body", "blockquote", "center", "div", "main",
            "p", "article", "aside",
            // Table stuff
            "table", "caption", "colgroup", "col", "tbody", "thead", "tfoot",
            "tr", "td", "th",
            // List stuff
            "ol", "ul", "li", "dl", "dt", "dd",
            // Sectioning stuff
            "h1", "h2", "h3", "h4", "h5", "h6", "hgroup", "header",
            // Effectively sectioning stuff, even though not logically
            "br", "hr",
        };
        private static final Set<String> blockTags =
            new HashSet<String>(Arrays.asList(blockTagsArr));

        // These get no special handling, but having a list is useful to let us
        // scan for tags we might have missed that should go into the above
        // sets
        private static final String[] knownRegularTagsArr = new String[] {
            "a", "abbr", "acronym", "b", "bdi", "bdo", "cite", "data", "datalist",
            "dfn", "em", "i", "ins", "label", "mark", "q", "small", "span",
            "strong", "sub", "sup", "u", "wbr",
            // Possibly non-text stuff, but still often used in-line in prose
            "code", "tt", "kbd", "var", "samp",
            // Obsolete
            "big", "blink",
        };
        private static final Set<String> knownRegularTags =
            new HashSet<String>(Arrays.asList(knownRegularTagsArr));

        private static void assertNoIntersection(Set<String> a, Set<String> b)
        {
            for (String s : a) {
                assert !b.contains(s);
            }
        }

        static
        {
            assertNoIntersection(invisibleTags, blockTags);
            assertNoIntersection(invisibleTags, knownRegularTags);
            assertNoIntersection(blockTags, knownRegularTags);
        }

        public StringBuilder blockInProgress = new StringBuilder();
        public StringBuilder documentInProgress = new StringBuilder();
        //public StringBuilder linkInProgress = new StringBuilder();
        // these integers count embedding depth for the relevant feature
        //public int inLink = 0;
        public int inInvisible = 1;

        public Set<String> languageHints = new HashSet();

        public boolean isInvisible(String uri, String localName)
        {
            return (!uri.equals(HTML_NAMESPACE)
                    || invisibleTags.contains(localName));
        }

        static Normalizer2 NFC = Normalizer2.getNFCInstance();

        public static String normalizeText(CharSequence content)
        {
            // Some interesting characters:
            //
            // U+00AD SOFT HYPHEN
            // U+200B ZERO WIDTH SPACE often shows up in spam, sometimes at
            //   beginning of block (why?), and is a legitimate line break
            //   opportunity indicator sometimes (e.g. for breaking
            //   identifiers in formatted source code)
            // U+200C ZERO WIDTH NON JOINER seems to only be used in arabic
            // U+200D ZERO WIDTH JOINER shows up in arabic and in what looks
            //   like a scanned english document?
            // U+FEFF BOM often appears at boundaries of paragraphs in
            //   reasonable looking text, probably by mistake.
            // U+2060 WORD JOINER (the replacement for use of U+FEFF as
            //   ZWNBSP) doesn't show up at all in my test file.
            // ... there are others ("default ignorable")
            //
            // Christian also strips U+FDD3 specifically, no idea why. (This is
            // just a random element in the non-character block.)
            // And also U+FFFF which is a non-character that gets used as a
            // sentinel value in some old and buggy APIs.
            //
            // I thnk maybe in english we should strip "default ignorable"
            //   characters from the edges, and if any are found in the middle
            //   of the string then discard that block...

            return
                // Convert to Normalization Form C
                NFC.normalize(content)
                // Remove non-characters
                .replaceAll("\\p{IsNoncharacter_Code_Point}", "")
                // Normalize (visible) whitespace
                .replaceAll("\\p{IsWhite_Space}+", " ")
                .trim();
        }

        public void maybeFlush(String localName)
        {
            if (inInvisible > 0) {
                return;
            }
            if (blockTags.contains(localName)) {
                String block = normalizeText(blockInProgress);
                blockInProgress.setLength(0);

                //int block_codepoints = codePointCount(block);
                // int link_codepoints = codePointCount(normalize(linkInProgress));
                // linkInProgress.setLength(0);

                if (!block.isEmpty()) {
                    documentInProgress.append(block);
                    documentInProgress.append("\n");
                    // int linkiness = (int) Math.round((double) link_codepoints
                    //                                  / (double) block_codepoints
                    //                                  * 100);
                    // if (wordCount(block) >= 5) {
                    //     System.out.println(block);
                    // }
                    //System.out.println(block);
                    //System.out.format("%d: %s\n", linkiness, block);
                }
            }
        }

        public void addLangHints(String hints_string)
        {
            if (hints_string == null || hints_string.isEmpty()) {
                return;
            }
            Collections.addAll(languageHints,
                               hints_string.replace("\\s", "").split(","));
        }

        public void checkForLangHints(String uri, String localName, String qName,
                                      Attributes atts)
        {
            if (!uri.equals(HTML_NAMESPACE)) {
                return;
            }
            // CLD2 looks for:
            //   <meta> tags:
            //      http-equiv="content-language"
            //      name="language"
            //      name="dc.language"
            //   most tags:
            //      lang="..."      (but not on <meta>!)
            //      xml:lang="..."
            // "most tags" means, everything except:
            //   comments
            //   font    (lang=postscript)
            //   script  (lang=javascript)
            //   link    hreflang=, xml:lang=
            //   img
            //   a       hreflang= maybe?
            // and then these are just deduped, lowercased and
            //   comma-separated. Then this string gets passed to
            //   SetCLDLangTagsHint to actually update the internal priors.
            //   I don't *think* order of this string matters.
            // When passing in hints externally:
            //   http content-language hint -> SetCLDContentLangHint
            //      CopyOneQuotedString
            //      -> SetCLDLangTagsHint
            // CopyOneQuotedString:
            //   does lowercasing, maps underscore to minus,
            //   *doesn't* want quote characters to be in the input string
            // so it looks like if we just take any hints we extract from the
            // HTML, comma separate them, and pass them in *as if* they were
            // the http content-language hint, then CLD2 will treat them the
            // same way as it would if it had extracted them from the HTML
            // itself.
            //
            // formally, content-language parsing aborts if a comma is found:
            //   http://www.whatwg.org/specs/web-apps/current-work/multipage/semantics.html#attr-meta-http-equiv-content-language
            //
            // in practice, <html lang=""> is much more common than
            // http-equiv="content-language" or meta lang="", and there are
            // very few pages that use lang= without using it on <html>.
            if (localName.equals("meta")
                && ("content-language".equals(atts.getValue("http-equiv"))
                    || "language".equals(atts.getValue("name"))
                    || "dc.language".equals(atts.getValue("name")))) {
                addLangHints(atts.getValue("content"));
            } else if (localName.equals("html")) {
                addLangHints(atts.getValue("lang"));
            }
        }

        public void startElement(String uri, String localName, String qName,
                                 Attributes atts) throws SAXException
        {
            checkForLangHints(uri, localName, qName, atts);

            if (isInvisible(uri, localName)) {
                inInvisible += 1;
                return;
            }

            // from here on we can assume that this element is in the HTML
            // namespace.

            if (localName.equals("body")) {
                inInvisible -= 1;
            }

            // if (localName.equals("a")) {
            //     inLink += 1;
            // }

            maybeFlush(localName);
        }

        public void endElement(String uri, String localName, String qName)
            throws SAXException
        {
            if (isInvisible(uri, localName)) {
                inInvisible -= 1;
                return;
            }

            if (localName.equals("body")) {
                inInvisible += 1;
            }

            // if (localName.equals("a")) {
            //     inLink -= 1;
            // }

            maybeFlush(localName);
        }

        public void ignorableWhitespace(char[] ch, int start, int length)
            throws SAXException
        {
            characters(ch, start, length);
        }

        public void characters(char[] ch, int start, int length)
            throws SAXException
        {
            if (inInvisible > 0) {
                return;
            }

            blockInProgress.append(ch, start, length);
            // if (inLink > 0) {
            //     linkInProgress.append(ch, start, length);
            // }
        }

        public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException, IOException
        {
            return new InputSource(new StringReader(""));
        }
    }
}
