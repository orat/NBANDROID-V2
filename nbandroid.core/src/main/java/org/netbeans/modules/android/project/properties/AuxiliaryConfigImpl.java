/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.android.project.properties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.spi.project.AuxiliaryConfiguration;
import org.openide.filesystems.FileObject;
import org.openide.util.Mutex;
import org.openide.xml.XMLUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
/**
 *
 * @author arsi
 */
public class AuxiliaryConfigImpl implements AuxiliaryConfiguration {

    private static final Logger LOG = Logger.getLogger(AuxiliaryConfigImpl.class.getName());
    static final String AUX_CONFIG_ATTR_BASE = AuxiliaryConfiguration.class.getName();
    static final String AUX_CONFIG_FILENAME = ".nbandroid.xml"; // NOI18N

    private final Project project;

    public AuxiliaryConfigImpl(Project proj) {
        this.project = proj;
    }

    @Override
    public Element getConfigurationFragment(final String elementName, final String namespace, final boolean shared) {
        assert project != null;
        return ProjectManager.mutex(false, project).readAccess(new Mutex.Action<Element>() {
            @Override
            public Element run() {
                FileObject dir = project.getProjectDirectory();
                if (shared) {
                    FileObject config = dir.getFileObject(AUX_CONFIG_FILENAME);
                    if (config != null) {
                        try {
                            InputStream is = config.getInputStream();
                            try {
                                InputSource input = new InputSource(is);
                                input.setSystemId(config.toURL().toString());
                                Element root = XMLUtil.parse(input, false, true, /*XXX*/ null, null).getDocumentElement();
                                return XMLUtil.findElement(root, elementName, namespace);
                            } finally {
                                is.close();
                            }
                        } catch (IOException | SAXException | IllegalArgumentException x) {
                            LOG.log(Level.INFO, "Cannot parse" + config, x);
                        }
                    }
                } else {
                    String attrName = AUX_CONFIG_ATTR_BASE + "." + namespace + "#" + elementName;
                    Object attr = dir.getAttribute(attrName);
                    if (attr instanceof String) {
                        try {
                            Element fragment = XMLUtil.parse(new InputSource(new StringReader((String) attr)), false, true,
                                    /*XXX #136595: need utility method*/ null, null).getDocumentElement();
                            if (elementName.equals(fragment.getLocalName()) && namespace.equals(fragment.getNamespaceURI())) {
                                return fragment;
                            } else {
                                LOG.log(Level.INFO, "Value {0} of {1} on {2} has the wrong local name or namespace", new Object[]{attr, attrName, dir});
                            }
                        } catch (SAXException x) {
                            LOG.log(Level.INFO, "Cannot parse value {0} of {1} on {2}: {3}", new Object[]{attr, attrName, dir, x.getMessage()});
                        } catch (IOException x) {
                            assert false : x;
                        }
                    }
                }
                return null;
            }
        });
    }

