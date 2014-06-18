import sys

from java.lang import Throwable
from java.io import FileInputStream, BufferedInputStream, InputStreamReader
from java.nio.charset import CodingErrorAction
from org.apache.tika.utils import CharsetUtils
from org.apache.tika.parser.html import HtmlEncodingDetector
from org.apache.tika.parser.txt import (Icu4jEncodingDetector,
                                        UniversalEncodingDetector)
from org.apache.tika.metadata import Metadata
from org.jwat.common import ContentType
from org.jwat.warc import WarcReaderFactory
from de.l3s.boilerpipe.extractors import KeepEverythingWithMinKWordsExtractor


BUFFERSIZE = 65536

# Tiny shim to isolate the rest of the code from the details of jwat's API, to
# make it easier to handle ARC records etc. later if necessary. (Sorry YAGNI.)
class MyWarcRecord(object):
    def __init__(self, record):
        self.record = record
        self._input_stream = None

    def is_response(self):
        return self.record.header.warcTypeStr == u"response"

    def _parsed_header_content_type(self):
        header = self.record.getHttpHeader().contentType
        # header might be None, or it might be an invalid string
        # either way, parseContentType returns None
        ct = ContentType.parseContentType(header)
        if ct is None:
            ct = ContentType.parseContentType("application/octet-stream")
        return ct

    def header_mime_type(self):
        parsed = self._parsed_header_content_type()
        return parsed.contentType + "/" + parsed.mediaType

    def input_stream(self):
        # We need a rewindable/markable input stream to support charset
        # detection, which jwat doesn't supply by default. So we have to wrap
        # in a BufferedInputStream. And we hold on to it, so repeated calls
        # get the same one.
        if self._input_stream is None:
            raw_input_stream = self.record.getPayloadContent()
            self._input_stream = BufferedInputStream(raw_input_stream)
        return self._input_stream

    def header_charset_name(self):
        # Might return None
        parsed = self._parsed_header_content_type()
        parameters = parsed.parameters
        if parameters is None:
            return None
        return parameters.get("charset")

    def header_charset(self):
        name = self.header_charset_name()
        if name is None:
            return None
        if CharsetUtils.isSupported(name):
            return CharsetUtils.forName(name)

def read_warc(input_stream):
    reader = WarcReaderFactory.getReader(input_stream, BUFFERSIZE)
    while True:
        record = reader.getNextRecord()
        if record is None:
            break
        yield MyWarcRecord(record)

def is_html(record):
    mime_type = record.header_mime_type()
    return mime_type == "text/html" or mime_type == "application/xhtml+xml"

# org.commoncrawl.util.CharsetUtils uses the strategy of checking the
# following sources in this order:
#   -- headers
#   -- meta tag
#   -- jchardet
#   -- ICU4j
# For each one, if given, and Charset.forName works, they're done.
#
# For headers and meta tag, they also allow the given charset to be one for
# which Charset.forName fails, but for which they have an entry in their big
# charset alias table.
#
# We basically do the same thing, but with juniversalcharset in place of
# jchardet, and without the giant alias table.
#
# XX Arguably we ought to call icu4j unconditionally and pass it the
# headers/meta tag as a *hint* instead of trusting
# it... UniversalEncodingDetector also has some hinting logic.
EMPTY_METADATA = Metadata()
DETECTORS = [HtmlEncodingDetector(),
             UniversalEncodingDetector(),
             Icu4jEncodingDetector(),
            ]
def guess_charset(record):
    input_stream = record.input_stream()
    #dump_charsets(record, input_stream)
    header_charset = record.header_charset()
    if header_charset is not None:
        return header_charset
    for detector in DETECTORS:
        detected = detector.detect(input_stream, EMPTY_METADATA)
        if detected is not None:
            return detected
    return CharsetUtils.forName("ISO-8859-1")

def dump_charsets(record, input_stream):
    sys.stderr.write("%s: %s, %s, %s, %s\n"
                     % ((record.record.getStartOffset(), record.header_charset())
                        + tuple([d.detect(input_stream, EMPTY_METADATA) for d in DETECTORS])))

def charset_strat_header(record, input_stream):
    return record.header_charset()

def charset_strat_meta(record, input_stream):
    return DETECTORS[0].detect(input_stream, EMPTY_METADATA)

def charset_strat_meta_header(record, input_stream):
    charset = charset_strat_meta(record, input_stream)
    if charset is None:
        charset = charset_strat_header(record, input_stream)
    return charset

def charset_strat_juniversal_plain(record, input_stream):
    return DETECTORS[1].detect(input_stream, EMPTY_METADATA)

