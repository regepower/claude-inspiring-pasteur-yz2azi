/* Fast folder listing: names, sizes, dates and types in one call (readdir + fstatat), instead of one
   Java attribute call per entry. */

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "jutil.h"

/* Entries of [path]: Object[] { String[] names, long[] info } with 3 longs per entry
   (size, modified ms, flags: 1 = folder, 2 = attributes read). null if the folder cannot be read. */
JNIEXPORT jobjectArray JNICALL
Java_de_regepower_dualfiles_NativeLib_listDir(JNIEnv *env, jclass cls, jstring jPath)
{
  (void)cls;
  char *path = jstr_utf8(env, jPath);
  if (!path)
    return NULL;
  int dfd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  free(path);
  if (dfd < 0)
    return NULL;
  DIR *d = fdopendir(dfd);
  if (!d)
  {
    close(dfd);
    return NULL;
  }

  size_t cap = 256, n = 0;
  char **names = (char **)malloc(cap * sizeof(char *));
  jlong *info = (jlong *)malloc(cap * 3 * sizeof(jlong));
  int ok = names && info;
  struct dirent *e;
  while (ok && (e = readdir(d)) != NULL)
  {
    const char *nm = e->d_name;
    if (nm[0] == '.' && (nm[1] == 0 || (nm[1] == '.' && nm[2] == 0)))
      continue;
    if (n == cap)
    {
      cap *= 2;
      char **nn = (char **)realloc(names, cap * sizeof(char *));
      jlong *ni = nn ? (jlong *)realloc(info, cap * 3 * sizeof(jlong)) : NULL;
      if (nn)
        names = nn;
      if (ni)
        info = ni;
      if (!nn || !ni)
      {
        ok = 0;
        break;
      }
    }
    struct stat st;
    jlong *in = info + n * 3;
    if (fstatat(dfd, nm, &st, 0) == 0)   /* follows links, like the Java version */
    {
      in[0] = (jlong)st.st_size;
      in[1] = (jlong)st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1000000;
      in[2] = (S_ISDIR(st.st_mode) ? 1 : 0) | 2;
    }
    else
    {
      in[0] = 0;
      in[1] = 0;
      in[2] = e->d_type == DT_DIR ? 1 : 0;
    }
    names[n] = strdup(nm);
    if (!names[n])
    {
      ok = 0;
      break;
    }
    n++;
  }
  closedir(d);   /* also closes dfd */

  jobjectArray result = NULL;
  if (ok)
  {
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jclass objClass = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray jNames = (*env)->NewObjectArray(env, (jsize)n, strClass, NULL);
    jlongArray jInfo = (*env)->NewLongArray(env, (jsize)(n * 3));
    if (jNames && jInfo)
    {
      for (size_t i = 0; i < n; i++)
      {
        jstring s = utf8_jstr(env, names[i], strlen(names[i]));
        if (!s)
          break;
        (*env)->SetObjectArrayElement(env, jNames, (jsize)i, s);
        (*env)->DeleteLocalRef(env, s);
      }
      (*env)->SetLongArrayRegion(env, jInfo, 0, (jsize)(n * 3), info);
      result = (*env)->NewObjectArray(env, 2, objClass, NULL);
      if (result)
      {
        (*env)->SetObjectArrayElement(env, result, 0, jNames);
        (*env)->SetObjectArrayElement(env, result, 1, jInfo);
      }
    }
  }
  for (size_t i = 0; i < n; i++)
    free(names[i]);
  free(names);
  free(info);
  return result;
}
