package com.fourtwo.hookintent.data;

import java.util.regex.Pattern;

/** Bound backtracking so a problematic expression cannot occupy the capture worker indefinitely. */
final class SearchMatch implements CharSequence {
    static final class TooSlow extends RuntimeException {
        TooSlow() { super("正则搜索耗时过长，请简化表达式"); }
    }
    private final String text;
    private final long deadline;
    private int reads;
    private SearchMatch(String text, long deadline) { this.text = text; this.deadline = deadline; }
    static boolean find(Pattern pattern, String text, long deadline) {
        if (System.nanoTime() > deadline) throw new TooSlow();
        try { return pattern.matcher(new SearchMatch(text, deadline)).find(); }
        catch (StackOverflowError error) { throw new TooSlow(); }
    }
    @Override public int length() { return text.length(); }
    @Override public char charAt(int index) {
        if ((++reads & 1023) == 0 && System.nanoTime() > deadline) throw new TooSlow();
        return text.charAt(index);
    }
    @Override public CharSequence subSequence(int start, int end) { return new SearchMatch(text.substring(start, end), deadline); }
    @Override public String toString() { return text; }
}
