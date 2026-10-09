/* 7z extraction for DualFiles, built on the 7z decoder of the LZMA SDK (lzma/, public domain).
   Extract only: LZMA, LZMA2, PPMd, BCJ/BCJ2/ARM filters. No encryption (returns SZ_ERROR_UNSUPPORTED). */

#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "lzma/7z.h"
#include "lzma/7zAlloc.h"
#include "lzma/7zCrc.h"
#include "lzma/7zFile.h"

#define IN_BUF_SIZE ((size_t)1 << 18)
#define ERR_PATH 100     /* entry name would leave the target folder */
#define ERR_CANCEL 101   /* cancelled by the app */

static const ISzAlloc g_Alloc = { SzAlloc, SzFree };

/* UTF-16 name from the archive to UTF-8; returns bytes written (without the 0) or -1. */
static int utf16_to_utf8(const UInt16 *s, char *out, size_t outSize)
{
  size_t n = 0;
  for (; *s; s++)
  {
    UInt32 c = *s;
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

/* Turns '\' into '/', drops empty parts and rejects absolute names and ".." parts. */
static int safe_name(char *name)
{
  char *r = name, *w = name;
  for (char *p = name; *p; p++)
    if (*p == '\\')
      *p = '/';
  while (*r)
  {
    while (*r == '/')
      r++;
    if (!*r)
      break;
    char *start = r;
    while (*r && *r != '/')
      r++;
    size_t len = (size_t)(r - start);
    if ((len == 1 && start[0] == '.'))
      continue;
    if (len == 2 && start[0] == '.' && start[1] == '.')
      return 0;
    if (w != name)
      *w++ = '/';
    memmove(w, start, len);
    w += len;
  }
  *w = 0;
  return w != name;
}

/* mkdir -p for every folder in [path] up to (not including) its last part, or the whole path if [all]. */
static int make_dirs(char *path, int all)
{
  for (char *p = path + 1; *p; p++)
    if (*p == '/')
    {
      *p = 0;
      int bad = mkdir(path, 0777) != 0 && errno != EEXIST;
      *p = '/';
      if (bad)
        return 0;
    }
  if (all && mkdir(path, 0777) != 0 && errno != EEXIST)
    return 0;
  return 1;
}

/* Extracts [archive] into the existing folder [outDir]. Returns the number of entries, or -code (SZ_ERROR_*, ERR_*). */
JNIEXPORT jint JNICALL
Java_de_regepower_dualfiles_SevenZip_extract(JNIEnv *env, jclass cls, jstring jArchive, jstring jOut, jobject cancel)
{
  (void)cls;
  jmethodID isCancelled = NULL;
  if (cancel)
  {
    jclass c = (*env)->GetObjectClass(env, cancel);
    isCancelled = (*env)->GetMethodID(env, c, "isCanceled", "()Z");
  }
  const char *archive = (*env)->GetStringUTFChars(env, jArchive, NULL);
  const char *outDir = (*env)->GetStringUTFChars(env, jOut, NULL);

  CFileInStream archiveStream;
  CLookToRead2 lookStream;
  CSzArEx db;
  SRes res = SZ_OK;
  int count = 0;
  UInt16 *name16 = NULL;
  size_t name16Size = 0;
  size_t pathSize = strlen(outDir) + 4096 * 4 + 2;
  char *path = (char *)malloc(pathSize);

  if (InFile_Open(&archiveStream.file, archive) != 0)
  {
    res = SZ_ERROR_READ;
    goto done_noarchive;
  }
  FileInStream_CreateVTable(&archiveStream);
  archiveStream.wres = 0;
  LookToRead2_CreateVTable(&lookStream, False);
  lookStream.buf = (Byte *)ISzAlloc_Alloc(&g_Alloc, IN_BUF_SIZE);
  if (!lookStream.buf || !path)
    res = SZ_ERROR_MEM;
  else
  {
    lookStream.bufSize = IN_BUF_SIZE;
    lookStream.realStream = &archiveStream.vt;
    LookToRead2_INIT(&lookStream)
  }

  CrcGenerateTable();
  SzArEx_Init(&db);
  if (res == SZ_OK)
    res = SzArEx_Open(&db, &lookStream.vt, &g_Alloc, &g_Alloc);

  if (res == SZ_OK)
  {
    UInt32 blockIndex = 0xFFFFFFFF;
    Byte *outBuffer = NULL;
    size_t outBufferSize = 0;
    for (UInt32 i = 0; i < db.NumFiles; i++)
    {
      if (isCancelled && (*env)->CallBooleanMethod(env, cancel, isCancelled))
      {
        res = ERR_CANCEL;
        break;
      }
      size_t offset = 0, outSizeProcessed = 0;
      const BoolInt isDir = SzArEx_IsDir(&db, i);
      size_t len = SzArEx_GetFileNameUtf16(&db, i, NULL);
      if (len > name16Size)
      {
        SzFree(NULL, name16);
        name16Size = len;
        name16 = (UInt16 *)SzAlloc(NULL, name16Size * sizeof(UInt16));
        if (!name16)
        {
          res = SZ_ERROR_MEM;
          break;
        }
      }
      SzArEx_GetFileNameUtf16(&db, i, name16);

      size_t base = (size_t)snprintf(path, pathSize, "%s/", outDir);
      if (utf16_to_utf8(name16, path + base, pathSize - base) < 0 || !safe_name(path + base))
      {
        res = ERR_PATH;
        break;
      }
      if (isDir)
      {
        if (!make_dirs(path, 1))
        {
          res = SZ_ERROR_WRITE;
          break;
        }
        count++;
        continue;
      }
      res = SzArEx_Extract(&db, &lookStream.vt, i, &blockIndex, &outBuffer, &outBufferSize,
          &offset, &outSizeProcessed, &g_Alloc, &g_Alloc);
      if (res != SZ_OK)
        break;
      if (!make_dirs(path, 0))
      {
        res = SZ_ERROR_WRITE;
        break;
      }
      CSzFile outFile;
      if (OutFile_Open(&outFile, path) != 0)
      {
        res = SZ_ERROR_WRITE;
        break;
      }
      size_t processed = outSizeProcessed;
      WRes w = File_Write(&outFile, outBuffer + offset, &processed);
      File_Close(&outFile);
      if (w != 0 || processed != outSizeProcessed)
      {
        res = SZ_ERROR_WRITE;
        break;
      }
      count++;
    }
    ISzAlloc_Free(&g_Alloc, outBuffer);
  }

  SzFree(NULL, name16);
  SzArEx_Free(&db, &g_Alloc);
  ISzAlloc_Free(&g_Alloc, lookStream.buf);
  File_Close(&archiveStream.file);

done_noarchive:
  free(path);
  (*env)->ReleaseStringUTFChars(env, jArchive, archive);
  (*env)->ReleaseStringUTFChars(env, jOut, outDir);
  return res == SZ_OK ? count : -res;
}
