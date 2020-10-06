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

public class KalturaRewriter {

    private static Set<String> ENCODED_ATTRIBUTES = new HashSet<>();

    static {
        ENCODED_ATTRIBUTES.add("body-html");
        ENCODED_ATTRIBUTES.add("syllabus_body-html");
        ENCODED_ATTRIBUTES.add("value");
    }

    public void rewriteKalturaTags(String siteId, String storagePath) throws Exception {
        String basedir = String.format("%s/%s-archive", storagePath, siteId);

        List<Path> pathsToRewrite = java.nio.file.Files.walk(Paths.get(basedir))
            .filter(p -> p.toString().endsWith(".xml"))
            .collect(Collectors.toList());

        for (Path p : pathsToRewrite) {
            rewriteXMLFile(p.toString());
        }
    }

    public void rewriteXMLFile(String path) {
        try {
            XMLReader xr = new XMLFilterImpl(XMLReaderFactory.createXMLReader()) {
                    private StringBuilder contentToProcess = new StringBuilder();

                    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
                        AttributesImpl modified = new AttributesImpl(atts);

                        for (int i = 0; i < modified.getLength(); i++) {
                            String key = modified.getLocalName(i);
                            String value = modified.getValue(i);

                            if (ENCODED_ATTRIBUTES.contains(key.toLowerCase(Locale.ROOT)) && value != null) {
                                value = rewriteKaltura(value, true);
                            }

                            modified.setValue(i, value);
                        }

                        super.startElement(uri, localName, qName, modified);
                    }

                    public void endElement(String uri, String localName, String qName) throws SAXException {
                        if( contentToProcess.length() > 0) {
                            String content = rewriteKaltura(contentToProcess.toString(), false);

                            char[] chars = content.toCharArray();
                            super.characters(chars, 0, chars.length);
                            contentToProcess = new StringBuilder();
                        }

                        super.endElement(uri, localName, qName);
                    }

                    public void characters(char[] ch, int start, int length) throws SAXException {
                        contentToProcess.append(new String(ch, start, length));
                    }
                };

            // Pass through invalid 'sakai:' prefixes
            xr.setFeature("http://xml.org/sax/features/namespaces", false);

            Source src = new SAXSource(xr, new InputSource(path));
            Result res = new StreamResult(new FileOutputStream(path + ".rewritten_xml"));
            TransformerFactory.newInstance().newTransformer().transform(src, res);

            new File(path + ".rewritten_xml").renameTo(new File(path));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    // "(<(span|div)\\s+class\\s*=\\s*['\"]kaltura-lti-media['\"]\\s*>\\s*(<img\\s+[^\\/>]+\\/>)\\s*<\\/(span|div)>)"
    private Pattern kalturaPattern = Pattern.compile("(<(span|div)\\s+class\\s*=\\s*['\"]kaltura-lti-media['\"]\\s*>\\s*(<img\\s+[^>]+>)\\s*</(span|div)>)",
                                                     Pattern.MULTILINE | Pattern.DOTALL);

    private String pullTag(String src, String tag, String defaultValue) {
        Pattern p = Pattern.compile(String.format("%s\\s*=\\s*[\"'](.*?)[\"']", tag));
        Matcher m = p.matcher(src);

        if (m.find()) {
            return m.group(1);
        } else {
            return defaultValue;
        }
    }

    private static String replacementTemplate = ("<div id='kaltura-video-container' style='position: relative; max-width: %spx'>" +
                                                 "  <div id='kaltura-video' style='position: relative; height=%spx'>" +
                                                 "    <iframe src='%s' allowfullscreen webkitallowfullscreen mozAllowFullScreen allow='autoplay *; fullscreen *; encrypted-media *' style='border: none; height: 100%%; width: 100%%'></iframe>" +
                                                 "  </div>" +
                                                 "</div>");


    // body-html="<p><span class="kaltura-lti-media"><img kaltura-lti-url="https://nyusakaidev.kaf.kaltura.com/browseandembed/index/media/entryid/1_g6j5n7as/showDescription/false/showTitle/false/showTags/false/showDuration/false/showOwner/false/showUploadDate/false/playerSize/608x402/playerSkin/44333542/" src="https://cfvod.kaltura.com/p/1674411/sp/167441100/thumbnail/entry_id/1_g6j5n7as/version/100011" style="height: 402.0px;width: 608.0px;" title="IFrame" /></span></p>">

    private String rewriteKaltura(String content, boolean isBase64) {
        try {
            String rewritten = content;

            if (isBase64) {
                rewritten = new String(Base64.getDecoder().decode(content), "UTF-8");
            }

            for (;;) {
                Matcher m = kalturaPattern.matcher(rewritten);
                if (m.find()) {
                    String imageTag = m.group(3);

                    String url = pullTag(imageTag, "kaltura-lti-url", "");
                    String defaultHeight = pullTag(imageTag, "height", "285");
                    String defaultWidth = pullTag(imageTag, "width", "400");

                    String width = null;
                    String height = null;

                    Matcher sizeMatcher = Pattern.compile("/playerSize/([0-9]+?)x([0-9]+?)/").matcher(url);
                    if (sizeMatcher.find()) {
                        width = sizeMatcher.group(1);
                        height = sizeMatcher.group(2);
                    }

                    if (width == null) {
                        width = defaultWidth;
                    }

                    if (height == null) {
                        height = defaultHeight;
                    }

                    rewritten = m.replaceFirst(String.format(replacementTemplate,
                                                             width, height, url));
                } else {
                    break;
                }
            }

            if (isBase64) {
                rewritten = Base64.getEncoder().encodeToString(rewritten.getBytes("UTF-8"));
            }

            return rewritten;
        } catch (Exception e) {
            System.err.println("FAILCAKE: " + e);

            return content;
        }
    }
}
