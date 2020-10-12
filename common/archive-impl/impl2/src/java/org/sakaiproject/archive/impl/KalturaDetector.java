package org.sakaiproject.archive.impl;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.XMLFilterImpl;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.*;
import java.util.*;
import java.util.stream.*;
import java.util.regex.*;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.concurrent.atomic.AtomicBoolean;

public class KalturaDetector {

    private static Set<String> ENCODED_ATTRIBUTES = new HashSet<>();

    static {
        ENCODED_ATTRIBUTES.add("body-html");
        ENCODED_ATTRIBUTES.add("syllabus_body-html");
        ENCODED_ATTRIBUTES.add("value");
    }

    public List<String> pathsWithKalturaTags(String siteId, String storagePath) throws Exception {
        String basedir = String.format("%s/%s-archive", storagePath, siteId);

        List<Path> pathsToRewrite = java.nio.file.Files.walk(Paths.get(basedir))
            .filter(p -> p.toString().endsWith(".xml"))
            .collect(Collectors.toList());

        List<String> foundPaths = new ArrayList<>();

        for (Path p : pathsToRewrite) {
            if (fileHasKalturaTags(p.toString())) {
                foundPaths.add(p.toString());
            }
        }

        return foundPaths;
    }

    public boolean fileHasKalturaTags(String path) {
        final AtomicBoolean hasKaltura = new AtomicBoolean(false);

        try {
            XMLReader xr = new XMLFilterImpl(XMLReaderFactory.createXMLReader()) {
                    private StringBuilder contentToProcess = new StringBuilder();

                    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
                        if (!hasKaltura.get()) {
                            for (int i = 0; i < atts.getLength(); i++) {
                                String key = atts.getLocalName(i);
                                String value = atts.getValue(i);

                                if (ENCODED_ATTRIBUTES.contains(key.toLowerCase(Locale.ROOT)) && value != null) {
                                    hasKaltura.set(findKalturaMarkup(value, true));
                                }
                            }
                        }

                        super.startElement(uri, localName, qName, atts);
                    }

                    public void endElement(String uri, String localName, String qName) throws SAXException {
                        if (contentToProcess.length() > 0) {
                            boolean found = findKalturaMarkup(contentToProcess.toString(), false);

                            if (found) {
                                hasKaltura.set(found);
                            }

                            contentToProcess = new StringBuilder();
                        }

                        super.endElement(uri, localName, qName);
                    }

                    public void characters(char[] ch, int start, int length) throws SAXException {
                        if (!hasKaltura.get()) {
                            contentToProcess.append(new String(ch, start, length));
                        }
                    }
                };

            // Pass through invalid 'sakai:' prefixes
            xr.setFeature("http://xml.org/sax/features/namespaces", false);

            Source src = new SAXSource(xr, new InputSource(path));
            Result res = new StreamResult(new FileOutputStream("/dev/null"));
            TransformerFactory.newInstance().newTransformer().transform(src, res);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return hasKaltura.get();
    }

    // "(<(span|div)\\s+class\\s*=\\s*['\"]kaltura-lti-media['\"]\\s*>\\s*(<img\\s+[^\\/>]+\\/>)\\s*<\\/(span|div)>)"
    private Pattern kalturaPattern = Pattern.compile("(<(span|div)\\s+class\\s*=\\s*['\"]kaltura-lti-media['\"]\\s*>\\s*(<img\\s+[^>]+>)\\s*</(span|div)>)",
                                                     Pattern.MULTILINE | Pattern.DOTALL);

    // body-html="<p><span class="kaltura-lti-media"><img kaltura-lti-url="https://nyusakaidev.kaf.kaltura.com/browseandembed/index/media/entryid/1_g6j5n7as/showDescription/false/showTitle/false/showTags/false/showDuration/false/showOwner/false/showUploadDate/false/playerSize/608x402/playerSkin/44333542/" src="https://cfvod.kaltura.com/p/1674411/sp/167441100/thumbnail/entry_id/1_g6j5n7as/version/100011" style="height: 402.0px;width: 608.0px;" title="IFrame" /></span></p>">

    private boolean findKalturaMarkup(String content, boolean isBase64) {
        try {
            String rewritten = content;

            if (isBase64) {
                rewritten = new String(Base64.getDecoder().decode(content), "UTF-8");
            }

            Matcher m = kalturaPattern.matcher(rewritten);

            return m.find();
        } catch (Exception e) {
            System.err.println("DELICIOUS FAILCAKE: " + e);

            return false;
        }
    }
}
