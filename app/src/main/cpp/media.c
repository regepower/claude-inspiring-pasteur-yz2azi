/* EXIF for WebP conversion: reads the EXIF block of a JPEG and puts it into a WebP file written by
   Android's encoder (which writes none). The orientation is set to "normal" because the converter
   already turned the pixels. */

#include <stdio.h>

#include "jutil.h"

static unsigned rd16(const unsigned char *p, int le) { return le ? p[0] | p[1] << 8 : p[0] << 8 | p[1]; }
static uint32_t rd32(const unsigned char *p, int le)
{
  return le ? (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24
            : (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | (uint32_t)p[3];
}
static void wr32le(unsigned char *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

/* Sets tag 0x0112 (orientation) in IFD0 of the TIFF block [t] to 1. */
static void reset_orientation(unsigned char *t, size_t n)
{
  if (n < 8)
    return;
  int le = t[0] == 'I' && t[1] == 'I';
  if (!le && !(t[0] == 'M' && t[1] == 'M'))
    return;
  uint32_t ifd = rd32(t + 4, le);
  if (ifd + 2 > n)
    return;
  unsigned count = rd16(t + ifd, le);
  for (unsigned i = 0; i < count; i++)
  {
    size_t e = ifd + 2 + (size_t)i * 12;
    if (e + 12 > n)
      return;
    if (rd16(t + e, le) == 0x0112 && rd16(t + e + 2, le) == 3)
    {
      t[e + 8] = le ? 1 : 0;
      t[e + 9] = le ? 0 : 1;
      return;
    }
  }
}

/* The TIFF part of the JPEG's EXIF block (APP1 "Exif\0\0"), orientation reset; null if there is none. */
JNIEXPORT jbyteArray JNICALL
Java_de_regepower_dualfiles_NativeLib_jpegExif(JNIEnv *env, jclass cls, jstring jPath)
{
  (void)cls;
  char *path = jstr_utf8(env, jPath);
  FILE *f = path ? fopen(path, "rb") : NULL;
  free(path);
  if (!f)
    return NULL;
  jbyteArray result = NULL;
  unsigned char m[4];
  if (fread(m, 1, 2, f) == 2 && m[0] == 0xFF && m[1] == 0xD8)
  {
    while (fread(m, 1, 4, f) == 4 && m[0] == 0xFF)
    {
      unsigned marker = m[1];
      size_t len = (size_t)(m[2] << 8 | m[3]);
      if (marker == 0xDA || marker == 0xD9 || len < 2)
        break;   /* image data starts: no EXIF before it */
      if (marker == 0xE1 && len > 8)
      {
        unsigned char *b = (unsigned char *)malloc(len - 2);
        if (b && fread(b, 1, len - 2, f) == len - 2 && memcmp(b, "Exif\0\0", 6) == 0)
        {
          size_t n = len - 8;
          reset_orientation(b + 6, n);
          result = (*env)->NewByteArray(env, (jsize)n);
          if (result)
            (*env)->SetByteArrayRegion(env, result, 0, (jsize)n, (const jbyte *)(b + 6));
          free(b);
          break;
        }
        free(b);
        continue;
      }
      if (fseek(f, (long)len - 2, SEEK_CUR) != 0)
        break;
    }
  }
  fclose(f);
  return result;
}

/* Rewrites the WebP file [path] in the extended format with [exif] as EXIF chunk. [w]/[h]: canvas size. */
JNIEXPORT jboolean JNICALL
Java_de_regepower_dualfiles_NativeLib_webpAddExif(JNIEnv *env, jclass cls, jstring jPath, jbyteArray jExif,
    jint w, jint h, jboolean alpha)
{
  (void)cls;
  char *path = jstr_utf8(env, jPath);
  if (!path)
    return JNI_FALSE;
  FILE *f = fopen(path, "rb");
  unsigned char *in = NULL, *out = NULL;
  long size = -1;
  jboolean ok = JNI_FALSE;
  if (f && fseek(f, 0, SEEK_END) == 0 && (size = ftell(f)) >= 20 && fseek(f, 0, SEEK_SET) == 0)
  {
    in = (unsigned char *)malloc((size_t)size);
    if (!in || fread(in, 1, (size_t)size, f) != (size_t)size)
      size = -1;
  }
  if (f)
    fclose(f);
  jsize exifLen = (*env)->GetArrayLength(env, jExif);
  if (size >= 20 && memcmp(in, "RIFF", 4) == 0 && memcmp(in + 8, "WEBP", 4) == 0 && exifLen > 0)
  {
    int extended = memcmp(in + 12, "VP8X", 4) == 0;
    size_t body = (size_t)size - 12;                    /* the chunks after "RIFF....WEBP" */
    size_t exifChunk = 8 + (size_t)exifLen + (exifLen & 1);
    size_t total = 12 + (extended ? 0 : 18) + body + exifChunk;
    out = (unsigned char *)malloc(total);
    if (out && w > 0 && h > 0)
    {
      unsigned char *p = out;
      memcpy(p, "RIFF", 4);
      wr32le(p + 4, (uint32_t)(total - 8));
      memcpy(p + 8, "WEBP", 4);
      p += 12;
      if (!extended)
      {
        memcpy(p, "VP8X", 4);
        wr32le(p + 4, 10);
        memset(p + 8, 0, 10);
        p[8] = 0x08 | (alpha ? 0x10 : 0);              /* EXIF (+ alpha) present */
        uint32_t cw = (uint32_t)w - 1, ch = (uint32_t)h - 1;
        p[12] = cw; p[13] = cw >> 8; p[14] = cw >> 16;
        p[15] = ch; p[16] = ch >> 8; p[17] = ch >> 16;
        p += 18;
      }
      memcpy(p, in + 12, body);
      if (extended)
        p[8] |= 0x08;
      p += body;
      memcpy(p, "EXIF", 4);
      wr32le(p + 4, (uint32_t)exifLen);
      (*env)->GetByteArrayRegion(env, jExif, 0, exifLen, (jbyte *)(p + 8));
      if (exifLen & 1)
        p[8 + exifLen] = 0;
      FILE *o = fopen(path, "wb");
      if (o)
      {
        ok = fwrite(out, 1, total, o) == total ? JNI_TRUE : JNI_FALSE;
        if (fclose(o) != 0)
          ok = JNI_FALSE;
      }
    }
  }
  free(in);
  free(out);
  free(path);
  return ok;
}
