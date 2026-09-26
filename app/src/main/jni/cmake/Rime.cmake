# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

string(ASCII 10 LUA_NL)
set(LUA_LIOLIB_SRC "${CMAKE_SOURCE_DIR}/librime-lua-deps/lua5.4/liolib.c")
if(EXISTS "${LUA_LIOLIB_SRC}")
  file(READ "${LUA_LIOLIB_SRC}" LUA_LIOLIB_CONTENT)
  string(FIND "${LUA_LIOLIB_CONTENT}" "ANDROID" LUA_ALREADY_PATCHED)
  if(LUA_ALREADY_PATCHED EQUAL -1)
    string(FIND "${LUA_LIOLIB_CONTENT}" "#if !defined(l_fseek)" LUA_ANCHOR_POS)
    if(LUA_ANCHOR_POS GREATER -1)
      string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_ANCHOR_POS} -1 LUA_SUB_CONTENT)
      string(FIND "${LUA_SUB_CONTENT}" "#if defined(LUA_USE_POSIX)" LUA_REL_POS)
      if(LUA_REL_POS GREATER -1)
        math(EXPR LUA_TARGET_POS "${LUA_ANCHOR_POS} + ${LUA_REL_POS}")
        string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_TARGET_POS} -1 LUA_TARGET_CONTENT)
        string(FIND "${LUA_TARGET_CONTENT}" "${LUA_NL}" LUA_REL_NL_POS)
        if(LUA_REL_NL_POS GREATER -1)
          math(EXPR LUA_NL_POS "${LUA_TARGET_POS} + ${LUA_REL_NL_POS}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" 0 ${LUA_TARGET_POS} LUA_HEAD)
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_NL_POS} -1 LUA_TAIL)
          math(EXPR LUA_SUFFIX_START "${LUA_TARGET_POS} + 26")
          math(EXPR LUA_SUFFIX_LEN "${LUA_NL_POS} - ${LUA_SUFFIX_START}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_SUFFIX_START} ${LUA_SUFFIX_LEN} LUA_SUFFIX)
          set(LUA_PATCHED_LINE
            "#if defined(LUA_USE_POSIX) && \\${LUA_NL}   (!defined(ANDROID) || (defined(__LP64__) || ANDROID_PLATFORM >= 24))${LUA_SUFFIX}")
          set(LUA_LIOLIB_CONTENT "${LUA_HEAD}${LUA_PATCHED_LINE}${LUA_TAIL}")
          file(WRITE "${LUA_LIOLIB_SRC}" "${LUA_LIOLIB_CONTENT}")
        endif()
      endif()
    endif()
  endif()
endif()

set(RIME_PLUGINS librime-octagram librime-predict librime-t9)

foreach(plugin ${RIME_PLUGINS})
  file(COPY "${CMAKE_SOURCE_DIR}/${plugin}/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
endforeach()

file(COPY "${CMAKE_SOURCE_DIR}/librime-lua/"
     DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua")

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
  file(COPY "${CMAKE_SOURCE_DIR}/librime-lua-deps/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
include("${CMAKE_CURRENT_LIST_DIR}/JapanesePrefixCompletion.cmake")
add_subdirectory(librime)
include("${CMAKE_CURRENT_LIST_DIR}/T9SingleKeyRecall.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/T9PhraseBeforeSentence.cmake")
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
