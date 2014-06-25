// NOTE NOTE NOTE:
// Any time you modify this header file, you need to regenerate
//   lib/CLD2wrap.jar
// To do this, go to the project root and run:
//   java -jar jnaerator-0.11-shaded.jar -library CLD2wrap -mode Jar -o lib -package org.vorpus.cld2wrap -f src/CLD2wrap/headers/cld2wrap.h
// (Or replace with your favoriate jnaerator version.)

#ifndef CLD2WRAP_H
#define CLD2WRAP_H

// This is just a super-thin wrapper around CLD2 to make it easier to wrap
// from Java's horribly painful FFI libraries.
struct CLD2WrapResultChunk
{
  int offset;
  int length;
  const char *language_code;
};

class CLD2Wrap
{
public:
  void * result_chunk_vector;
  const char *language_code0;
  const char *language_code1;
  const char *language_code2;
  int percents[3];
  double normalized_scores[3];
  bool is_reliable;
  int text_bytes;

  CLD2Wrap();
  ~CLD2Wrap();
};

// Why do these have to be extern "C", instead of regular functions or
// methods? I have no idea, but when I tried it the other way Bridj couldn't
// find the symbols. I guess Bridj's (de)mangling code just doesn't work?
extern "C"
{
  void CLD2Wrap_detect(CLD2Wrap *this_,
                       const char * bytes, int numBytes,
                       bool is_plain_text,
                       const char * content_language_hint,
                       const char * tld_hint);

  int CLD2Wrap_num_result_chunks(const CLD2Wrap *this_);

  void CLD2Wrap_get_result_chunk(const CLD2Wrap *this_,
                                 int i,
                                 CLD2WrapResultChunk *result_chunk);
};

#endif
