package kinkin.aeternum.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 书籍分页工具。
 *
 * <p>按 Minecraft 成书（Written Book）的单页限制（每页最多 14 行、每行最多 20 个可见字符）
 * 把长文本切分成多页。文本支持 {@code \n} 分段与 {@code §} 颜色代码；颜色代码不计入可见宽度。</p>
 *
 * <p>典型用法：连续调用 {@link #addText}/{@link #addLine}/{@link #addBlankLine}，
 * 最后调用 {@link #build()} 取回分页后的页面字符串列表。</p>
 */
public class BookPaginator {
    /**
     * 单页最大行数。
     */
    private static final int MAX_LINES_PER_PAGE = 14;
    /**
     * 单行最大可见字符数（不含颜色代码）。
     */
    private static final int MAX_VISIBLE_CHARS_PER_LINE = 20;
    /**
     * 已完成的页面。
     */
    private final List<String> pages = new ArrayList();
    /**
     * 当前页已累积的行。
     */
    private final List<String> currentLines = new ArrayList();
    /**
     * 当前正在拼接的行。
     */
    private StringBuilder currentLine = new StringBuilder();
    /**
     * 当前行已占用的可见字符数。
     */
    private int currentVisibleLen = 0;

    public BookPaginator() {
        super();
    }

    /**
     * 追加一段可能包含换行的文本，按换行拆成多个段落。
     */
    public void addText(String text) {
        if (text != null) {
            String[] paragraphs = text.split("\n", -1);

            for (int i = 0; i < paragraphs.length; ++i) {
                this.addParagraph(paragraphs[i]);
                if (i < paragraphs.length - 1) {
                    this.newLine();
                }
            }

        }
    }

    /**
     * 追加一整行（自动换行后结束该行）。
     */
    public void addLine(String line) {
        this.addParagraph(line);
        this.newLine();
    }

    /**
     * 追加一个空行。
     */
    public void addBlankLine() {
        this.newLine();
    }

    /**
     * 结束当前页，后续内容另起一页。
     */
    public void newPage() {
        this.closeLine();
        this.closePage();
    }

    /**
     * 收尾并返回全部分页结果（返回副本，不影响内部状态）。
     */
    public List<String> build() {
        this.closeLine();
        this.closePage();
        return new ArrayList(this.pages);
    }

    /**
     * 把一个段落按空格拆词后逐词拼行，超过 {@link #MAX_VISIBLE_CHARS_PER_LINE} 时自动换行。
     */
    private void addParagraph(String text) {
        if (text != null && !text.isEmpty()) {
            String[] words = text.split(" ");

            for (String word : words) {
                int wordLen = this.visibleLength(word);
                int extra = this.currentVisibleLen == 0 ? 0 : 1;
                if (this.currentVisibleLen + extra + wordLen > 20) {
                    this.newLine();
                }

                if (!this.currentLine.isEmpty()) {
                    this.currentLine.append(" ");
                    ++this.currentVisibleLen;
                }

                this.currentLine.append(word);
                this.currentVisibleLen += wordLen;
            }

        }
    }

    /**
     * 结束当前行；行数达到 {@link #MAX_LINES_PER_PAGE} 时自动换页。
     */
    private void newLine() {
        this.closeLine();
        if (this.currentLines.size() >= 14) {
            this.closePage();
        }

    }

    /**
     * 将当前行写入当前页（若非空），并重置行缓冲。
     */
    private void closeLine() {
        if (!this.currentLine.isEmpty()) {
            this.currentLines.add(this.currentLine.toString());
            this.currentLine = new StringBuilder();
            this.currentVisibleLen = 0;
            if (this.currentLines.size() >= 14) {
                this.closePage();
            }
        }

    }

    /**
     * 将当前页写入结果列表（若非空），并清空行缓冲。
     */
    private void closePage() {
        if (!this.currentLines.isEmpty()) {
            this.pages.add(String.join("\n", this.currentLines));
            this.currentLines.clear();
        }

    }

    /**
     * 计算字符串的可见长度，跳过 {@code §} 颜色代码（颜色代码占两个字符）。
     */
    private int visibleLength(String text) {
        int len = 0;
        boolean skipNext = false;

        for (int index = 0; index < text.length(); ++index) {
            char ch = text.charAt(index);
            if (skipNext) {
                skipNext = false;
            } else if (ch == 167 && index + 1 < text.length()) {
                skipNext = true;
            } else {
                ++len;
            }
        }

        return len;
    }
}
