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
 * Contributions:
 *   Daniel Ruthardt
 *   Copyright 2010 dowee Limited. All rights reserved.
 * 
 * $RCSfile$
 * $Author$
 * $Revision$
 * $Date$
 */

package helma.framework;

import helma.framework.core.Skin;
import helma.framework.core.Application;
import helma.util.*;
import helma.scripting.ScriptingException;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.security.*;
import java.util.*;

import org.apache.commons.codec.binary.Base64;
import org.apache.xmlrpc.XmlRpcResponseProcessor;

/**
 * A Transmitter for a response to the servlet client. Objects of this
 * class are directly exposed to JavaScript as global property res.
 */
public final class ResponseTrans extends Writer implements Serializable {

    static final long serialVersionUID = -8627370766119740844L;
    static final int INITIAL_BUFFER_SIZE = 2048;

    static final String newLine = System.getProperty("line.separator"); //$NON-NLS-1$

    //  MIME content type of the response.
    private String contentType = "text/html"; //$NON-NLS-1$

    // Charset (encoding) to use for the response.
    private String charset;

    // Used to allow or disable client side caching
    private boolean cacheable = true;

    // HTTP response code, defaults to 200 (OK).
    private int status = 200;

    // HTTP authentication realm
    private String realm;

    // the actual response
    private byte[] response = null;

    // contains the redirect URL
    private String redir = null;

    // the forward (internal redirect) URL
    private String forward = null;

    // the last-modified date, if it should be set in the response
    private long lastModified = -1;

    // flag to signal that resource has not been modified
    private boolean notModified = false;

    // Entity Tag for this response, used for conditional GETs
    private String etag = null;

    // cookies
    Map cookies;

    // the buffer used to build the response
    private transient StringBuffer buffer = null;

    // an idle StringBuffer waiting to be reused
    private transient StringBuffer cachedBuffer = null;

    // these are used to implement the _as_string variants for Hop templates.
    private transient Stack buffers;

    // the path used to tell where to look for skins
    private transient Object[] skinpath = null;

    // hashmap for skin caching
    private transient HashMap skincache;

    // buffer for debug messages - will be automatically appended to response
    private transient StringBuffer debugBuffer;

    // field for generic message to be displayed
    private transient String message;

    // field for error
    private transient Throwable error;

    // the res.data map of form and cookie data
    private transient Map values = new SystemMap();

    // the res.handlers map of macro handlers
    private transient Map handlers = new SystemMap();

    // the res.meta map for meta response data
    private transient Map meta = new SystemMap();

    // the request trans for this response
    private transient RequestTrans reqtrans;

    // the message digest used to generate composed digests for ETag headers
    private transient MessageDigest digest;

    // the skin current or last rendered skin
    private transient volatile Skin activeSkin;

    // the application
    Application app;


    /**
     * Creates a new ResponseTrans object.
     *
     * @param req the RequestTrans for this response
     */
    public ResponseTrans(Application app, RequestTrans req) {
        this.app = app;
        this.reqtrans = req;
    }

    /**
     *  Get a value from the responses map by key.
     */
    public Object get(String name) {
        try {
            return this.values.get(name);
        } catch (Exception x) {
            return null;
        }
    }

    /**
     *  Get the data map for this response transmitter.
     */
    public Map getResponseData() {
        return this.values;
    }

    /**
     *  Get the macro handlers map for this response transmitter.
     */
    public Map getMacroHandlers() {
        return this.handlers;
    }

    /**
     *  Get the meta info map for this response transmitter.
     */
    public Map getMetaData() {
        return this.meta;
    }

    /**
     * Returns the ServletResponse instance for this ResponseTrans.
     * Returns null for internal and XML-RPC requests.
     */
    public HttpServletResponse getServletResponse() {
        return this.reqtrans.getServletResponse();
    }

    /**
     * Reset the current response buffer.
     */
    public synchronized void resetBuffer() {
        if (this.buffer != null) {
            this.buffer.setLength(0);
        }
    }

