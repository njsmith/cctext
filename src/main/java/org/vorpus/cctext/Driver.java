package org.vorpus.cctext;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;

import java.io.File;
import java.io.Writer;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;

import org.jwat.common.HttpHeader;
import org.jwat.common.Payload;
import org.jwat.common.ContentType;
import org.jwat.common.Uri;
import org.jwat.common.HeaderLine;
import org.jwat.warc.WarcReaderFactory;
import org.jwat.warc.WarcReader;
import org.jwat.warc.WarcRecord;
import org.jwat.arc.ArcReaderFactory;
import org.jwat.arc.ArcReader;
import org.jwat.arc.ArcRecordBase;
import org.jwat.arc.ArcRecord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.google.common.collect.ImmutableMap;

import org.apache.commons.lang3.exception.ExceptionUtils;

// debugging
import java.util.Arrays;

public class Driver
{
    static final int BUFFER_SIZE = 65536;
    // how often to dump stats to disk
    static final int STAT_FLUSH_TIME_MS = 60 * 1000;

    private void XX() {}

    long lastFlushTime = 0;

    Map stats = new HashMap();
    Gson statsGson = new Gson();

    Gson urlInfoGson = new Gson();
    Gson exceptionLogGson = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    File statsFile;
    Writer exceptionLog, urlInfoLog, extractedText;

    // XX dedupe memory size?
    public Driver(String statsPath,
                  Writer exceptionLog,
                  Writer urlInfoLog,
                  Writer extractedText)
    {
        statsFile = new File(statsPath);
        this.exceptionLog = exceptionLog;
        this.urlInfoLog = urlInfoLog;
        this.extractedText = extractedText;
    }

    public void processAll(String urlOrPath, InputStream input)
        throws IOException
    {
        if (urlOrPath.endsWith(".warc.gz")
            || urlOrPath.endsWith(".warc")) {
            _processAllWarc(urlOrPath, input);
        } else if (urlOrPath.endsWith(".arc.gz")
                   || urlOrPath.endsWith(".arc")) {
            _processAllArc(urlOrPath, input);
        } else {
            throw new IllegalArgumentException(("urlOrPath must end in .warc "
                                                + "or .arc (+/- .gz); got %s")
                                               .format(urlOrPath));
        }
        flush();
    }

    public void _processAllArc(String urlOrPath, InputStream input)
        throws IOException
    {
        ArcReader reader = ArcReaderFactory.getReader(input, BUFFER_SIZE);
        while (true) {
            ArcRecordBase recordBase = reader.getNextRecord();
            if (recordBase == null) {
                break;
            }
            if (recordBase instanceof ArcRecord) {
                ArcRecord record = (ArcRecord) recordBase;
                processOne(urlOrPath,
                           record.getStartOffset(),
                           record.getUrl(),
                           record.getHttpHeader(),
                           record.getPayload());
            }
        }
    }

    public void _processAllWarc(String urlOrPath, InputStream input)
        throws IOException
    {
        WarcReader reader = WarcReaderFactory.getReader(input, BUFFER_SIZE);
        while (true) {
            WarcRecord record = reader.getNextRecord();
            if (record == null) {
                break;
            }
            if (record.header.warcTypeStr.equals("response")) {
                processOne(urlOrPath,
                           record.getStartOffset(),
                           record.header.warcTargetUriUri,
                           record.getHttpHeader(),
                           record.getPayload());
            }
        }
    }

    public String getTLD(Uri uri)
    {
        if (uri == null) {
            return null;
        } else {
            String[] domainNameParts = uri.getHost().split("\\.");
            return domainNameParts[domainNameParts.length - 1];
        }
    }

