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

package helma.util;

import org.apache.commons.logging.Log;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * An extended Logger that writes to a file and rotates files each midnight.
 *
 * @author Stefan Pollach
 * @author Daniel Ruthardt
 * @author Hannes Wallnoefer
 */
public class FileLogger extends Logger implements Log {

    // fields used for logging to files
    private String name;
    private File logdir;
    private File logfile;

    // number format for log file rotation
    DecimalFormat nformat = new DecimalFormat("000"); //$NON-NLS-1$
    DateFormat aformat = new SimpleDateFormat("yyyy-MM-dd"); //$NON-NLS-1$

    /**
     * Create a file logger. The actual file names do have numbers appended and are
     * rotated every x bytes.
     * @param directory the directory
     * @param name the log file base name
     */
    protected FileLogger(String directory, String name) {
        this.name = name;
        logdir = new File(directory);
        // make logdir have an absolute path in case it doesn't already
        if (!logdir.isAbsolute())
            logdir = logdir.getAbsoluteFile();
        logfile = new File(logdir, name + ".log"); //$NON-NLS-1$

        if (!logdir.exists()) {
            logdir.mkdirs();
        }
    }

    /**
     * Open the file and get a writer to it. If the file already exists, this will
     * return a writer that appends to an existing file if it is from today, or
     * otherwise rotate the old log file and start a new one.
     */
    private synchronized void openFile() {
        try {
            if (logfile.exists() && (logfile.lastModified() < Logging.lastMidnight())) {
                // rotate if a log file exists and is NOT from today
                File archive = rotateLogFile();
                // gzip rotated log file in a separate thread
                if (archive != null) {
                    new GZipper(archive).start();
                }
            }
            // create a new log file, appending to an existing file
            writer = new PrintWriter(new FileWriter(logfile.getAbsolutePath(), true),
                                     false);
        } catch (IOException iox) {
            System.err.println(Messages.getString("FileLogger.0") + name + ": " + iox);  //$NON-NLS-1$//$NON-NLS-2$
        }
    }

    /**
     * Actually closes the file writer of a log.
     */
    synchronized void closeFile() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignore) {
                // ignore
            } finally {
                writer = null;
            }
        }
    }

    /**
     * This is called by the runner thread to to make sure we have an open writer.
     */
    @Override
    protected synchronized void ensureOpen() {
        // open a new writer if writer is null or the log file has been deleted
        if (writer == null || !logfile.exists()) {
            openFile();
        }
    }

    /**
     *  Rotate log files, closing the file writer and renaming the old
     *  log file. Returns the renamed log file for zipping, or null if
     *  the log file couldn't be rotated.
     *
     *  @return the old renamed log file, or null
     *  @throws IOException if an i/o error occurred
     */
    protected synchronized File rotateLogFile() throws IOException {
        // if the logger is not file based do nothing.
        if (logfile == null) {
            return null;
        }

        closeFile();

        // only backup/rotate if the log file is not empty,
        if (logfile.exists() && (logfile.length() > 0)) {
            String today = aformat.format(new Date());
            int ct = 0;

            // first append just the date
            String archname = name + "-" + today + ".log"; //$NON-NLS-1$ //$NON-NLS-2$
            File archive = new File(logdir, archname);
            File zipped = new File(logdir, archname + ".gz"); //$NON-NLS-1$

            // increase counter until we find an unused log archive name, checking
            // both unzipped and zipped file names
            while (archive.exists() || zipped.exists()) {
                // for the next try we append a counter
                String archidx = (ct > 999) ? Integer.toString(ct) : nformat.format(++ct);

                archname = name + "-" + today + "-" + archidx + ".log"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                archive = new File(logdir, archname);
                zipped = new File(logdir, archname + ".gz"); //$NON-NLS-1$
            }

            if (logfile.renameTo(archive)) {
                return archive;
            } else {
                System.err.println(Messages.getString("FileLogger.1") + canonicalName + //$NON-NLS-1$
                        Messages.getString("FileLogger.2")); //$NON-NLS-1$
            }
        }

        // no log file rotated
        return null;
    }

    /**
     * Return a string representation of this Logger
     */
    @Override
    public String toString() {
        return "FileLogger[" + name + "]";  //$NON-NLS-1$//$NON-NLS-2$
    }

    /**
     *  Return an object  which identifies this logger.
     *  @return the logger's name
     */
    public String getName() {
        return name;
    }

    /**
     * a Thread class that zips up a file, filename will stay the same.
     */
    static class GZipper extends Thread {
        List files;
        final static int BUFFER_SIZE = 8192;

        public GZipper(List files) {
            this.files = files;
            setPriority(MIN_PRIORITY);
        }

        public GZipper(File file) {
            files = new ArrayList(1);
            files.add(file);
            setPriority(MIN_PRIORITY);
        }

        @Override
        public void run() {
            Iterator it = files.iterator();
            File file = null;

            while (it.hasNext()) {
                try {
                    file = (File) it.next();
                    File zipped = new File(file.getAbsolutePath() + ".gz"); //$NON-NLS-1$
                    GZIPOutputStream zip = new GZIPOutputStream(new FileOutputStream(zipped));
                    BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
                    byte[] b = new byte[BUFFER_SIZE];
                    int len;

                    while ((len = in.read(b, 0, BUFFER_SIZE)) != -1) {
                        zip.write(b, 0, len);
                    }

                    zip.close();
                    in.close();
                    file.delete();
                } catch (Exception e) {
                    System.err.println(Messages.getString("FileLogger.3") + file); //$NON-NLS-1$
                    System.err.println(e.toString());
                }
            }
        }
    }

}