    /**
     * Reset the response object to its initial empty state.
     */
    public synchronized void reset() {
        if (this.buffer != null) {
            this.buffer.setLength(0);
        }

        this.buffers = null;
        this.response = null;
        this.cacheable = true;
        this.redir = this.forward = this.message = null;
        this.error = null;
        this.etag = this.realm = this.charset = null;
        this.contentType =  "text/html"; //$NON-NLS-1$
        this.values.clear();
        this.handlers.clear();
        this.meta.clear();
        this.lastModified = -1;
        this.notModified = false;
        this.skinpath = null;
        this.skincache = null;
        this.cookies = null;

        if (this.digest != null) {
            this.digest.reset();
        }
    }

    /**
     * This is called before a skin is rendered as string
     * (renderSkinAsString) to redirect the output to a new
     * string buffer.
     * @param buf the StringBuffer to use, or null
     * @return the new StringBuffer instance
     */
    public synchronized StringBuffer pushBuffer(StringBuffer buf) {
        if (this.buffers == null) {
            this.buffers = new Stack();
        }

        if (this.buffer != null) {
            this.buffers.push(this.buffer);
        }

        if (buf != null) {
            this.buffer = buf;
        } else if (this.cachedBuffer != null) {
            this.buffer = this.cachedBuffer;
            this.cachedBuffer = null;
        } else {
            this.buffer = new StringBuffer(64);
        }
        return this.buffer;
    }

    /**
     * Returns the content of the current string buffer and switches back to the previos one.
     */
    public synchronized String popString() {
        StringBuffer buf = popBuffer();
        String str = buf.toString();
        // store stringbuffer for later reuse
        buf.setLength(0);
        this.cachedBuffer = buf;
        return str;
    }

    public synchronized StringBuffer popBuffer() {
        if (this.buffer == null) {
            throw new RuntimeException(Messages.getString("ResponseTrans.0")); //$NON-NLS-1$
        } else if (this.buffers == null) {
            throw new RuntimeException(Messages.getString("ResponseTrans.1")); //$NON-NLS-1$
        }
        // get local reference
        StringBuffer buf = this.buffer;
        // restore the previous buffer, which may be null
        this.buffer = this.buffers.empty() ? null : (StringBuffer) this.buffers.pop();
        return buf;
    }

    /**
     *  Get the response buffer, creating it if it doesn't exist
     */
    public synchronized StringBuffer getBuffer() {
        if (this.buffer == null) {
            this.buffer = new StringBuffer(INITIAL_BUFFER_SIZE);
        }

        return this.buffer;
    }

    /**
     * Append a string to the response unchanged.
     */
    @Override
    public synchronized void write(String str) {
        if (str != null) {
            if (this.buffer == null) {
                this.buffer = new StringBuffer(Math.max(str.length() + 100, INITIAL_BUFFER_SIZE));
            }
            this.buffer.append(str);
        }
    }

    /**
     * Appends a objct to the response unchanged.
     * The object is first converted to a string.
     */
    public void write(Object what) {
        if (what != null) {
            write(what.toString());
        }
    }

    /**
     *  Appends a part from a char array to the response buffer.
     *
     * @param chars
     * @param offset
     * @param length
     */
    @Override
    public synchronized void write(char[] chars, int offset, int length) {
        if (this.buffer == null) {
            this.buffer = new StringBuffer(Math.max(length + 100, INITIAL_BUFFER_SIZE));
        }
        this.buffer.append(chars, offset, length);
    }

    /**
     *  Appends a char array to the response buffer.
     *
     * @param chars
     */
    @Override
    public void write(char chars[]) {
        write(chars, 0, chars.length);
    }


    /**
     * Appends a signle character to the response buffer.
     * @param c
     */
    @Override
    public synchronized void write(int c) {
        if (this.buffer == null) {
            this.buffer = new StringBuffer(INITIAL_BUFFER_SIZE);
        }
        this.buffer.append((char) c);
    }

    /**
     * Appends a part from a string to the response buffer.
     * @param str
     * @param offset
     * @param length
     */
    @Override
    public void write(String str, int offset, int length) {
        char cbuf[]  = new char[length];
        str.getChars(offset, (offset + length), cbuf, 0);
        write(cbuf, 0, length);
    }

    /**
     * Write object to response buffer and append a platform dependent newline sequence.
     */
    public synchronized void writeln(Object what) {
        if (what != null) {
            write(what.toString());
        } else if (this.buffer == null) {
            // if what is null, buffer may still be uninitialized
            this.buffer = new StringBuffer(INITIAL_BUFFER_SIZE);
        }
        this.buffer.append(newLine);
    }

