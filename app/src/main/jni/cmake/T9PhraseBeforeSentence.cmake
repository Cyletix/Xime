# Pure-number T9 ranking patch.
# Full-cover user/system phrases must not be unconditionally placed behind
# generated sentence candidates. Non-T9 behavior is unchanged.

set(T9_SCRIPT_TRANSLATOR_SOURCE
    "${CMAKE_SOURCE_DIR}/librime/src/rime/gear/script_translator.cc")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
             "${T9_SCRIPT_TRANSLATOR_SOURCE}")
file(READ "${T9_SCRIPT_TRANSLATOR_SOURCE}" T9_SCRIPT_TRANSLATOR_CODE)

set(T9_SENTENCE_RANK_OLD [=[
bool ScriptTranslation::PrepareCandidate() {
iter_incremented:
  if (exhausted()) {
    candidate_source_ = kUninitialized;
    candidate_ = nullptr;
    return false;
  }
  if (!sentences_.empty()) {
    candidate_source_ = kSentence;
    candidate_ = sentences_[0];
    return true;
  }
  const size_t full_code_length = end_of_input_ - start_;
]=])

set(T9_SENTENCE_RANK_NEW [=[
bool ScriptTranslation::PrepareCandidate() {
iter_incremented:
  if (exhausted()) {
    candidate_source_ = kUninitialized;
    candidate_ = nullptr;
    return false;
  }

  const size_t full_code_length = end_of_input_ - start_;

  const std::string& active_input = syllabifier_->input();
  const bool t9_numeric_input =
      !active_input.empty() &&
      std::all_of(active_input.begin(), active_input.end(),
                  [](char c) { return c >= '2' && c <= '9'; });

  const bool has_full_user_phrase =
      user_phrase_ && user_phrase_iter_ != user_phrase_->rend() &&
      user_phrase_iter_->first == full_code_length &&
      !user_phrase_iter_->second.exhausted();

  const bool has_full_sys_phrase =
      phrase_ && phrase_iter_ != phrase_->rend() &&
      phrase_iter_->first == full_code_length &&
      !phrase_iter_->second.exhausted();

  if (!sentences_.empty() &&
      (!t9_numeric_input ||
       (!has_full_user_phrase && !has_full_sys_phrase))) {
    candidate_source_ = kSentence;
    candidate_ = sentences_[0];
    return true;
  }
]=])

string(FIND "${T9_SCRIPT_TRANSLATOR_CODE}"
       "${T9_SENTENCE_RANK_OLD}"
       T9_SENTENCE_RANK_POS)
if(T9_SENTENCE_RANK_POS EQUAL -1)
  message(FATAL_ERROR
    "librime script_translator changed: review T9 phrase-before-sentence patch")
endif()

string(REPLACE "${T9_SENTENCE_RANK_OLD}"
               "${T9_SENTENCE_RANK_NEW}"
               T9_SCRIPT_TRANSLATOR_CODE
               "${T9_SCRIPT_TRANSLATOR_CODE}")

set(T9_SCRIPT_TRANSLATOR_COPY
    "${CMAKE_CURRENT_BINARY_DIR}/cyime-script-translator.cc")
file(CONFIGURE
     OUTPUT "${T9_SCRIPT_TRANSLATOR_COPY}"
     CONTENT "${T9_SCRIPT_TRANSLATOR_CODE}"
     @ONLY)

get_target_property(T9_RIME_SOURCES rime-static SOURCES)
set(T9_SCRIPT_REPLACED FALSE)
foreach(T9_SOURCE IN LISTS T9_RIME_SOURCES)
  if(T9_SOURCE MATCHES "(^|/)rime/gear/script_translator\\.cc$")
    list(REMOVE_ITEM T9_RIME_SOURCES "${T9_SOURCE}")
    set(T9_SCRIPT_REPLACED TRUE)
  endif()
endforeach()

if(NOT T9_SCRIPT_REPLACED)
  message(FATAL_ERROR "Cannot locate librime script_translator compilation unit")
endif()

list(APPEND T9_RIME_SOURCES "${T9_SCRIPT_TRANSLATOR_COPY}")
set_property(TARGET rime-static PROPERTY SOURCES "${T9_RIME_SOURCES}")
