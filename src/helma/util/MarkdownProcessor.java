package helma.util;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class MarkdownProcessor {

    private HashMap links = new HashMap();
    private int state;
    private int i;
    private int length;
    private char[] chars;
    private StringBuilder buffer;
    private int lineMarker = 0;
    private int paragraphStartMarker = 0;
    private boolean listParagraphs = false;
    private int codeEndMarker = 0;
    private ElementStack stack = new ElementStack();
    private Emphasis[] emph = new Emphasis[2];

    private String result = null;

    // private Logger log = Logger.getLogger(MarkdownProcessor.class);
    private int line;

    private final int
        // stage 1 states
        NONE = 0, NEWLINE = 1, LINK_ID = 2, LINK_URL = 3,
        // stage 2 states
        HEADER = 4, PARAGRAPH = 5, LIST = 6, HTML_BLOCK = 7, CODE = 8;

    static final Set blockTags = new HashSet();

    static {
        blockTags.add("p"); //$NON-NLS-1$
        blockTags.add("div"); //$NON-NLS-1$
        blockTags.add("h1"); //$NON-NLS-1$
        blockTags.add("h2"); //$NON-NLS-1$
        blockTags.add("h3"); //$NON-NLS-1$
        blockTags.add("h4"); //$NON-NLS-1$
        blockTags.add("h5"); //$NON-NLS-1$
        blockTags.add("h6"); //$NON-NLS-1$
        blockTags.add("blockquote"); //$NON-NLS-1$
        blockTags.add("pre"); //$NON-NLS-1$
        blockTags.add("table"); //$NON-NLS-1$
        blockTags.add("tr"); // handle <tr> as block tag for pragmatical reasons //$NON-NLS-1$
        blockTags.add("dl"); //$NON-NLS-1$
        blockTags.add("ol"); //$NON-NLS-1$
        blockTags.add("ul"); //$NON-NLS-1$
        blockTags.add("script"); //$NON-NLS-1$
        blockTags.add("noscript"); //$NON-NLS-1$
        blockTags.add("form"); //$NON-NLS-1$
        blockTags.add("fieldset"); //$NON-NLS-1$
        blockTags.add("iframe"); //$NON-NLS-1$
        blockTags.add("math"); //$NON-NLS-1$
    }

    public MarkdownProcessor() {}

    public MarkdownProcessor(String text) {
        init(text);
    }

    public MarkdownProcessor(File file) throws IOException {
        length = (int) file.length();
        chars = new char[length + 2];
        FileReader reader = new FileReader(file);
        int read = 0;
        try {
            while (read < length) {
                int r = reader.read(chars, read, length - read);
                if (r == -1)
                    break;
                read += r;
            }
        } finally {
            reader.close();
        }
        length = read;
        chars[length] = chars[length + 1] = '\n';
    }

    public synchronized String process(String text) {
        init(text);
        return process();
    }

    public synchronized String process() {
        if (result == null) {
            length = chars.length;
            firstPass();
            secondPass();
            result = buffer.toString();
            cleanup();
        }
        return result;
    }

    public synchronized String processLinkText(String text) {
        init(text);
        return processLinkText();
    }

    private void init(String text) {
        length = text.length();
        chars = new char[length + 2];
        text.getChars(0, length, chars, 0);
        chars[length] = chars[length + 1] = '\n';
    }

   /**
    * Retrieve a link defined in the source text. If the link is not found, we call
    * lookupLink(String) to retrieve it from an external source.
    * @param linkId the link id
    * @return a String array with the url as first element and the link title as second.
    */
    protected String[] getLink(String linkId) {
        String[] link =  (String[]) links.get(linkId);
        if (link == null) {
            link = lookupLink(linkId);
        }
        return link;
    }

    /**
     * Method to override for extended link lookup, e.g. for integration into a wiki
     * @param linkId the link id
     * @return a String array with the url as first element and the link title as second.
     */
    protected String[] lookupLink(String linkId) {
        return null;
    }

    /**
     * Method to override to create custom HTML tags.
     * @param tag the html tag to generate
     * @param builder the java.lang.StringBuilder to generate the string
     */
    protected void openTag(String tag, StringBuilder builder) {
        builder.append('<').append(tag).append('>');
    }

    /**
     * First pass: extract links definitions and remove them from the source text.
     */
    private synchronized void firstPass() {
        int state = NEWLINE;
        int linestart = 0;
        int indentation = 0;
        int indentationChars = 0;
        String linkId = null;
        String[] linkValue = null;
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < length; i++) {
            // convert \r\n and \r newlines to \n
            if (chars[i] == '\r') {
                if (i < length && chars[i + 1] == '\n') {
                    System.arraycopy(chars, i + 1, chars, i, (length - i) - 1);
                    length -= 1;
                } else {
                    chars[i] = '\n';
                }
            }
        }

        for (int i = 0; i < length; i++) {
            char c = chars[i];

            switch (state) {
                case NEWLINE:
                    if (c == '[' && indentation < 4) {
                        state = LINK_ID;
                    } else if (isSpace(c)) {
                        indentationChars += 1;
                        indentation += (c == '\t') ? 4 : 1;
                    } else if (c == '\n' && indentationChars > 0) {
                        System.arraycopy(chars, i, chars, i - indentationChars, length - i);
                        i -= indentationChars;
                        length -= indentationChars;
                    } else {
                        state = NONE;
                    }
                    break;
                case LINK_ID:
                    if (c == ']') {
                        if (i < length - 1 && chars[i + 1] == ':') {
                            linkId = buffer.toString();
                            linkValue = new String[2];
                            state = LINK_URL;
                            i++;
                        } else {
                            state = NONE;
                        }
                        buffer.setLength(0);
                    } else {
                        buffer.append(c);
                    }
                    break;
                case LINK_URL:
                    if (c == '<' && buffer.length() == 0) {
                        continue;
                    } else if ((Character.isWhitespace(c) || c == '>') && buffer.length() > 0) {
                        linkValue[0] = buffer.toString().trim();
                        buffer.setLength(0);
                        int j = i + 1;
                        int newlines = c == '\n' ? 1 : 0;
                        while (j < length && Character.isWhitespace(chars[j])) {
                            if (chars[j] == '\n') {
                                newlines += 1;
                                if (newlines > 1) {
                                    break;
                                } else {
                                    i = j;
                                    c = chars[j];
                                }
                            }
                            j += 1;
                        }
                        if (j < length && newlines <= 1 && isLinkQuote(chars[j])) {
                            char quoteChar = chars[j] == '(' ? ')' : chars[j];
                            int start = j = j + 1;
                            int len = -1;
                            while (j < length && chars[j] != '\n') {
                                if (chars[j] == quoteChar) {
                                    len = j - start;
                                } else if (len > -1 && !isSpace(chars[j])) {
                                    len = -1;
                                }
                                j += 1;
                            }
                            if (len > -1) {
                                linkValue[1] = new String(chars, start, len);
                                i = j;
                                c = chars[j];
                            }
                        }
                        if (c == '\n' && state != NONE) {
                            links.put(linkId.toLowerCase(), linkValue);
                            System.arraycopy(chars, i, chars, linestart, length - i);
                            length -= (i - linestart);
                            i = linestart;
                            buffer.setLength(0);
                            linkId = null;
                        } else {
                            // no valid link title - escape
                            state = NONE;
                        }
                    } else if (!isSpace(c) || buffer.length() > 0) {
                        buffer.append(c);
                    }
            }

            if (c == '\n') {
                state = NEWLINE;
                linestart = i;
                indentation = indentationChars = 0;
            }

        }
    }

    private synchronized void secondPass() {
        state = NEWLINE;
        stack.add(new BaseElement());
        buffer = new StringBuilder((int) (length * 1.2));
        line = 1;
        boolean escape = false;

        for (i = 0; i < length; ) {
            char c;

            if (state == NEWLINE) {
                checkBlock(0);
            }

            boolean leadingSpaceChars = true;

            while (i < length && chars[i] != '\n') {

                c = chars[i];
                leadingSpaceChars = leadingSpaceChars && isSpace(c);

                if (state == HTML_BLOCK) {
                    buffer.append(c);
                    i += 1;
                    continue;
                }

                if (escape) {
                    buffer.append(c);
                    escape = false;
                    i += 1;
                    continue;
                } else if (c == '\\') {
                    escape = true;
                    i += 1;
                    continue;
                }

                switch (c) {
                    case '*':
                    case '_':
                        if (checkEmphasis(c)) {
                            continue;
                        }
                        break;

                    case '`':
                        if (checkCodeSpan(c)) {
                            continue;
                        }
                        break;

                    case '[':
                        if (checkLink(c)) {
                            continue;
                        }
                        break;

                    case '!':
                        if (checkImage()) {
                            continue;
                        }
                        break;

                    case '<':
                        if (checkHtmlLink(c)) {
                            continue;
                        }
                        break;
                }

                if (state == HEADER) {
                    if (c == '#') {
                        ((Element) stack.peek()).mod++;
                    } else {
                        ((Element) stack.peek()).mod = 0;
                    }
                }

                if (!leadingSpaceChars) {
                    buffer.append(c);
                }
                i += 1;

            }

            while (i < length && chars[i] == '\n') {

                c = chars[i];
                line += 1;

                if (state == HTML_BLOCK &&
                        (i >= length - 1 || chars[i + 1] != '\n')) {
                    buffer.append(c);
                    i += 1;
                    continue;
                }
                if (state == HEADER) {
                    Element header = (Element) stack.pop();
                    if (header.mod > 0) {
                        buffer.setLength(buffer.length() - header.mod);
                    }
                    header.close();
                }

                int bufLen = buffer.length();
                boolean markParagraph = bufLen > 0 && buffer.charAt(bufLen - 1) == '\n';

                if (state == LIST && i < length) {
                    checkParagraph(listParagraphs);
                }
                if (state == PARAGRAPH && i < length) {
                    checkParagraph(true);
                    checkHeader();
                }

                buffer.append(c);
                state = NEWLINE;
                lineMarker = buffer.length();

                if (markParagraph) {
                    paragraphStartMarker = lineMarker;
                }
                i += 1;

            }

        }
        while (!stack.isEmpty()) {
            ((Element) stack.pop()).close();
        }
    }

    private boolean checkBlock(int blockquoteNesting) {
        int indentation = 0;
        int j = i;
        while (j < length && isSpace(chars[j])) {
            indentation += chars[j] == '\t' ? 4 : 1;
            j += 1;
        }

        if (j < length) {
            char c = chars[j];

            if (checkBlockquote(c, j, indentation, blockquoteNesting)) {
                return true;
            }

            if (checkCodeBlock(c, j, indentation, blockquoteNesting)) {
                return true;
            }

            if (checkList(c, j, indentation, blockquoteNesting)) {
                return true;
            }

            if (checkAtxHeader(c, j)) {
                return true;
            }

            if (!checkHtmlBlock(c, j)) {
                state = stack.search(ListElement.class) != null ? LIST : PARAGRAPH;
            }
        }
        return false;
    }

    private boolean checkEmphasis(char c) {
        for (int l = 1; l >= 0; l--) {
            if (emph[l] != null && emph[l].end == i) {
                emph[l].close();
                i += emph[l].mod;
                emph[l] = null;
                return true;
            }
        }
        if (c == '*' || c == '_') {
            int n = 1;
            int j = i + 1;
            while(j < length && chars[j] == c && n <= 3) {
                n += 1;
                j += 1;
            }
            int found = n;
            boolean isStartTag = j < length  - 1 && !Character.isWhitespace(chars[j]);
            if (isStartTag && (emph[0] == null || emph[1] == null)) {
                List possibleEndTags = new ArrayList();
                char lastChar = 0;
                int count = 0;
                boolean escape = false;
                for (int k = j; k <= length; k++) {
                    if (chars[k] == '\n' && lastChar == '\n') {
                        break;
                    }
                    lastChar = chars[k];

                    if (escape) {
                        escape = false;
                    } else {
                        if (chars[k] == '\\') {
                            escape = true;
                        } else if (chars[k] == '`') {
                            k = skipCodeSpan(k);
                        } else if (chars[k] == '[') {
                            k = skipLink(k);
                        } else if (chars[k] == c) {
                            count += 1;
                        } else {
                            if (count > 0 && !Character.isWhitespace(chars[k - count - 1])) {
                                // add an int array to possible end tags: [position, nuberOfTokens]
                                possibleEndTags.add(new int[] {k - count, count});
                            }
                            count = 0;
                        }
                    }
                }

                for (int l = 1; l >= 0; l--) {
                    if (emph[l] == null && n > l) {
                        emph[l] = checkEmphasisInternal(l + 1, possibleEndTags);
                        if (emph[l] != null) {
                            n -= l + 1;
                        }
                    }
                }
            }
            if (n == found) {
                return false;
            }
            // write out remaining token chars
            for (int m = 0; m < n; m++) {
                buffer.append(c);
            }
            i = j;
            return true;
        }
        return false;
    }

    private Emphasis checkEmphasisInternal(int length, List possibleEndTags) {
        for (int k = 0; k < possibleEndTags.size(); k++) {
            int[] possibleEndTag = (int[]) possibleEndTags.get(k);
            if (possibleEndTag[1] >= length) {
                Emphasis elem = new Emphasis(length, possibleEndTag[0]);
                elem.open();
                possibleEndTag[0] += length;
                possibleEndTag[1] -= length;
                return elem;
            }
        }
        return null;
    }

    private boolean checkCodeSpan(char c) {
        if (c != '`') {
            return false;
        }
        int n = 0; // additional backticks to match
        int j = i + 1;
        StringBuffer code = new StringBuffer();
        while(j < length && chars[j] == '`') {
            n += 1;
            j += 1;
        }
        outer: while(j < length) {
            if (chars[j] == '`') {
                if (n == 0) {
                    break;
                } else {
                    if (j + n >= length) {
                        return false;
                    }
                    for (int k = j + 1; k <= j + n; k++) {
                        if (chars[k] != '`') {
                            break;
                        } else if (k == j + n) {
                            j = k;
                            break outer;
                        }
                    }

                }
            }
            if (chars[j] == '&') {
                code.append("&amp;"); //$NON-NLS-1$
            } else if (chars[j] == '<') {
                code.append("&lt;"); //$NON-NLS-1$
            } else if (chars[j] == '>') {
                code.append("&gt;"); //$NON-NLS-1$
            } else {
                code.append(chars[j]);
            }
            j += 1;
        }
        openTag("code", buffer); //$NON-NLS-1$
        buffer.append(code.toString().trim()).append("</code>"); //$NON-NLS-1$
        i = j + 1;
        return true;
    }

    // find the end of a code span starting at start
    private int skipCodeSpan(int start) {
        int n = 0; // additional backticks to match
        int j = start + 1;
        while(j < length && chars[j] == '`') {
            n += 1;
            j += 1;
        }
        outer: while(j < length) {
            if (chars[j] == '`') {
                if (n == 0) {
                    break;
                } else {
                    if (j + n >= length) {
                        return start + 1;
                    }
                    for (int k = j + 1; k <= j + n; k++) {
                        if (chars[k] != '`') {
                            break;
                        } else if (k == j + n) {
                            j = k;
                            break outer;
                        }
                    }
                }
            }
            j += 1;
        }
        return j;
    }

    private int skipLink(int start) {
        boolean escape = false;
        int nesting = 0;
        int j = start + 1;
        char c;
        while (j < length && (escape || chars[j] != ']' || nesting != 0)) {
            c = chars[j];
            if (c == '\n' && chars[j - 1] == '\n') {
                return start;
            }

            if (escape) {
                escape = false;
            } else {
                escape = c == '\\';
                if (!escape) {
                    if (c == '[') {
                        nesting += 1;
                    } else if (c == ']') {
                        nesting -= 1;
                    }
                }
            }
            j += 1;
        }
        int k = j;
        j += 1;
        boolean extraSpace = false;
        if (j < length && Character.isWhitespace(chars[j])) {
            j += 1;
            extraSpace = true;
        }
        c = chars[j++];
        if (c == '[') {
            while (j < length && chars[j] != ']') {
                if (chars[j] == '\n') {
                    return start;
                }
                j += 1;
            }
        } else if (c == '(' && !extraSpace) {
            while (j < length && chars[j] != ')' && !isSpace(chars[j])) {
                if (chars[j] == '\n') {
                    return start;
                }
                j += 1;
            }
            if (j < length && chars[j] != ')') {
                while (j < length && chars[j] != ')' && Character.isWhitespace(chars[j])) {
                    j += 1;
                }
                if (chars[j] == '"') {
                    int quoteStart = j = j + 1;
                    int len = -1;
                    while (j < length && chars[j] != '\n') {
                        if (chars[j] == '"') {
                            len = j - quoteStart;
                        } else if (len > -1) {
                            if (chars[j] == ')') {
                                break;
                            } else if (!isSpace(chars[j])) {
                                len = -1;
                            }
                        }
                        j += 1;
                    }
                }
                if (chars[j] != ')') {
                    return start;
                }
            }
        } else {
            j = k;
        }
        return j;
    }

    private boolean checkLink(char c) {
        return checkLinkInternal(c, i + 1, false);
    }

    private boolean checkImage() {
        return checkLinkInternal(chars[i + 1], i + 2,  true);
    }

    private boolean checkLinkInternal(char c, int j, boolean isImage) {
        if (c != '[') {
            return false;
        }
        StringBuffer b = new StringBuffer();
        boolean escape = false;
        boolean space = false;
        int nesting = 0;
        boolean needsEncoding = false;
        while (j < length && (escape || chars[j] != ']' || nesting != 0)) {
            c = chars[j];
            if (c == '\n' && chars[j - 1] == '\n') {
                return false;
            }

            if (escape) {
                b.append(c);
                escape = false;
            } else {
                escape = c == '\\';
                if (!escape) {
                    if (c == '[') {
                        nesting += 1;
                    } else if (c == ']') {
                        nesting -= 1;
                    }
                    if (c == '*' || c == '_' || c == '`' || c == '[') {
                        needsEncoding = true;
                    }
                    boolean s = Character.isWhitespace(chars[j]);
                    if (!space || !s) {
                        b.append(s ? ' ' : c);
                    }
                    space = s;
                }
            }
            j += 1;
        }
        String text = b.toString();
        b.setLength(0);
        String[] link;
        String linkId;
        int k = j;
        j += 1;
        // this is weird, but we follow the official markup implementation here:
        // only accept space between link text and link target for [][], not for []()
        boolean extraSpace = false;
        if (j < length && Character.isWhitespace(chars[j])) {
            j += 1;
            extraSpace = true;
        }
        c = chars[j++];
        if (c == '[') {
            while (j < length && chars[j] != ']') {
                if (chars[j] == '\n') {
                    return false;
                }
                b.append(chars[j]);
                j += 1;
            }
            linkId = b.toString().toLowerCase();
            if (linkId.length() > 0) {
                link = getLink(linkId);
                if (link == null) {
                    return false;
                }
            } else {
                linkId = text.toLowerCase();
                link = getLink(linkId);
                if (link == null) {
                    return false;
                }
            }
        } else if (c == '(' && !extraSpace) {
            link = new String[2];
            while (j < length && chars[j] != ')' && !isSpace(chars[j])) {
                if (chars[j] == '\n') {
                    return false;
                }
                b.append(chars[j]);
                j += 1;
            }
            link[0] = b.toString();
            if (j < length && chars[j] != ')') {
                while (j < length && chars[j] != ')' && Character.isWhitespace(chars[j])) {
                    j += 1;
                }
                if (chars[j] == '"') {
                    int start = j = j + 1;
                    int len = -1;
                    while (j < length && chars[j] != '\n') {
                        if (chars[j] == '"') {
                            len = j - start;
                        } else if (len > -1) {
                            if (chars[j] == ')') {
                                link[1] = new String(chars, start, len);
                                break;
                            } else if (!isSpace(chars[j])) {
                                len = -1;
                            }
                        }
                        j += 1;
                    }
                }
                if (chars[j] != ')') {
                    return false;
                }
            }
        } else {
            j = k;
            linkId = text.toLowerCase();
            link = getLink(linkId);
            if (link == null) {
                return false;
            }
        }
        b.setLength(0);
        if (isImage) {
            buffer.append("<img src=\"").append(escapeHtml(link[0])).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            buffer.append(" alt=\"").append(escapeHtml(text)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            if (link[1] != null) {
                buffer.append(" title=\"").append(escapeHtml(link[1])).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
            buffer.append(" />"); //$NON-NLS-1$

        } else {
            buffer.append("<a href=\"").append(escapeHtml(link[0])).append("\"");  //$NON-NLS-1$//$NON-NLS-2$
            if (link[1] != null) {
                buffer.append(" title=\"").append(escapeHtml(link[1])).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
            buffer.append(">"); //$NON-NLS-1$
            if (needsEncoding) {
                MarkdownProcessor wrapped = new MarkdownProcessor();
                buffer.append(wrapped.processLinkText(text)).append("</a>"); //$NON-NLS-1$
            } else {
                buffer.append(escapeHtml(text)).append("</a>"); //$NON-NLS-1$
            }
        }
        i = j + 1;
        return true;
    }

    private boolean checkHtmlLink(char c) {
        if (c != '<') {
            return false;
        }
        int k = i + 1;
        int j = k;
        while (j < length && !Character.isWhitespace(chars[j]) && chars[j] != '>') {
            j += 1;
        }
        if (chars[j] == '>') {
            String href = new String(chars, k, j - k);
            if (href.matches("\\w+:\\S*")) { //$NON-NLS-1$
                String text = href;
                if (href.startsWith("mailto:")) { //$NON-NLS-1$
                    text = href.substring(7);
                    href = escapeMailtoUrl(href);
                }
                buffer.append("<a href=\"").append(href).append("\">")  //$NON-NLS-1$//$NON-NLS-2$
                        .append(text).append("</a>"); //$NON-NLS-1$
                i = j + 1;
                return true;
            } else if (href.matches("^.+@.+\\.[a-zA-Z]+$")) { //$NON-NLS-1$
                buffer.append("<a href=\"") //$NON-NLS-1$
                        .append(escapeMailtoUrl("mailto:" + href)).append("\">") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(href).append("</a>"); //$NON-NLS-1$
                i = j + 1;
                return true;
            }
        }
        return false;
    }

    private boolean checkList(char c, int j, int indentation, int blockquoteNesting) {
        int nesting = indentation / 4 + blockquoteNesting;
        if (c >= '0' && c <= '9') {
            while (j < length && chars[j] >= '0' && chars[j] <= '9' ) {
                j += 1;
            }
            if (j < length && chars[j] == '.') {
                checkCloseList("ol", nesting); //$NON-NLS-1$
                checkOpenList("ol", nesting); //$NON-NLS-1$
                i = j + 1;
                return true;
            }
        } else if (c == '*' || c == '+' || c == '-') {
            if (c != '+' && checkHorizontalRule(c, j, nesting)) {
                return true;
            }
            j += 1;
            if (j < length && isSpace(chars[j])) {
                checkCloseList("ul", nesting); //$NON-NLS-1$
                checkOpenList("ul", nesting); //$NON-NLS-1$
                i = j;
                return true;
            }
        }
        if (isParagraphStart()) {
            // never close list unless there's an empty line
            checkCloseList(null, nesting - 1);
        }
        return false;
    }

    private void checkOpenList(String tag, int nesting) {
        Element list = stack.search(ListElement.class);
        if (list == null || !tag.equals(list.tag) || nesting != list.nesting) {
            list = new ListElement(tag, nesting);
            stack.push(list);
            list.open();
        } else {
            stack.closeElementsExclusive(list);
            buffer.insert(getBufferEnd(), "</li>"); //$NON-NLS-1$
        }
        openTag("li", buffer); //$NON-NLS-1$
        listParagraphs = isParagraphStart();
        lineMarker = paragraphStartMarker = buffer.length();
        state = LIST;
    }

    private void checkCloseList(String tag, int nesting) {
        Element elem = stack.search(ListElement.class);
        while (elem != null &&
                (elem.nesting > nesting ||
                (elem.nesting == nesting && tag != null && !elem.tag.equals(tag)))) {
            stack.closeElements(elem);
            elem = stack.peekNestedElement();
            lineMarker = paragraphStartMarker = buffer.length();
        }
    }

    private boolean checkCodeBlock(char c, int j, int indentation, int blockquoteNesting) {
        int nesting = indentation / 4;
        int nestedLists = stack.countNestedLists(null);
        Element code;
        if (nesting - nestedLists <= 0) {
            code = stack.findNestedElement(CodeElement.class, blockquoteNesting + nestedLists);
            if (code != null) {
                stack.closeElements(code);
                lineMarker = paragraphStartMarker = buffer.length();
            }
            return false;
        }
        code = stack.isEmpty() ? null : (Element) stack.peek();
        if (!(code instanceof CodeElement)) {
            code = new CodeElement(blockquoteNesting + nestedLists);
            code.open();
            stack.push(code);
        }
        int sub = 4 + nestedLists * 4;
        for (int k = sub; k < indentation; k++) {
            buffer.append(' ');
        }
        while(j < length && chars[j] != '\n') {
            if (chars[j] == '&') {
                buffer.append("&amp;"); //$NON-NLS-1$
            } else if (chars[j] == '<') {
                buffer.append("&lt;"); //$NON-NLS-1$
            } else if (chars[j] == '>') {
                buffer.append("&gt;"); //$NON-NLS-1$
            } else if (chars[j] == '\t') {
                buffer.append("   "); //$NON-NLS-1$
            } else {
                buffer.append(chars[j]);
            }
            j += 1;
        }
        codeEndMarker = buffer.length();
        i = j;
        state = CODE;
        return true;
    }

    private boolean checkBlockquote(char c, int j, int indentation, int blockquoteNesting) {
        int nesting = indentation / 4;
        Element elem = stack.findNestedElement(BlockquoteElement.class, nesting + blockquoteNesting);
        if (c != '>' && isParagraphStart() || nesting > stack.countNestedLists(elem)) {
            elem = stack.findNestedElement(BlockquoteElement.class, blockquoteNesting);
            if (elem != null) {
                stack.closeElements(elem);
                lineMarker = paragraphStartMarker = buffer.length();
            }
            return false;
        }
        nesting +=  blockquoteNesting;
        elem = stack.findNestedElement(BlockquoteElement.class, nesting);
        if (c == '>') {
            stack.closeElementsUnlessExists(BlockquoteElement.class, nesting);
            if (elem != null && !(elem instanceof BlockquoteElement)) {
                stack.closeElements(elem);
                elem = null;
            }
            if (elem == null || elem.nesting < nesting) {
                elem = new BlockquoteElement(nesting);
                elem.open();
                stack.push(elem);
                lineMarker = paragraphStartMarker = buffer.length();
            } else {
                lineMarker = buffer.length();
            }
            i = isSpace(chars[j+ 1]) ? j + 2 : j + 1;
            state = NEWLINE;
            checkBlock(nesting + 1);
            return true;
        } else {
            return elem instanceof BlockquoteElement;
        }
    }


    private void checkParagraph(boolean paragraphs) {
        int paragraphEndMarker = getBufferEnd();
        if (paragraphs && paragraphEndMarker > paragraphStartMarker &&
                (chars[i + 1] == '\n' || buffer.charAt(buffer.length() - 1) == '\n')) {
            buffer.insert(paragraphEndMarker, "</p>"); //$NON-NLS-1$
            buffer.insert(paragraphStartMarker, "<p>"); //$NON-NLS-1$
        } else if (i > 1 && chars[i-1] == ' ' && chars[i-2] == ' ') {
            buffer.append("<br />"); //$NON-NLS-1$
        }
    }

    private boolean checkAtxHeader(char c, int j) {
        if (c == '#') {
            int nesting = 1;
            int k = j + 1;
            while (k < length && chars[k++] == '#') {
                nesting += 1;
            }
            HeaderElement header = new HeaderElement(nesting);
            header.open();
            stack.push(header);
            state = HEADER;
            i = k - 1;
            return true;
        }
        return false;
    }

    private boolean checkHtmlBlock(char c, int j) {
        if (c == '<') {
            j += 1;
            int k = j;
            while (k < length && Character.isLetterOrDigit(chars[k])) {
                k += 1;
            }
            String tag = new String(chars, j, k - j).toLowerCase();
            if (blockTags.contains(tag)) {
                state = HTML_BLOCK;
                return true;
            }
        }
        return false;
    }

    private void checkHeader() {
        char c = chars[i + 1];
        if (c == '-' || c == '=') {
            int j = i + 1;
            while (j < length && chars[j] == c) {
                j++;
            }
            if (j < length && chars[j] == '\n') {
                if (c == '=') {
                    buffer.insert(lineMarker, "<h1>"); //$NON-NLS-1$
                    buffer.append("</h1>"); //$NON-NLS-1$
                } else {
                    buffer.insert(lineMarker, "<h2>"); //$NON-NLS-1$
                    buffer.append("</h2>"); //$NON-NLS-1$
                }
                i = j;
            }
        }
    }

    private boolean checkHorizontalRule(char c, int j, int nesting) {
        if (c != '*' && c != '-') {
            return false;
        }
        int count = 1;
        int k = j;
        while (k < length && (isSpace(chars[k]) || chars[k] == c)) {
            k += 1;
            if (chars[k] == c) {
                 count += 1;
            }
        }
        if (count >= 3 &&  chars[k] == '\n') {
            checkCloseList(null, nesting - 1);
            buffer.append("<hr />"); //$NON-NLS-1$
            i = k;
            return true;
        }
        return false;
    }

    private void cleanup() {
        links = null;
        chars = null;
        buffer = null;
        stack = null;
    }

    private String escapeHtml(String str) {
        if (str.indexOf('"') > -1) {
            str = str.replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (str.indexOf('<') > -1) {
            str = str.replace("\"", "&lt;");  //$NON-NLS-1$//$NON-NLS-2$
        }
            if (str.indexOf('>') > -1) {
            str = str.replace("\"", "&gt;");  //$NON-NLS-1$//$NON-NLS-2$
        }
        return str;
    }

    private String escapeMailtoUrl(String str) {
        StringBuffer b = new StringBuffer();
        char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            double random = Math.random();
            if (random < 0.5) {
                b.append("&#x").append(Integer.toString(chars[i], 16)).append(";"); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (random < 0.9) {
                b.append("&#").append(Integer.toString(chars[i], 10)).append(";");  //$NON-NLS-1$//$NON-NLS-2$
            } else {
                b.append(chars[i]);
            }
        }
        return b.toString();
    }

    private synchronized String processLinkText() {
        buffer = new StringBuilder((int) (length * 1.2));
        line = 1;
        boolean escape = false;

        for (i = 0; i < length; ) {
            char c = chars[i];

            if (escape) {
                buffer.append(c);
                escape = false;
                i += 1;
                continue;
            } else if (c == '\\') {
                escape = true;
                i += 1;
                continue;
            }
            switch (c) {
                case '*':
                case '_':
                    if (checkEmphasis(c)) {
                        continue;
                    }
                    break;

                case '`':
                    if (checkCodeSpan(c)) {
                        continue;
                    }
                    break;

                case '!':
                    if (checkImage()) {
                        continue;
                    }
                    break;
            }

            buffer.append(c);
            i += 1;
        }
        return buffer.toString().trim();
    }

    boolean isLinkQuote(char c) {
        return c == '"' || c == '\'' || c == '(';
    }

    boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    boolean isParagraphStart() {
        return paragraphStartMarker == lineMarker;
    }

    int getBufferEnd() {
        int l = buffer.length();
        while(l > 0 && buffer.charAt(l - 1) == '\n') {
            l -= 1;
        }
        return l;
    }

    class ElementStack extends Stack {
        private static final long serialVersionUID = 8514510754511119691L;

        private Element search(Class clazz) {
            for (int i = size() - 1; i >= 0; i--) {
                Element elem = (Element) get(i);
                if (clazz.isInstance(elem)) {
                    return elem;
                }
            }
            return null;
        }

        private int countNestedLists(Element startFromElement) {
            int count = 0;
            for (int i = size() - 1; i >= 0; i--) {
                Element elem = (Element) get(i);
                if (startFromElement != null) {
                    if (startFromElement == elem) {
                        startFromElement = null;
                    }
                    continue;
                }
                if (elem instanceof ListElement) {
                    count += 1;
                } else if (elem instanceof BlockquoteElement) {
                    break;
                }
            }
            return count;
        }

        private Element peekNestedElement() {
            for (int i = size() - 1; i >= 0; i--) {
                Element elem = (Element) get(i);
                if (elem instanceof ListElement || elem instanceof BlockquoteElement) {
                    return elem;
                }
            }
            return null;
        }

        private Element findNestedElement(Class type, int nesting) {
            for (Iterator it = iterator(); it.hasNext();) {
                Element elem = (Element) it.next();
                if (nesting == elem.nesting && type.isInstance(elem)) {
                    return elem;
                }
            }
            return null;
        }

        private void closeElements(Element element) {
            do {
                ((Element) peek()).close();
            } while (pop() != element);
        }

        private void closeElementsExclusive(Element element) {
            while(peek() != element) {
                ((Element) pop()).close();
            }
        }

        private void closeElementsUnlessExists(Class type, int nesting) {
            Element elem = this.findNestedElement(type, nesting);
            if (elem == null) {
                while(stack.size() > 0) {
                    elem = (Element) this.peek();
                    if (elem != null && elem.nesting >= nesting) {
                        ((Element) stack.pop()).close();
                    } else {
                        break;
                    }
                }
            }
        }
    }

    class Element {
        String tag;
        int nesting, mod;

        void open() {
            openTag(tag, buffer);
        }

        void close() {
            buffer.insert(getBufferEnd(), "</" + tag + ">"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    class BaseElement extends Element {
        @Override
        void open() {}
        @Override
        void close() {}
    }

    class BlockquoteElement extends Element {
        BlockquoteElement(int nesting) {
            tag = "blockquote"; //$NON-NLS-1$
            this.nesting = nesting;
        }
    }

    class CodeElement extends Element {
        CodeElement(int nesting) {
            this.nesting = nesting;
        }

        @Override
        void open() {
            openTag("pre", buffer); //$NON-NLS-1$
            openTag("code", buffer); //$NON-NLS-1$
        }

        @Override
        void close() {
            buffer.insert(codeEndMarker, "</code></pre>"); //$NON-NLS-1$
        }
    }

    class HeaderElement extends Element {
        HeaderElement(int nesting) {
            this.nesting = nesting;
            this.tag = "h" + nesting; //$NON-NLS-1$
        }
    }

    class ListElement extends Element {
        ListElement(String tag, int nesting) {
            this.tag = tag;
            this.nesting = nesting;
        }

        @Override
        void close() {
            buffer.insert(getBufferEnd(), "</li></" + tag + ">");        }  //$NON-NLS-1$//$NON-NLS-2$
    }

    class Emphasis extends Element {
        int end;
        Emphasis(int mod, int end) {
            this.mod = mod;
            this.end = end;
            this.tag = mod == 1 ? "em" : "strong";  //$NON-NLS-1$//$NON-NLS-2$
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println(Messages.getString("MarkdownProcessor.0")); //$NON-NLS-1$
            return;
        }
        MarkdownProcessor processor = new MarkdownProcessor(new File(args[0]));
        System.out.println(processor.process());
    }


}