    /**
     * Writes a platform dependent newline sequence to response buffer.
     */
    public synchronized void writeln() {
        // buffer may still be uninitialized
        if (this.buffer == null) {
            this.buffer = new StringBuffer(INITIAL_BUFFER_SIZE);
        }
        this.buffer.append(newLine);
    }

    /**
     *  Insert string somewhere in the response buffer. Caller has to make sure
     *  that buffer exists and its length is larger than offset. str may be null, in which
     *  case nothing happens.
     */
    public void debug(Object message) {
        if (this.debugBuffer == null) {
            this.debugBuffer = new StringBuffer();
        }

        String str = (message == null) ? "null" : message.toString(); //$NON-NLS-1$

        this.debugBuffer.append("<div class=\"helma-debug-line\" style=\"background: yellow; "); //$NON-NLS-1$
        this.debugBuffer.append("color: black; border-top: 1px solid black;\">"); //$NON-NLS-1$
        this.debugBuffer.append(str);
        this.debugBuffer.append("</div>"); //$NON-NLS-1$
    }

    /**
     * Replace special characters with entities, including <, > and ", thus allowing
     * no HTML tags.
     */
    public synchronized void encode(Object what) {
        if (what != null) {
            String str = what.toString();

            if (this.buffer == null) {
                this.buffer = new StringBuffer(Math.max(str.length() + 100, INITIAL_BUFFER_SIZE));
            }

            HtmlEncoder.encodeAll(str, this.buffer);
        }
    }

    /**
     * Replace special characters with entities but pass through HTML tags
     */
    public synchronized void format(Object what) {
        if (what != null) {
            String str = what.toString();

            if (this.buffer == null) {
                this.buffer = new StringBuffer(Math.max(str.length() + 100, INITIAL_BUFFER_SIZE));
            }

            HtmlEncoder.encode(str, this.buffer);
        }
    }

    /**
     * Replace special characters with entities, including <, > and ", thus allowing
     * no HTML tags.
     */
    public synchronized void encodeXml(Object what) {
        if (what != null) {
            String str = what.toString();

            if (this.buffer == null) {
                this.buffer = new StringBuffer(Math.max(str.length() + 100, INITIAL_BUFFER_SIZE));
            }

            HtmlEncoder.encodeXml(str, this.buffer);
        }
    }

    /**
     * Encode HTML entities, but leave newlines alone. This is for the content of textarea forms.
     */
    public synchronized void encodeForm(Object what) {
        if (what != null) {
            String str = what.toString();

            if (this.buffer == null) {
                this.buffer = new StringBuffer(Math.max(str.length() + 100, INITIAL_BUFFER_SIZE));
            }

            HtmlEncoder.encodeAll(str, this.buffer, false);
        }
    }

