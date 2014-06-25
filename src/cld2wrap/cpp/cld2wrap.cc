#include "cld2wrap.h"

// compact_lang_det assumes that stdio.h has already been included
#include <stdio.h>
#include "compact_lang_det.h"
#include "encodings.h"

/*
  typedef struct {
  const char* content_language_hint;      // "mi,en" boosts Maori and English
  const char* tld_hint;                   // "id" boosts Indonesian
  int encoding_hint;                      // SJS boosts Japanese
  Language language_hint;                 // ITALIAN boosts it
  } CLDHints;

  static const int kMaxResultChunkBytes = 65535;

  // For returning a vector of per-language pieces of the input buffer
  // Unreliable and too-short are mapped to UNKNOWN_LANGUAGE
  typedef struct {
  int offset;                 // Starting byte offset in original buffer
  uint16 bytes;               // Number of bytes in chunk
  uint16 lang1;               // Top lang, as full Language. Apply
  // static_cast<Language>() to this short value.
  } ResultChunk;
  typedef std::vector<ResultChunk> ResultChunkVector;
*/

static inline CLD2::ResultChunkVector* rcv(CLD2Wrap *this_)
{
  return static_cast<CLD2::ResultChunkVector *>(this_->result_chunk_vector);
}

CLD2Wrap::CLD2Wrap()
{
  result_chunk_vector = new CLD2::ResultChunkVector();
}

CLD2Wrap::~CLD2Wrap()
{
  delete rcv(this);
}

void
CLD2Wrap_detect(CLD2Wrap *this_, const char * bytes, int numBytes,
                bool is_plain_text,
                const char * content_language_hint,
                const char * tld_hint)
{
  CLD2::CLDHints cldHints;

  cldHints.content_language_hint = content_language_hint;
  cldHints.tld_hint = tld_hint;
  cldHints.encoding_hint = CLD2::UNKNOWN_ENCODING;
  cldHints.language_hint = CLD2::UNKNOWN_LANGUAGE;

  CLD2::Language languages[3];

  CLD2::ExtDetectLanguageSummary(bytes, numBytes, is_plain_text,
                                 &cldHints,
                                 0, // flags
                                 languages,
                                 this_->percents,
                                 this_->normalized_scores,
                                 rcv(this_),
                                 &this_->text_bytes,
                                 &this_->is_reliable);

  this_->language_code0 = CLD2::LanguageCode(languages[0]);
  this_->language_code1 = CLD2::LanguageCode(languages[1]);
  this_->language_code2 = CLD2::LanguageCode(languages[2]);
}

int
CLD2Wrap_num_result_chunks(const CLD2Wrap *this_)
{
  return rcv(this_)->size();
}

void
CLD2Wrap_get_result_chunk(const CLD2Wrap *this_,
                          int i, CLD2WrapResultChunk *result_chunk)
{
  CLD2::ResultChunk &real_chunk = rcv(this_)->at(i);
  result_chunk->offset = real_chunk.offset;
  result_chunk->length = real_chunk.bytes;
  result_chunk->language_code = CLD2::LanguageCode(
      static_cast<CLD2::Language>(real_chunk.lang1));
}
