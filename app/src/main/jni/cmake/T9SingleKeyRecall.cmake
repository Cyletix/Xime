# Keep ambiguous one-key T9 initials (3 = d/e/f) even when e is a full syllable.
# Patch a build-local copy: do not mutate the pinned librime submodule.
set(T9_SYLLABIFIER_SOURCE "${CMAKE_SOURCE_DIR}/librime/src/rime/algo/syllabifier.cc")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${T9_SYLLABIFIER_SOURCE}")
file(READ "${T9_SYLLABIFIER_SOURCE}" T9_SYLLABIFIER_CODE)
set(T9_PRUNE_OLD "(std::max)(graph->vertices[farthest], kFuzzySpelling);")
set(T9_PRUNE_NEW [=[(std::max)(graph->vertices[farthest],
          input.size() == 1 && input[0] >= '2' && input[0] <= '9'
              ? kAbbreviation : kFuzzySpelling);]=])
string(FIND "${T9_SYLLABIFIER_CODE}" "${T9_PRUNE_OLD}" T9_PRUNE_POS)
if(T9_PRUNE_POS EQUAL -1)
  message(FATAL_ERROR "librime syllabifier changed: review T9 single-key recall patch")
endif()
string(REPLACE "${T9_PRUNE_OLD}" "${T9_PRUNE_NEW}" T9_SYLLABIFIER_CODE "${T9_SYLLABIFIER_CODE}")

# Keep a legal final-syllable abbreviation in a numeric T9 input even if
# another interpretation reaches the end using only complete syllables.
# Examples: 583|3 = jue|d(e), 936|84|8 = wen|ti|t(ai).
# Do NOT promote abbreviations to normal spellings or change ranking scores.
# Earlier abbreviated edges and nonnumeric input retain the existing policy.
set(T9_TAIL_GUARD_OLD "if (k->second.type > last_type) {")
string(REGEX MATCHALL "if \\(k->second\\.type > last_type\\) \\{"
       T9_TAIL_GUARD_MATCHES "${T9_SYLLABIFIER_CODE}")
list(LENGTH T9_TAIL_GUARD_MATCHES T9_TAIL_GUARD_COUNT)
if(NOT T9_TAIL_GUARD_COUNT EQUAL 2)
  message(FATAL_ERROR "librime pruning changed: expected full and incremental T9 guards; review patch")
endif()
set(T9_TAIL_GUARD_NEW [=[const bool t9_tail_abbreviation =
            k->second.type == kAbbreviation &&
            j->first == input.size() &&
            !input.empty() &&
            input.find_first_not_of("23456789") == std::string::npos;
        if (k->second.type > last_type && !t9_tail_abbreviation) {]=])
string(REPLACE "${T9_TAIL_GUARD_OLD}" "${T9_TAIL_GUARD_NEW}"
       T9_SYLLABIFIER_CODE "${T9_SYLLABIFIER_CODE}")

string(REPLACE "#include \"syllabifier.h\"" "#include <rime/algo/syllabifier.h>" T9_SYLLABIFIER_CODE "${T9_SYLLABIFIER_CODE}")
set(T9_SYLLABIFIER_COPY "${CMAKE_CURRENT_BINARY_DIR}/cyime-syllabifier.cc")
file(CONFIGURE OUTPUT "${T9_SYLLABIFIER_COPY}" CONTENT "${T9_SYLLABIFIER_CODE}" @ONLY)
get_target_property(T9_RIME_SOURCES rime-static SOURCES)
set(T9_REPLACED FALSE)
foreach(T9_SOURCE IN LISTS T9_RIME_SOURCES)
  if(T9_SOURCE MATCHES "(^|/)rime/algo/syllabifier\\.cc$")
    list(REMOVE_ITEM T9_RIME_SOURCES "${T9_SOURCE}")
    set(T9_REPLACED TRUE)
  endif()
endforeach()
if(NOT T9_REPLACED)
  message(FATAL_ERROR "Cannot locate librime syllabifier compilation unit")
endif()
list(APPEND T9_RIME_SOURCES "${T9_SYLLABIFIER_COPY}")
set_property(TARGET rime-static PROPERTY SOURCES "${T9_RIME_SOURCES}")