    /**
     *
     *
     * @param url ...
     *
     * @throws RedirectException ...
     */
    public void redirect(String url) throws RedirectException {
        // remove newline chars to prevent response splitting attack
        this.redir = url == null ?
                null : url.replaceAll("[\r\n]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        throw new RedirectException(this.redir);
    }

    /**
     *
     *
     * @return ...
     */
    public String getRedirect() {
        return this.redir;
    }

    /**
     *
     *
     * @param url ...
     *
     * @throws RedirectException ...
     */
    public void forward(String url) throws RedirectException {
        // remove newline chars to prevent response splitting attack
        this.forward = url == null ?
                null : url.replaceAll("[\r\n]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        throw new RedirectException(this.forward);
    }

    /**
     *
     *
     * @return ...
     */
    public String getForward() {
        return this.forward;
    }

    /**
     *  Allow to directly set the byte array for the response. Calling this more than once will
     *  overwrite the previous output.
     * @param bytes an arbitrary byte array
     */
    public void writeBinary(byte[] bytes) {
        this.response = bytes;
    }

    /**
     * Proxy to HttpServletResponse.addHeader()
     * @param name the header name
     * @param value the header value
     */
    public void addHeader(String name, String value) {
        HttpServletResponse res = getServletResponse();
        if (res != null)
            res.addHeader(name, value);
    }

    /**
     * Proxy to HttpServletResponse.addDateHeader()
     * @param name the header name
     * @param value the header value
     */
    public void addDateHeader(String name, Date value) {
        HttpServletResponse res = getServletResponse();
        if (res != null)
            res.addDateHeader(name, value.getTime());
    }

    /**
     * Proxy to HttpServletResponse.setHeader()
     * @param name the header name
     * @param value the header value
     */
    public void setHeader(String name, String value) {
        HttpServletResponse res = getServletResponse();
        if (res != null)
            res.setHeader(name, value);
    }

    /**
     * Proxy to HttpServletResponse.setDateHeader()
     * @param name the header name
     * @param value the header value
     */
    public void setDateHeader(String name, Date value) {
        HttpServletResponse res = getServletResponse();
        if (res != null)
            res.setDateHeader(name, value.getTime());
    }

    /**
     * Write a vanilla error report. Callers should make sure the ResponeTrans is
     * new or has been reset.
     *
     * @param throwable the error
     */
    public void reportError(Throwable throwable) {
        if (throwable == null) {
            // just to be safe
            reportError(Messages.getString("ResponseTrans.2")); //$NON-NLS-1$
            return;
        }
        if (this.reqtrans.isXmlRpc()) {
            writeXmlRpcError(new RuntimeException(throwable));
        } else {
            this.status = 500;
            if (!"true".equalsIgnoreCase(this.app.getProperty("suppressErrorPage"))) { //$NON-NLS-1$ //$NON-NLS-2$
                write("<html><body>"); //$NON-NLS-1$
                write(Messages.getString("ResponseTrans.3") + this.app.getName() + "</h2><p>");  //$NON-NLS-1$//$NON-NLS-2$
                encode(getErrorMessage(throwable));
                writeln("</p>"); //$NON-NLS-1$
                if (this.app.debug()) {
                    if (throwable instanceof ScriptingException) {
                        ScriptingException scriptx = (ScriptingException) throwable;
                        writeln(Messages.getString("ResponseTrans.4")); //$NON-NLS-1$
                        writeln("<pre>" + scriptx.getScriptStackTrace() + "</pre>"); //$NON-NLS-1$ //$NON-NLS-2$
                        writeln(Messages.getString("ResponseTrans.5")); //$NON-NLS-1$
                        writeln("<pre>" + scriptx.getJavaStackTrace() + "</pre>"); //$NON-NLS-1$ //$NON-NLS-2$
                    } else {
                        writeln(Messages.getString("ResponseTrans.6")); //$NON-NLS-1$
                        writeln("<pre>"); //$NON-NLS-1$
                        throwable.printStackTrace(new PrintWriter(this));
                        writeln("</pre>"); //$NON-NLS-1$
                    }
                }
                writeln("</body></html>"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Write a vanilla error report. Callers should make sure the ResponeTrans is
     * new or has been reset.
     * @param errorMessage the error message
     */
    public void reportError(String errorMessage) {
        if (this.reqtrans.isXmlRpc()) {
            writeXmlRpcError(new RuntimeException(errorMessage));
        } else {
            this.status = 500;
            if (!"true".equalsIgnoreCase(this.app.getProperty("suppressErrorPage"))) { //$NON-NLS-1$ //$NON-NLS-2$
                write("<html><body><h2>"); //$NON-NLS-1$
                write(Messages.getString("ResponseTrans.7")); //$NON-NLS-1$
                write(this.app.getName());
                write("</h2><p>"); //$NON-NLS-1$
                encode(errorMessage);
                writeln("</p></body></html>"); //$NON-NLS-1$
            }
        }
    }

    public void writeXmlRpcResponse(Object result) {
        try {
            reset();
            this.contentType = "text/xml"; //$NON-NLS-1$
            if (this.charset == null) {
                this.charset = "UTF-8"; //$NON-NLS-1$
            }
            XmlRpcResponseProcessor xresproc = new XmlRpcResponseProcessor();
            writeBinary(xresproc.encodeResponse(result, this.charset));
        } catch (Exception x) {
            writeXmlRpcError(x);
        }
    }

    public void writeXmlRpcError(Exception x) {
        this.contentType = "text/xml"; //$NON-NLS-1$
        if (this.charset == null) {
            this.charset = "UTF-8"; //$NON-NLS-1$
        }
        XmlRpcResponseProcessor xresproc = new XmlRpcResponseProcessor();
        writeBinary(xresproc.encodeException(x, this.charset));
    }

    @Override
    public void flush() {
        // does nothing!
    }

    /**
     * This has to be called after writing to this response has finished and before it is shipped back to the
     * web server. Transforms the string buffer into a byte array for transmission.
     */
    @Override
    public void close() throws UnsupportedEncodingException {
        close(null);
    }

    /**
     * This has to be called after writing to this response has finished and before it is shipped back to the
     * web server. Transforms the string buffer into a byte array for transmission.
     * @param defaultCharset the charset to use if no explicit charset has been set on the response
     * @throws UnsupportedEncodingException if the charset is not a valid encoding name
     */
    public synchronized void close(String defaultCharset) throws UnsupportedEncodingException {
        // if the response was already written and committed by the application
        // there's no point in closing the response buffer
        HttpServletResponse res = this.reqtrans.getServletResponse();
        if (res != null && res.isCommitted()) {
            // response was committed using HttpServletResponse directly. We need
            // set response to null and notify waiters in order to let attached
            // requests know they can't reuse this response.
            this.response = null;
            notifyAll();
            return;
        }

        boolean encodingError = false;

        // only close if the response hasn't been closed yet, and if no
        // response was generated using writeBinary().
        if (this.response == null) {
            // only use default charset if not explicitly set for this response.
            if (this.charset == null) {
                this.charset = defaultCharset;
            }
            // if charset is not set, use western encoding
            if (this.charset == null) {
                this.charset = "UTF-8"; //$NON-NLS-1$
            }

            // if debug buffer exists, append it to main buffer
            if (this.contentType != null &&
                    this.contentType.startsWith("text/html") && //$NON-NLS-1$ 
                    this.debugBuffer != null) {
                this.debugBuffer.append("</div>"); //$NON-NLS-1$
                if (this.buffer == null) {
                    this.buffer = this.debugBuffer;
                } else {
                    this.buffer.append(this.debugBuffer);
                }
            }

            // get the buffer's bytes in the specified encoding
            if (this.buffer != null) {
                try {
                    this.response = this.buffer.toString().getBytes(this.charset);
                } catch (UnsupportedEncodingException uee) {
                    encodingError = true;
                    this.response = this.buffer.toString().getBytes();
                }

                // make sure this is done only once, even with more requsts attached
                this.buffer = null;
            } else {
                this.response = new byte[0];
            }
        }

        boolean autoETags = "true".equals(this.app.getProperty("autoETags", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // if etag is not set, calc MD5 digest and check it, but only if
        // not a redirect or error
        if (autoETags &&
                this.etag == null &&
                this.lastModified == -1 &&
                this.status == 200 &&
                this.redir == null) {
            try {
                this.digest = MessageDigest.getInstance("MD5"); //$NON-NLS-1$
                // if (contentType != null)
                //     digest.update (contentType.getBytes());
                byte[] b = this.digest.digest(this.response);
                this.etag = "\"" + new String(Base64.encodeBase64(b)) + "\""; //$NON-NLS-1$ //$NON-NLS-2$
                // only set response to 304 not modified if no cookies were set
                if (this.reqtrans.hasETag(this.etag) && countCookies() == 0) {
                    this.response = new byte[0];
                    this.notModified = true;
                }
            } catch (Exception e) {
                // Etag creation failed for some reason.
                this.app.logError(Messages.getString("ResponseTrans.8") + e); //$NON-NLS-1$
            }
        }

        notifyAll();

        // if there was a problem with the encoding, let the app know
        if (encodingError) {
            throw new UnsupportedEncodingException(this.charset);
        }
    }

    /**
     * If we just attached to evaluation we call this instead of close because only the primary thread
     * is responsible for closing the result
     */
    public synchronized void waitForClose() {
        try {
            if (this.response == null) {
                wait(10000L);
            }
        } catch (InterruptedException ix) {
            // Ignore
        }
    }

    /**
     * Get the body content for this response as byte array, encoded using the
     * response's charset.
     *
     * @return the response body
     */
    public byte[] getContent() {
        return this.response;
    }

    /**
     * Get the number of bytes of the response body.
     *
     * @return the length of the response body
     */
    public int getContentLength() {
        if (this.response != null) {
            return this.response.length;
        }

        return 0;
    }

    /**
     * Get the response's MIME content type
     *
     * @return the MIME type for this response
     */
    public String getContentType() {
        if (this.charset != null) {
            return this.contentType + "; charset=" + this.charset; //$NON-NLS-1$
        }

        return this.contentType;
    }


    /**
     * Set the response's MIME content type
     *
     * @param contentType MIME type for this response
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * Set the Last-Modified header for this response
     *
     * @param modified the Last-Modified header in milliseconds
     */
    public void setLastModified(long modified) {
        // date headers don't do milliseconds, round to seconds
        this.lastModified = (modified / 1000) * 1000;
        if (this.reqtrans.getIfModifiedSince() == this.lastModified) {
            this.notModified = true;
            throw new RedirectException(null);
        }
    }

    /**
     * Get the value of the Last-Modified header for this response.
     *
     * @return the Last-Modified header in milliseconds
     */
    public long getLastModified() {
        return this.lastModified;
    }

    /**
     * Set the ETag header value for this response.
     *
     * @param value the ETag header value
     */
    public void setETag(String value) {
        this.etag = (value == null) ? null : ("\"" + value + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        if (this.etag != null && this.reqtrans.hasETag(this.etag)) {
            this.notModified = true;
            throw new RedirectException(null);
        }
    }

    /**
     * Get the ETag header value for this response.
     *
     * @return the ETag header value
     */
    public String getETag() {
        return this.etag;
    }

    /**
     * Check if this response should generate a Not-Modified response.
     *
     * @return true if the the response wasn't modified since the client last saw it.
     */
    public boolean getNotModified() {
        return this.notModified;
    }

    /**
     * Add a dependency to this response.
     *
     * @param what an item this response's output depends on.
     */
    public void dependsOn(Object what) {
        if (this.digest == null) {
            try {
                this.digest = MessageDigest.getInstance("MD5"); //$NON-NLS-1$
            } catch (NoSuchAlgorithmException nsa) {
                // MD5 should always be available
            }
        }

        if (what == null) {
            this.digest.update(new byte[0]);
        } else if (what instanceof Date) {
            this.digest.update(Long.toBinaryString(((Date) what).getTime()).getBytes());
        } else if (what instanceof byte[]) {
            this.digest.update((byte[]) what);
        } else {
            String str = what.toString();

            if (str != null) {
                this.digest.update(str.getBytes());
            } else {
                this.digest.update(new byte[0]);
            }
        }
    }

    /**
     * Digest all dependencies to a checksum to see if the response has changed.
     */
    public void digestDependencies() {
        if (this.digest == null) {
            return;
        }

        // add the application checksum as dependency to make ETag
        // generation sensitive to changes in the app
        byte[] b = this.digest.digest(Long.toBinaryString((this.app.getChecksum())).getBytes());

        setETag(new String(Base64.encodeBase64(b)));
    }

    /**
     * Set the path in which to look for skins. This may contain file locations and
     * HopObjects.
     *
     * @param arr the skin path
     */
    public void setSkinpath(Object[] arr) {
        this.skinpath = arr;
        this.skincache = null;
    }

    /**
     * Get the path in which to look for skins. This may contain file locations and
     * HopObjects.
     *
     * @return the skin path
     */
    public Object[] getSkinpath() {
        if (this.skinpath == null) {
            this.skinpath = new Object[0];
        }

        return this.skinpath;
    }

    /**
     * Look up a cached skin.
     *
     * @param id the skin key
     * @return the skin, or null if no skin is cached for the given key
     */
    public Skin getCachedSkin(Object id) {
        if (this.skincache == null) {
            return null;
        }

        return (Skin) this.skincache.get(id);
    }

    /**
     * Cache a skin for the length of this response.
     *
     * @param id the skin key
     * @param skin the skin to cache
     */
    public void cacheSkin(Object id, Skin skin) {
        if (this.skincache == null) {
            this.skincache = new HashMap();
        }

        this.skincache.put(id, skin);
    }

    /**
     * Set the skin currently being rendered, returning the previously active skin.
     * @param skin the new active skin
     * @return the previously active skin
     */
    public Skin switchActiveSkin(Skin skin) {
        Skin previousSkin = this.activeSkin;
        this.activeSkin = skin;
        return previousSkin;
    }

    /**
     * Return the skin currently being rendered, or none.
     * @return the currently active skin
     */
    public Skin getActiveSkin() {
        return this.activeSkin;
    }

    /**
     * Set a cookie.
     *
     * @param key the cookie key
     * @param value the cookie value
     * @param days the cookie's lifespan in days
     * @param path the URL path to apply the cookie to
     * @param domain the domain to apply the cookie to
     */
    public void setCookie(String key, String value, int days, String path, String domain) {
        CookieTrans c = null;

        if (this.cookies == null) {
            this.cookies = new HashMap();
        } else {
            c = (CookieTrans) this.cookies.get(key);
        }

        // remove newline chars to prevent response splitting attack
        if (value != null) {
            value = value.replaceAll("[\r\n]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (c == null) {
            c = new CookieTrans(key, value);
            this.cookies.put(key, c);
        } else {
            c.setValue(value);
        }

        c.setDays(days);
        c.setPath(path);
        c.setDomain(domain);
    }

    /**
     * Reset all previously set cookies.
     */
    public void resetCookies() {
        if (this.cookies != null) {
            this.cookies.clear();
        }
    }

    /**
     * Get the number of cookies set in this response.
     *
     * @return the number of cookies
     */
    public int countCookies() {
        if (this.cookies != null) {
            return this.cookies.size();
        }

        return 0;
    }

    /**
     * Get the cookies set in this response.
     *
     * @return the cookies
     */
    public CookieTrans[] getCookies() {
        if (this.cookies == null) {
            return new CookieTrans[0];
        }

        CookieTrans[] c = new CookieTrans[this.cookies.size()];
        this.cookies.values().toArray(c);
        return c;
    }

    /**
     * Get the message to display to the user, if any.
     * @return the message
     */
    public String getMessage() {
        return this.message;
    }

    /**
     * Set a message to display to the user.
     * @param message the message
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * Get the error message to display to the user, if any.
     * @return the error message
     */
    public Throwable getError() {
        return this.error;
    }

    /**
     * Set a message to display to the user.
     * @param error the error message
     */
    public void setError(Throwable error) {
        this.error = error;
    }

    public String getErrorMessage() {
        if (this.error == null)
            return null;
        return getErrorMessage(this.error);
    }

    private static String getErrorMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.length() == 0)
            msg = t.toString();
        if (msg == null || msg.length() == 0)
            return Messages.getString("ResponseTrans.9") + t.getClass().getName(); //$NON-NLS-1$
        return msg;
    }

    /**
     * Get debug messages to append to the response, if any.
     * @return the response's debug buffer
     */
    public StringBuffer getDebugBuffer() {
        return this.debugBuffer;
    }

    /**
     * Set debug messages to append to the response.
     * @param debugBuffer the response's debug buffer
     */
    public void setDebugBuffer(StringBuffer debugBuffer) {
        this.debugBuffer = debugBuffer;
    }

    /**
     * Get the charset/encoding for this response
     * @return the charset name
     */
    public String getCharset() {
        return this.charset;
    }

    /**
     * Set the charset/encoding for this response
     * @param charset the charset name
     */
    public void setCharset(String charset) {
        this.charset = charset;
    }

    /**
     * Returns true if this response may be cached by the client
     * @return true if the response may be cached
     */
    public boolean isCacheable() {
        return this.cacheable;
    }

    /**
     * Set the cacheability of this response
     * @param cache true if the response may be cached
     */
    public void setCacheable(boolean cache) {
        this.cacheable = cache;
    }

    /**
     * Get the HTTP response status code
     * @return the HTTP response code
     */
    public int getStatus() {
        return this.status;
    }

    /**
     * Set the HTTP response status code
     * @param status the HTTP response code
     */
    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * Get the HTTP authentication realm
     * @return the name of the authentication realm
     */
    public String getRealm() {
        return this.realm;
    }

    /**
     * Set the HTTP authentication realm
     * @param realm the name of the authentication realm
     */
    public void setRealm(String realm) {
        this.realm = realm;
    }
}