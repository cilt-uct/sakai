package org.sakaiproject.archive.impl;

import org.sakaiproject.util.Xml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;

public class QuizTitleHappyMaker {
    public void makeHappy(String lessonsExportPath) {
        DocumentBuilder builder = null;
        try {
            builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new File(lessonsExportPath));

            XPath xPath = XPathFactory.newInstance().newXPath();
            NodeList pages = ((NodeList)xPath.evaluate("/archive/org.sakaiproject.lessonbuildertool.service.LessonBuilderEntityProducer/lessonbuilder/page",
                    doc,
                    XPathConstants.NODESET));

            for (int i = 0; i < pages.getLength(); i++) {
                if (!(pages.item(i) instanceof Element)) {
                    continue;
                }

                Element page = (Element) pages.item(i);
                String pageName = page.getAttribute("title");
                if (pageName.length() > 30) {
                    pageName = String.format("%s...", pageName.substring(0, 30));
                }

                NodeList items = page.getChildNodes();

                int quizCount = 0;

                for (int j = 0; j < items.getLength(); j++) {
                    if (!(items.item(j) instanceof Element)) {
                        continue;
                    }

                    Element item = (Element) items.item(j);

                    if (!"item".equals(item.getTagName())) {
                        continue;
                    }

                    if (!"11".equals(item.getAttribute("type"))) {
                        continue;
                    }

                    quizCount++;
                    String newQuizName = String.format("%s: Question %d", pageName, quizCount);
                    item.setAttribute("name", newQuizName);
                }
            }

            Xml.writeDocument(doc, lessonsExportPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
