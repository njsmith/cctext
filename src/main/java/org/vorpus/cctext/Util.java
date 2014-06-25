package org.vorpus.cctext;

import com.ibm.icu.text.BreakIterator;

public class Util
{
    public static int codePointCount(String s)
    {
        return s.codePointCount(0, s.length());
    }

    public static int wordCount(String s)
    {
        BreakIterator wordIterator = BreakIterator.getWordInstance();
        int count = 0;
        wordIterator.setText(s);
        int offset = 0;
        while (offset != BreakIterator.DONE) {
            if (wordIterator.getRuleStatus() != BreakIterator.WORD_NONE) {
                count++;
            }
            offset = wordIterator.next();
        }
        return count;
    }
}
