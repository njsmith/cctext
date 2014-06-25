package org.vorpus.cctext;

import java.util.Set;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;

import org.bridj.Pointer;
import static org.bridj.Pointer.*;
import org.vorpus.cld2wrap.CLD2Wrap;
import static org.vorpus.cld2wrap.CLD2wrapLibrary.*;
import org.vorpus.cld2wrap.CLD2WrapResultChunk;

public class LanguageDetection
{
    public static Result detect(String s,
                                Set<String> contentLanguageHints,
                                String tldHint)
    {
        // pointerToBytes blows up with NullPointerException if passed an
        // empty array. why? it's a mystery.
        assert !s.isEmpty();

        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);

        CLD2Wrap detector = new CLD2Wrap();
        Pointer<CLD2Wrap> pdetector = pointerTo(detector);

        // silently passes through null
        String contentLanguageString =
            StringUtils.join(contentLanguageHints, ",");

        //System.out.format("hints: %s / %s\n", contentLanguageString, tldHint);

        CLD2Wrap_detect(pdetector,
                        pointerToBytes(utf8),
                        utf8.length,
                        true,
                        // pointerToCString silently passes through nulls
                        pointerToCString(contentLanguageString),
                        pointerToCString(tldHint));

        Result result = new Result();
        result.languages =
            new String[] { detector.language_code0().getCString(),
                           detector.language_code1().getCString(),
                           detector.language_code2().getCString()
            };
        result.percents = detector.percents().getInts(3);
        result.normalizedScores = detector.normalized_scores().getDoubles(3);
        result.isReliable = detector.is_reliable();

        result._utf8 = utf8;
        result._pdetector = pdetector;

        return result;
    }

    static public class Result
    {
        public String[] languages;
        public int[] percents;
        public double[] normalizedScores;
        public boolean isReliable;

        public byte[] _utf8;
        public Pointer<CLD2Wrap> _pdetector;

        public int numChunks()
        {
            return CLD2Wrap_num_result_chunks(_pdetector);
        }

        public ResultChunk getChunk(int i)
        {
            CLD2WrapResultChunk cChunk = new CLD2WrapResultChunk();
            Pointer<CLD2WrapResultChunk> pcChunk = pointerTo(cChunk);
            CLD2Wrap_get_result_chunk(_pdetector, i, pcChunk);

            ResultChunk jChunk = new ResultChunk();
            jChunk.language = cChunk.language_code().getCString();
            int start = cChunk.offset();
            jChunk.spanLengthUTF8 = cChunk.length();
            int end = start + jChunk.spanLengthUTF8;
            byte[] utf8Span = Arrays.copyOfRange(_utf8, start, end);
            jChunk.contentSpan = new String(utf8Span, StandardCharsets.UTF_8);
            return jChunk;
        }
    }

    static public class ResultChunk
    {
        String language, contentSpan;
        // for calculating "total storage size" statistics.
        int spanLengthUTF8;
    }
}