    public void processOne(String sourceUrlOrPath,
                           long offset,
                           Uri uri,
                           HttpHeader header,
                           Payload payload)
        throws IOException
    {
        //System.out.println(uri);
        try {
            bumpStat("total-response-count");
            bumpStat("total-response-bytes", payload.getTotalLength());
            bumpStat("total-response-bytes-content", payload.getRemaining());

            InputStream contentStream = payload.getInputStream();

            ContentType ct = ContentType.parseContentType(header.contentType);
            if (ct == null) {
                bumpStat("mime-type", null);
                return;
            }
            String mimeType = String.format("%s/%s",
                                            ct.contentType, ct.mediaType);
            String headerCharset = null;
            if (ct.parameters != null) {
                headerCharset = ct.parameters.get("charset");
            }

            bumpStat("mime-type", mimeType);
            bumpStat("header-charset", headerCharset);

            HTMLToText.Result extracted = null;
            if (mimeType.equals("text/html")) {
                bumpStat("total-html-bytes", payload.getTotalLength());
                bumpStat("total-html-bytes-content", payload.getRemaining());
                extracted = HTMLToText.parse(contentStream,
                                             false, headerCharset);
            } else if (mimeType.equals("application/xhtml+xml")) {
                bumpStat("total-html-bytes", payload.getTotalLength());
                bumpStat("total-html-bytes-content", payload.getRemaining());
                extracted = HTMLToText.parse(contentStream,
                                             true, headerCharset);
            } else {
                return;
            }

            if (extracted.content.isEmpty()) {
                // LanguageDetection.detect requires a non-empty string for
                // stupid FFI-related reasons, and in any case there's no
                // reason to continue.
                bumpStat("no-text-count");
                return;
            }

            String contentLanguage = null;
            HeaderLine contentLanguageLine =
                header.getHeader("Content-Language");
            if (contentLanguageLine != null) {
                contentLanguage = contentLanguageLine.value;
            }
            bumpStat("header-language", contentLanguage);
            String tld = getTLD(uri);
            bumpStat("tld", tld);

            Set<String> languageHints = extracted.languageHints;
            if (contentLanguage != null) {
                languageHints.add(contentLanguage);
            }
            LanguageDetection.Result langInfo =
                LanguageDetection.detect(extracted.content,
                                         languageHints,
                                         tld);

            logUriInfo(uri, languageHints, langInfo);

            // iterate through chunks, reconstructing into lines-with-tags.
            // count bytes, characters and words in each block for stats
            // subdivided by language

            XX();
        } catch (Throwable e) {
            try {
                logException(sourceUrlOrPath, offset, uri, e);
            } catch (Throwable e2) {
                System.err.println("Oh noes! Double-fault in exception "
                                   + "logging path.");
                e2.printStackTrace(System.err);
                System.err.println("Was trying to log:");
                e.printStackTrace(System.err);
                System.err.println("");
            }
        }

        maybeFlush();
    }

    public void logException(String sourceUrlOrPath, long offset,
                             Uri uri, Throwable e)
        throws IOException
    {
        Throwable rootCause = ExceptionUtils.getRootCause(e);
        if (rootCause == null) {
            // WTF ExceptionUtils
            rootCause = e;
        }
        StackTraceElement[] stack = rootCause.getStackTrace();

        String tag = String.format("At %s:%s in %s.%s: %s",
                                   stack[0].getFileName(),
                                   stack[0].getLineNumber(),
                                   stack[0].getClassName(),
                                   stack[0].getMethodName(),
                                   ExceptionUtils.getMessage(rootCause));
        bumpStat("exceptions", tag);

        String uriString = (uri == null) ? "(null)" : uri.toString();

        Map stats = ImmutableMap.of
            ("archive", sourceUrlOrPath,
             "offset", offset,
             "uri", uriString,
             // ExceptionUtils.getMessage automatically includes class name
             "exception", ExceptionUtils.getMessage(rootCause),
             "traceback", ExceptionUtils.getStackTrace(e));
        exceptionLog.write(exceptionLogGson.toJson(stats));
        exceptionLog.write("\n\n");
        exceptionLog.flush();
    }

    public void logUriInfo(Uri uri, Set<String> languageHints,
                           LanguageDetection.Result langInfo)
        throws IOException
    {
        if (uri == null) {
            return;
        }

        Map stats = ImmutableMap.of
            ("url", uri.toString(),
             "declared-languages", languageHints,
             "cld2-document-stats",
             ImmutableMap.of("is-reliable", langInfo.isReliable,
                             "languages", langInfo.languages,
                             "percents", langInfo.percents,
                             "normalized-scores", langInfo.normalizedScores),
             "cld2-span-stats", "XX");
        urlInfoLog.write(urlInfoGson.toJson(stats));
        urlInfoLog.write("\n");
        // add detected language stats
        XX();
    }

    public void bumpStat(String stat)
    {
        bumpStat(stat, 1);
    }

    public void _bumpStatImpl(Map statmap, String stat, long amount)
    {
        assert stat != null;

        Long current = (Long) statmap.get(stat);
        if (current == null) {
            current = new Long(0);
        }
        statmap.put(stat, current + amount);
    }

    public void bumpStat(String stat, long amount)
    {
        _bumpStatImpl(stats, stat, amount);
    }

    public void bumpStat(String stat, String value)
    {
        bumpStat(stat, value, 1);
    }

    public void bumpStat(String stat, String value, long amount)
    {
        assert stat != null;
        if (value == null) {
            value = "__null";
        }
        Map substats = (Map) stats.get(stat);
        if (substats == null) {
            substats = new HashMap();
            stats.put(stat, substats);
        }
        _bumpStatImpl(substats, value, amount);
    }

    public void maybeFlush()
        throws IOException
    {
        long now = System.currentTimeMillis();
        if (now - lastStatDumpTime >= STAT_FLUSH_TIME_MS) {
            flush();
        }
    }

    public void flush()
        throws IOException
    {
        long now = System.currentTimeMillis();
        lastStatDumpTime = now;
        // false -> truncate, rather than append
        FileWriter statsWriter = new FileWriter(statsFile, false);
        statsWriter.write(statsGson.toJson(stats));
        statsWriter.close();

        urlInfoLog.flush();
    }

    protected void finalize() throws Throwable
    {
        flush();
        super.finalize();
    }
}