    @Override
    public void putConfigurationFragment(final Element fragment, final boolean shared) throws IllegalArgumentException {
        ProjectManager.mutex(false, project).writeAccess(new Mutex.Action<Void>() {
            @Override
            public Void run() {
                String elementName = fragment.getLocalName();
                String namespace = fragment.getNamespaceURI();
                if (namespace == null) {
                    throw new IllegalArgumentException();
                }
                FileObject dir = project.getProjectDirectory();
                try {
                    if (shared) {
                        Document doc;
                        FileObject config = dir.getFileObject(AUX_CONFIG_FILENAME);
                        if (config != null) {
                            InputStream is = config.getInputStream();
                            try {
                                InputSource input = new InputSource(is);
                                input.setSystemId(config.toURL().toString());
                                doc = XMLUtil.parse(input, false, true, /*XXX*/ null, null);
                            } finally {
                                is.close();
                            }
                        } else {
                            config = dir.createData(AUX_CONFIG_FILENAME);
                            doc = XMLUtil.createDocument("auxiliary-configuration", "http://www.netbeans.org/ns/auxiliary-configuration/1", null, null);
                        }
                        Element root = doc.getDocumentElement();
                        Element oldFragment = XMLUtil.findElement(root, elementName, namespace);
                        if (oldFragment != null) {
                            root.removeChild(oldFragment);
                        }
                        Node ref = null;
                        NodeList list = root.getChildNodes();
                        for (int i = 0; i < list.getLength(); i++) {
                            Node node = list.item(i);
                            if (node.getNodeType() != Node.ELEMENT_NODE) {
                                continue;
                            }
                            int comparison = node.getNodeName().compareTo(elementName);
                            if (comparison == 0) {
                                comparison = node.getNamespaceURI().compareTo(namespace);
                            }
                            if (comparison > 0) {
                                ref = node;
                                break;
                            }
                        }
                        root.insertBefore(root.getOwnerDocument().importNode(fragment, true), ref);
                        try (OutputStream os = config.getOutputStream()) {
                            XMLUtil.write(doc, os, "UTF-8");
                        }
                    } else {
                        String attrName = AUX_CONFIG_ATTR_BASE + "." + namespace + "#" + elementName;
                        dir.setAttribute(attrName, elementToString(fragment));
                    }
                } catch (Exception x) {
                    LOG.log(Level.WARNING, "Cannot save configuration to " + dir, x);
                }
                return null;
            }
        });
    }

    static String elementToString(Element e) throws ParserConfigurationException {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        DOMImplementationLS ls = (DOMImplementationLS) doc.getImplementation().getFeature("LS", "3.0"); // NOI18N
        assert ls != null : "No DOM 3 LS supported in " + doc.getClass().getName();
        // JAXP bug #6710755: cannot directly serialize fragment in JDK 5, must add to a document.
        doc.appendChild(doc.importNode(e, true));
        LSSerializer serializer = ls.createLSSerializer();
        serializer.getDomConfig().setParameter("xml-declaration", false);
        return serializer.writeToString(doc);
    }

    private boolean removeFallbackImpl(final String elementName, final String namespace, final boolean shared) {
        FileObject dir = project.getProjectDirectory();
        try {
            if (shared) {
                FileObject config = dir.getFileObject(AUX_CONFIG_FILENAME);
                if (config != null) {
                    try {
                        Document doc;
                        InputStream is = config.getInputStream();
                        try {
                            InputSource input = new InputSource(is);
                            input.setSystemId(config.toURL().toString());
                            doc = XMLUtil.parse(input, false, true, /*XXX*/ null, null);
                        } finally {
                            is.close();
                        }
                        Element root = doc.getDocumentElement();
                        Element toRemove = XMLUtil.findElement(root, elementName, namespace);
                        if (toRemove != null) {
                            root.removeChild(toRemove);
                            if (root.getElementsByTagName("*").getLength() > 0) {
                                try (OutputStream os = config.getOutputStream()) {
                                    XMLUtil.write(doc, os, "UTF-8");
                                }
                            } else {
                                config.delete();
                            }
                            return true;
                        }
                    } catch (SAXException x) {
                        LOG.log(Level.INFO, "Cannot parse" + config, x);
                    }
                }
            } else {
                String attrName = AUX_CONFIG_ATTR_BASE + "." + namespace + "#" + elementName;
                if (dir.getAttribute(attrName) != null) {
                    dir.setAttribute(attrName, null);
                    return true;
                }
            }
        } catch (IOException x) {
            LOG.log(Level.WARNING, "Cannot remove configuration from {0}", dir);
        }
        return false;
    }

    @Override
    public boolean removeConfigurationFragment(final String elementName, final String namespace, final boolean shared) throws IllegalArgumentException {
        return ProjectManager.mutex(false, project).writeAccess(new Mutex.Action<Boolean>() {
            @Override
            public Boolean run() {
                boolean result = false;
                result |= removeFallbackImpl(elementName, namespace, shared);
                return result;
            }
        });
    }

}
