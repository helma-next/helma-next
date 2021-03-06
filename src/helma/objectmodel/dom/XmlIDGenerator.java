/*
 * Helma License Notice
 *
 * The contents of this file are subject to the Helma License
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://adele.helma.org/download/helma/license.txt
 *
 * Copyright 1998-2003 Helma Software. All Rights Reserved.
 *
 * $RCSfile$
 * $Author$
 * $Revision$
 * $Date$
 */

package helma.objectmodel.dom;

import helma.objectmodel.ObjectNotFoundException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.*;
import java.util.Date;

/**
 * 
 */
public class XmlIDGenerator {

    private long counter;
    transient volatile boolean dirty;

    /**
     * Builds a new IDGeneratorInterface starting with 0.
     */
    public XmlIDGenerator() {
        this.counter = 0L;
        this.dirty = false;
    }

    /**
     * Builds a new IDGeneratorInterface starting with value.
     */
    public XmlIDGenerator(long value) {
        this.counter = value;
        this.dirty = false;
    }

    /**
     * Delivers a unique id and increases counter by 1.
     */
    public synchronized String newID() {
        this.counter += 1L;
        this.dirty = true;

        return Long.toString(this.counter);
    }

    /**
     * Set the counter to a new value
     */
    protected synchronized void setValue(long value) {
        this.counter = value;
        this.dirty = true;
    }

    /**
     * Get the current counter  value
     */
    public long getValue() {
        return this.counter;
    }

    /**
     * Returns a string representation of this IDGeneratorInterface
     */
    @Override
    public String toString() {
        return "IDGeneratorInterface[counter=" + this.counter + ",dirty=" + this.dirty + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Read an IDGeneratorInterface from file
     *
     * @param file
     * @return
     * @throws ObjectNotFoundException
     */
    public static XmlIDGenerator getIDGenerator(File file)
            throws ObjectNotFoundException {
        if (!file.exists()) {
            throw new ObjectNotFoundException(Messages.getString("XmlIDGenerator.0")); //$NON-NLS-1$
        }

        try {
            Document document = XmlUtil.parse(new FileInputStream(file));
            org.w3c.dom.Element tmp = (Element) document.getDocumentElement()
                    .getElementsByTagName("counter") //$NON-NLS-1$
                    .item(0);

            return new XmlIDGenerator(Long.parseLong(XmlUtil.getTextContent(tmp)));
        } catch (Exception e) {
            throw new ObjectNotFoundException(e.toString());
        }
    }

    /**
     * Save an id generator to a file.
     *
     * @param idgen
     * @param file
     * @throws IOException
     */
    public static void saveIDGenerator(XmlIDGenerator idgen, File file)
            throws IOException {
        OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(file));

        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
        out.write("<!-- printed by helma object publisher     -->\n"); //$NON-NLS-1$
        out.write("<!-- created " + (new Date()).toString() + " -->\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.write("<xmlroot>\n"); //$NON-NLS-1$
        out.write("  <counter>" + idgen.getValue() + "</counter>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        out.write("</xmlroot>\n"); //$NON-NLS-1$
        out.close();
    }
}
