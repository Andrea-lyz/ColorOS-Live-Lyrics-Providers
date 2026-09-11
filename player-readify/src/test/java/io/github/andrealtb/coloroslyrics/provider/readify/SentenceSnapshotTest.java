package io.github.andrealtb.coloroslyrics.provider.readify;

public final class SentenceSnapshotTest {
    public static void main(String[] args) {
        check("中文 不加 空格", SentenceSnapshot.clean("中文\n不加\t空格"));
        check("你好世界", SentenceSnapshot.clean("你好世界"));
        check("第一句 第二句", SentenceSnapshot.clean("  第一句\r\n\u00a0第二句  "));
        check("", SentenceSnapshot.clean(null));
        check("", SentenceSnapshot.clean("\n\u0000 \t"));
        check("［00:01］〈00:02〉", SentenceSnapshot.clean("[00:01]<00:02>"));
        check("[00:00.000]当前句子\n", SentenceSnapshot.lrc("当前句子"));
        check("", SentenceSnapshot.lrc(""));
        String longText = new String(new char[2000]).replace("\0", "😀");
        String bounded = SentenceSnapshot.clean(longText);
        if (bounded.codePointCount(0, bounded.length()) != 1600 || bounded.endsWith("\ud83d"))
            throw new AssertionError("Unicode truncation");
        System.out.println("9 sentence snapshot checks passed");
    }
    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
