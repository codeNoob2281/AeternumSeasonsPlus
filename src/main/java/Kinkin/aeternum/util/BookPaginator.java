package Kinkin.aeternum.util;

import java.util.ArrayList;
import java.util.List;

public class BookPaginator {
    private static final int MAX_LINES_PER_PAGE = 14;
    private static final int MAX_VISIBLE_CHARS_PER_LINE = 20;
    private final List<String> pages = new ArrayList();
    private final List<String> currentLines = new ArrayList();
    private StringBuilder currentLine = new StringBuilder();
    private int currentVisibleLen = 0;

    public BookPaginator() {
        super();
    }

    public void addText(String text) {
        if (text != null) {
            String[] paragraphs = text.split("\n", -1);

            for(int i = 0; i < paragraphs.length; ++i) {
                this.addParagraph(paragraphs[i]);
                if (i < paragraphs.length - 1) {
                    this.newLine();
                }
            }

        }
    }

    public void addLine(String line) {
        this.addParagraph(line);
        this.newLine();
    }

    public void addBlankLine() {
        this.newLine();
    }

    public void newPage() {
        this.closeLine();
        this.closePage();
    }

    public List<String> build() {
        this.closeLine();
        this.closePage();
        return new ArrayList(this.pages);
    }

    private void addParagraph(String text) {
        if (text != null && !text.isEmpty()) {
            String[] words = text.split(" ");

            for(String word : words) {
                int wordLen = this.visibleLength(word);
                int extra = this.currentVisibleLen == 0 ? 0 : 1;
                if (this.currentVisibleLen + extra + wordLen > 20) {
                    this.newLine();
                }

                if (this.currentLine.length() > 0) {
                    this.currentLine.append(" ");
                    ++this.currentVisibleLen;
                }

                this.currentLine.append(word);
                this.currentVisibleLen += wordLen;
            }

        }
    }

    private void newLine() {
        this.closeLine();
        if (this.currentLines.size() >= 14) {
            this.closePage();
        }

    }

    private void closeLine() {
        if (this.currentLine.length() > 0) {
            this.currentLines.add(this.currentLine.toString());
            this.currentLine = new StringBuilder();
            this.currentVisibleLen = 0;
            if (this.currentLines.size() >= 14) {
                this.closePage();
            }
        }

    }

    private void closePage() {
        if (!this.currentLines.isEmpty()) {
            this.pages.add(String.join("\n", this.currentLines));
            this.currentLines.clear();
        }

    }

    private int visibleLength(String s) {
        int len = 0;
        boolean skipNext = false;

        for(int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (skipNext) {
                skipNext = false;
            } else if (c == 167 && i + 1 < s.length()) {
                skipNext = true;
            } else {
                ++len;
            }
        }

        return len;
    }
}
