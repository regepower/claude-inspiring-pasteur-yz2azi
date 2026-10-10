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
#define ERR_OPEN 102     /* archive file cannot be opened */

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

/* Java string as standard UTF-8 (JNI's own UTF-8 is "modified" and breaks emoji); free() the result. */
static char *jstr_utf8(JNIEnv *env, jstring js)
{
  jsize len = (*env)->GetStringLength(env, js);
  const jchar *chars = (*env)->GetStringChars(env, js, NULL);
  UInt16 *z = (UInt16 *)malloc(((size_t)len + 1) * sizeof(UInt16));
  size_t outSize = (size_t)len * 3 + 8;
  char *out = (char *)malloc(outSize);
  if (z && out && chars)
  {
    memcpy(z, chars, (size_t)len * sizeof(UInt16));
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

/* An opened archive; Open/Close keep the setup of the LZMA SDK in one place. */
typedef struct
{
  CFileInStream archiveStream;
  CLookToRead2 lookStream;
  CSzArEx db;
} Arc;

static SRes arc_open(Arc *a, const char *path)
{
  memset(a, 0, sizeof(*a));
  SzArEx_Init(&a->db);
  if (InFile_Open(&a->archiveStream.file, path) != 0)
    return ERR_OPEN;
  FileInStream_CreateVTable(&a->archiveStream);
  a->archiveStream.wres = 0;
  LookToRead2_CreateVTable(&a->lookStream, False);
  a->lookStream.buf = (Byte *)ISzAlloc_Alloc(&g_Alloc, IN_BUF_SIZE);
  if (!a->lookStream.buf)
    return SZ_ERROR_MEM;
  a->lookStream.bufSize = IN_BUF_SIZE;
  a->lookStream.realStream = &a->archiveStream.vt;
  LookToRead2_INIT(&a->lookStream)
  CrcGenerateTable();
  return SzArEx_Open(&a->db, &a->lookStream.vt, &g_Alloc, &g_Alloc);
}

static void arc_close(Arc *a)
{
  SzArEx_Free(&a->db, &g_Alloc);
  ISzAlloc_Free(&g_Alloc, a->lookStream.buf);
  File_Close(&a->archiveStream.file);
}

#define NAME_MAX_BYTES (4096 * 4 + 1)

/* Contents of [archive], one string per entry in archive order: "D|F <tab> size <tab> mtime ms <tab> name".
   On failure a single string "!<code>". */
JNIEXPORT jobjectArray JNICALL
Java_de_regepower_dualfiles_SevenZip_list(JNIEnv *env, jclass cls, jstring jArchive)
{
  (void)cls;
  jclass strClass = (*env)->FindClass(env, "java/lang/String");
  char *archive = jstr_utf8(env, jArchive);
  Arc a;
  SRes res = archive ? arc_open(&a, archive) : ERR_OPEN;
  free(archive);
  jobjectArray out = NULL;
  jchar *line = NULL;
  size_t lineSize = 0;
  if (res == SZ_OK)
  {
    out = (*env)->NewObjectArray(env, (jsize)a.db.NumFiles, strClass, NULL);
    for (UInt32 i = 0; out && i < a.db.NumFiles; i++)
    {
      long long mtime = 0;
      if (SzBitWithVals_Check(&a.db.MTime, i))
      {
        const CNtfsFileTime *t = &a.db.MTime.Vals[i];
        unsigned long long ft = ((unsigned long long)t->High << 32) | t->Low;
        mtime = (long long)(ft / 10000) - 11644473600000LL;   /* 100 ns since 1601 -> ms since 1970 */
      }
      char head[64];
      int n = snprintf(head, sizeof(head), "%c\t%llu\t%lld\t", SzArEx_IsDir(&a.db, i) ? 'D' : 'F',
          (unsigned long long)SzArEx_GetFileSize(&a.db, i), mtime);
      size_t len = SzArEx_GetFileNameUtf16(&a.db, i, NULL);   /* with the closing 0 */
      if ((size_t)n + len > lineSize)
      {
        free(line);
        lineSize = (size_t)n + len + 256;
        line = (jchar *)malloc(lineSize * sizeof(jchar));
        if (!line)
          break;
      }
      for (int k = 0; k < n; k++)
        line[k] = (jchar)head[k];
      SzArEx_GetFileNameUtf16(&a.db, i, (UInt16 *)(line + n));
      for (size_t k = 0; k + 1 < len; k++)
        if (line[n + k] == '\\')
          line[n + k] = '/';
      jstring s = (*env)->NewString(env, line, (jsize)(n + (len ? len - 1 : 0)));
      if (!s)
        break;
      (*env)->SetObjectArrayElement(env, out, (jsize)i, s);
      (*env)->DeleteLocalRef(env, s);
    }
    arc_close(&a);
  }
  else
  {
    if (res != ERR_OPEN)
      arc_close(&a);
    char err[16];
    snprintf(err, sizeof(err), "!%d", res);
    out = (*env)->NewObjectArray(env, 1, strClass, (*env)->NewStringUTF(env, err));
  }
  free(line);
  return out;
}

/* Extracts the entries whose [outNames] element is not null to [outDir]/<that name> (a relative path that is
   checked again here). [progress].step(done, total) is called around each file; false cancels.
   Returns the number of written entries, or -code (SZ_ERROR_*, ERR_*). */
JNIEXPORT jint JNICALL
Java_de_regepower_dualfiles_SevenZip_extract(JNIEnv *env, jclass cls, jstring jArchive, jstring jOut,
    jobjectArray outNames, jobject progress)
{
  (void)cls;
  jclass pc = (*env)->GetObjectClass(env, progress);
  jmethodID step = (*env)->GetMethodID(env, pc, "step", "(JJ)Z");
  char *archive = jstr_utf8(env, jArchive);
  Arc a;
  SRes res = archive ? arc_open(&a, archive) : ERR_OPEN;
  free(archive);
  if (res != SZ_OK)
  {
    if (res != ERR_OPEN)
      arc_close(&a);
    return -res;
  }
  char *outDir = jstr_utf8(env, jOut);
  size_t pathSize = (outDir ? strlen(outDir) : 0) + NAME_MAX_BYTES + 2;
  char *path = outDir ? (char *)malloc(pathSize) : NULL;
  jsize count = (*env)->GetArrayLength(env, outNames);
  int written = 0;
  if (!path)
    res = SZ_ERROR_MEM;
  if (count != (jsize)a.db.NumFiles)
    res = SZ_ERROR_PARAM;

  unsigned long long total = 0, done = 0;
  for (UInt32 i = 0; res == SZ_OK && i < a.db.NumFiles; i++)
  {
    jobject o = (*env)->GetObjectArrayElement(env, outNames, (jsize)i);
    if (o && !SzArEx_IsDir(&a.db, i))
      total += SzArEx_GetFileSize(&a.db, i);
    (*env)->DeleteLocalRef(env, o);
  }

  UInt32 blockIndex = 0xFFFFFFFF;
  Byte *outBuffer = NULL;
  size_t outBufferSize = 0;
  for (UInt32 i = 0; res == SZ_OK && i < a.db.NumFiles; i++)
  {
    jstring jName = (jstring)(*env)->GetObjectArrayElement(env, outNames, (jsize)i);
    if (!jName)
      continue;
    if (!(*env)->CallBooleanMethod(env, progress, step, (jlong)done, (jlong)total))
    {
      res = ERR_CANCEL;
      break;
    }
    char *name = jstr_utf8(env, jName);
    size_t base = (size_t)snprintf(path, pathSize, "%s/", outDir);
    int ok = name && strlen(name) < NAME_MAX_BYTES;
    if (ok)
    {
      strcpy(path + base, name);
      ok = safe_name(path + base);
    }
    free(name);
    (*env)->DeleteLocalRef(env, jName);
    if (!ok)
    {
      res = ERR_PATH;
      break;
    }
    if (SzArEx_IsDir(&a.db, i))
    {
      if (!make_dirs(path, 1))
        res = SZ_ERROR_WRITE;
      else
        written++;
      continue;
    }
    size_t offset = 0, outSizeProcessed = 0;
    res = SzArEx_Extract(&a.db, &a.lookStream.vt, i, &blockIndex, &outBuffer, &outBufferSize,
        &offset, &outSizeProcessed, &g_Alloc, &g_Alloc);
    if (res != SZ_OK)
      break;
    CSzFile outFile;
    if (!make_dirs(path, 0) || OutFile_Open(&outFile, path) != 0)
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
    done += outSizeProcessed;
    written++;
  }
  if (res == SZ_OK)
    (*env)->CallBooleanMethod(env, progress, step, (jlong)done, (jlong)total);

  ISzAlloc_Free(&g_Alloc, outBuffer);
  arc_close(&a);
  free(path);
  free(outDir);
  return res == SZ_OK ? written : -res;
}
