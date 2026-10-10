/* Small helpers shared by the native parts of DualFiles. */
#ifndef DUALFILES_JUTIL_H
#define DUALFILES_JUTIL_H

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* UTF-16 (0-terminated) to UTF-8; returns bytes written (without the 0) or -1. */
static inline int utf16_to_utf8(const uint16_t *s, char *out, size_t outSize)
{
  size_t n = 0;
  for (; *s; s++)
  {
    uint32_t c = *s;
    if (c >= 0xD800 && c < 0xDC00 && s[1] >= 0xDC00 && s[1] < 0xE000)
    {
      c = 0x10000 + ((c - 0xD800) << 10) + (s[1] - 0xDC00);
      s++;
    }
    if (n + 5 > outSize)
      return -1;
    if (c < 0x80)
      out[n++] = (char)c;
    else if (c < 0x800)
    {
      out[n++] = (char)(0xC0 | (c >> 6));
      out[n++] = (char)(0x80 | (c & 0x3F));
    }
    else if (c < 0x10000)
    {
      out[n++] = (char)(0xE0 | (c >> 12));
      out[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
      out[n++] = (char)(0x80 | (c & 0x3F));
    }
    else
    {
      out[n++] = (char)(0xF0 | (c >> 18));
      out[n++] = (char)(0x80 | ((c >> 12) & 0x3F));
      out[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
      out[n++] = (char)(0x80 | (c & 0x3F));
    }
  }
  out[n] = 0;
  return (int)n;
}

/* Java string as standard UTF-8 (JNI's own UTF-8 is "modified" and breaks emoji); free() the result. */
static inline char *jstr_utf8(JNIEnv *env, jstring js)
{
  jsize len = (*env)->GetStringLength(env, js);
  const jchar *chars = (*env)->GetStringChars(env, js, NULL);
  uint16_t *z = (uint16_t *)malloc(((size_t)len + 1) * sizeof(uint16_t));
  size_t outSize = (size_t)len * 3 + 8;
  char *out = (char *)malloc(outSize);
  if (z && out && chars)
  {
    memcpy(z, chars, (size_t)len * sizeof(uint16_t));
    z[len] = 0;
    if (utf16_to_utf8(z, out, outSize) < 0)
    {
      free(out);
      out = NULL;
    }
  }
  else
  {
    free(out);
    out = NULL;
  }
  if (chars)
    (*env)->ReleaseStringChars(env, js, chars);
  free(z);
  return out;
}

/* UTF-8 bytes (e.g. a file name from the kernel) as a Java string; invalid bytes become U+FFFD. */
static inline jstring utf8_jstr(JNIEnv *env, const char *s, size_t n)
{
  jchar stack[256];
  jchar *out = n <= 256 ? stack : (jchar *)malloc(n * sizeof(jchar));
  if (!out)
    return NULL;
  size_t k = 0;
  for (size_t i = 0; i < n;)
  {
    unsigned c = (unsigned char)s[i];
    unsigned need = c < 0x80 ? 0 : (c & 0xE0) == 0xC0 ? 1 : (c & 0xF0) == 0xE0 ? 2 : (c & 0xF8) == 0xF0 ? 3 : 9;
    uint32_t cp = need == 0 ? c : need == 1 ? (c & 0x1F) : need == 2 ? (c & 0x0F) : (c & 0x07);
    int bad = need == 9 || i + need >= n + (need ? 0 : 1);
    for (unsigned t = 1; !bad && t <= need; t++)
    {
      unsigned d = (unsigned char)s[i + t];
      if ((d & 0xC0) != 0x80)
        bad = 1;
      cp = (cp << 6) | (d & 0x3F);
    }
    if (!bad && ((need == 1 && cp < 0x80) || (need == 2 && (cp < 0x800 || (cp >= 0xD800 && cp < 0xE000))) || (need == 3 && (cp < 0x10000 || cp > 0x10FFFF))))
      bad = 1;
    if (bad)
    {
      out[k++] = 0xFFFD;
      i++;
      continue;
    }
    if (cp >= 0x10000)
    {
      cp -= 0x10000;
      out[k++] = (jchar)(0xD800 + (cp >> 10));
      out[k++] = (jchar)(0xDC00 + (cp & 0x3FF));
    }
    else
      out[k++] = (jchar)cp;
    i += need + 1;
  }
  jstring r = (*env)->NewString(env, out, (jsize)k);
  if (out != stack)
    free(out);
  return r;
}

#endif