def strat_meta_hint(record, input_stream):
    m = Metadata()
    charset = charset_strat_meta_header(record, input_stream)
    if charset is not None:
        m.set(m.CONTENT_ENCODING, charset.name())
    return m

def charset_strat_juniversal_hint(record, input_stream):
    return DETECTORS[1].detect(input_stream, strat_meta_hint(record, input_stream))

def charset_strat_icu4j_plain(record, input_stream):
    return DETECTORS[2].detect(input_stream, EMPTY_METADATA)

def charset_strat_icu4j_hint(record, input_stream):
    return DETECTORS[2].detect(input_stream, strat_meta_hint(record, input_stream))

from java.nio.file import Files
from java.nio.charset import (MalformedInputException,
                              UnmappableCharacterException)
from org.apache.commons.io import IOUtils
from org.apache.commons.io.output import NullWriter
from java.io import ByteArrayInputStream
def try_strats(csv_writer, record):
    input_stream = record.input_stream()
    data = IOUtils.toByteArray(input_stream)
    strats = [charset_strat_header, charset_strat_meta,
              charset_strat_meta_header,
              charset_strat_juniversal_plain, charset_strat_juniversal_hint,
              charset_strat_icu4j_plain, charset_strat_icu4j_hint,
              ]
    charsets = [strat(record, ByteArrayInputStream(data)) for strat in strats]
    charset_success = {None: "--"}
    for charset in charsets:
        if charset is None:
            continue
        if charset not in charset_success:
            decoder = charset.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)
            reader = InputStreamReader(ByteArrayInputStream(data),
                                       decoder)
            try:
                # IOUtils.toString is AFAICT suffering from some weird jython
                # dispatch bug, so we use copy() instead.
                IOUtils.copy(reader, NullWriter())
                charset_success[charset] = "True"
            except MalformedInputException, UnmappableCharacterException:
                charset_success[charset] = "False"
    if True: # "False" in charset_success.values():
        row = ([str(record.record.getStartOffset())]
                + [(c.name() if c else str(c)) for c in charsets]
                + [charset_success[c] for c in charsets])
        csv_writer.writerow(row)

# Empirically, the above function fails about 1% of the time

# HTML rule is:
# - trust BOMs
# - otherwise trust the header if given
# - otherwise trust meta
# - otherwise guess
# extra bonus: 8859-1 and ASCII are treated as windows-1252!

def get_content_reader(record):
    # returns a java Reader (text file handle)
    charset = guess_charset(record)
    # Error out on broken character set usage:
    # https://stackoverflow.com/questions/14702189/how-make-inputstreamreader-fail-on-invalid-data-for-encoding
    decoder = charset.newDecoder()
    decoder.onMalformedInput(CodingErrorAction.REPORT)
    # UnmappableCharacter means a character that the decoder recognizes, but
    # for which there is no unicode equivalent. This does not indicate a
    # failure to understand the file's encoding.
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)
    return InputStreamReader(record.input_stream(), decoder)

import csv
w = csv.writer(sys.stdout)
from org.vorpus.cctext import TextExtractor
def dump_all(input_stream, writer, min_words=1):
    ex = KeepEverythingWithMinKWordsExtractor(min_words)
    for record in read_warc(input_stream):
        try:
            # if record.is_response() and is_html(record):
            #     #try_strats(w, record)
            #     content = get_content_reader(record)
            #     writer.write(ex.getText(content))
            if record.is_response():
                mime_type = record.header_mime_type()
                if mime_type == "application/xhtml+xml":
                    TextExtractor.parse(record.input_stream(), True,
                                        record.header_charset_name())
                elif mime_type == "text/html":
                    TextExtractor.parse(record.input_stream(), False,
                                        record.header_charset_name())
                else:
                    pass
        except Exception as e:
            sys.stderr.write("Oops: %s\n" % (e,))
            #import pdb; pdb.post_mortem()
        except Throwable as e:
            sys.stderr.write("Java oops: %s\n" % (e,))
            #import pdb; pdb.post_mortem()

if __name__ == "__main__":
    import java.lang.System
    dump_all(java.lang.System.in, java.lang.System.out)

# stats:
# - count of responses
# - total bytes of responses
# - count of each mime type
# - total bytes of html types
# - count of charsets and where they were found
# - count of exception messages (and locations?)
# - count of successes
#
# - for selecting MinK, a histogram of how many blocks have each count?
#
# http://www.jython.org/docs/library/traceback.html
# linear log of exceptions
