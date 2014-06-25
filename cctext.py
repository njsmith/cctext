import sys

from java.lang import Throwable
from java.io import FileInputStream, BufferedInputStream, InputStreamReader
from org.jwat.common import ContentType
from org.jwat.warc import WarcReaderFactory

BUFFERSIZE = 65536

# Tiny shim to isolate the rest of the code from the details of jwat's API, to
# make it easier to handle ARC records etc. later if necessary. (Sorry YAGNI.)
class MyWarcRecord(object):
    def __init__(self, record):
        self.record = record
        self._input_stream = None

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

    def header_charset(self):
        # Might return None
        parsed = self._parsed_header_content_type()
        parameters = parsed.parameters
        if parameters is None:
            return None
        return parameters.get("charset")

def read_warc_responses(input_stream):
    reader = WarcReaderFactory.getReader(input_stream, BUFFERSIZE)
    while True:
        record = reader.getNextRecord()
        if record is None:
            break
        if record.header.warcTypeStr == u"response":
            yield MyWarcRecord(record)

from org.vorpus.cctext import TextExtractor
def dump_all(input_stream, writer, min_words=1):
    for record in read_warc_responses(input_stream):
        try:
            mime_type = record.header_mime_type()
            if mime_type == "application/xhtml+xml":
                TextExtractor.parse(record.input_stream(), True,
                                    record.header_charset())
            elif mime_type == "text/html":
                TextExtractor.parse(record.input_stream(), False,
                                    record.header_charset())
            else:
                pass
            # if raw_input().strip():
            #     break
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
