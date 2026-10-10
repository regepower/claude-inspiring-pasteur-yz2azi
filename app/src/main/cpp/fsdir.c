/* Fast folder listing: names, sizes, dates and types in one call (readdir + fstatat), instead of one
   Java attribute call per entry. Shared storage is a FUSE file system: every fstatat is a round trip to
   the system's storage daemon, which serves several requests at once, so the attributes are read by up
   to 4 threads in parallel. */

#include <pthread.h>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "jutil.h"

typedef struct
{
  int dfd;
  char **names;
  jlong *info;
  size_t from, to;
} StatJob;

static void stat_one(int dfd, const char *nm, jlong *in)
{
  struct stat st;
  if (fstatat(dfd, nm, &st, 0) == 0)   /* follows links, like the Java version */
  {
    in[0] = (jlong)st.st_size;
    in[1] = (jlong)st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1000000;
    in[2] = (S_ISDIR(st.st_mode) ? 1 : 0) | 2;
  }
}

static void *stat_range(void *arg)
{
  StatJob *j = (StatJob *)arg;
  for (size_t i = j->from; i < j->to; i++)
    stat_one(j->dfd, j->names[i], j->info + i * 3);
  return NULL;
}

/* Entries of [path]: Object[] { String[] names, long[] info } with 3 longs per entry
   (size, modified ms, flags: 1 = folder, 2 = attributes read). Without [withStat] only the type from
   the folder itself is given (fast: no access per entry; links and unknown types are still read).
   null if the folder cannot be read. */
JNIEXPORT jobjectArray JNICALL
Java_de_regepower_dualfiles_NativeLib_listDir(JNIEnv *env, jclass cls, jstring jPath, jboolean withStat)
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
    jlong *in = info + n * 3;
    in[0] = 0;
    in[1] = 0;
    in[2] = e->d_type == DT_DIR ? 1 : 0;
    if (!withStat && (e->d_type == DT_UNKNOWN || e->d_type == DT_LNK))
      stat_one(dfd, nm, in);
    names[n] = strdup(nm);
    if (!names[n])
    {
      ok = 0;
      break;
    }
    n++;
  }
  if (ok && withStat)
  {
    /* Attributes in parallel: up to 4 threads for big folders, this thread does the first part */
    size_t parts = n < 64 ? 1 : n < 256 ? 2 : 4;
    StatJob jobs[4];
    pthread_t th[4];
    int started[4] = {0, 0, 0, 0};
    for (size_t k = 0; k < parts; k++)
    {
      jobs[k].dfd = dfd;
      jobs[k].names = names;
      jobs[k].info = info;
      jobs[k].from = n * k / parts;
      jobs[k].to = n * (k + 1) / parts;
      if (k > 0)
        started[k] = pthread_create(&th[k], NULL, stat_range, &jobs[k]) == 0;
    }
    stat_range(&jobs[0]);
    for (size_t k = 1; k < parts; k++)
    {
      if (started[k])
        pthread_join(th[k], NULL);
      else
        stat_range(&jobs[k]);
    }
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
