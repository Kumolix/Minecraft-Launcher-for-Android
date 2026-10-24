// SimpleMC: нативный мост (этап 3).
//  - eglClear: проверка, что из процесса можно рисовать на экран через EGL/GLES
//  - startJvm: запуск Java (libjvm.so) внутри процесса приложения
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>

#define TAG "SimpleMC"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif
#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

static jstring mk(JNIEnv* env, const char* s) { return (*env)->NewStringUTF(env, s); }


typedef jint (*CreateJvmFn)(JavaVM**, void**, void*);

static void getProp(JNIEnv* e, jclass sys, jmethodID gp, const char* key, char* dst, size_t cap) {
    jstring k = (*e)->NewStringUTF(e, key);
    jstring v = (jstring) (*e)->CallStaticObjectMethod(e, sys, gp, k);
    if (v) {
        const char* s = (*e)->GetStringUTFChars(e, v, NULL);
        snprintf(dst, cap, "%s", s);
        (*e)->ReleaseStringUTFChars(e, v, s);
    } else {
        snprintf(dst, cap, "(нет)");
    }
}

// функции из libglfw.so
extern void smc_push_event(int type, double a, double b, int c);
extern void smc_set_surface(ANativeWindow* w, int width, int height);
extern void smc_set_gl_lib(const char* path);

static jint create_jvm(JNIEnv* env, jstring javaHome, jobjectArray opts, JavaVM** jvm, JNIEnv** jenv, char* err, size_t cap) {
    char path[1024];
    const char* home = (*env)->GetStringUTFChars(env, javaHome, NULL);
    snprintf(path, sizeof path, "%s/lib/server/libjvm.so", home);
    void* h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!h) { snprintf(err, cap, "JVM: dlopen(%s) не удался: %s", path, dlerror()); return -1; }
    CreateJvmFn create = (CreateJvmFn) dlsym(h, "JNI_CreateJavaVM");
    if (!create) { snprintf(err, cap, "JVM: нет символа JNI_CreateJavaVM"); return -1; }
    // Пространство имён приложения не видит папку lib рантайма: расширяем поиск и подгружаем заранее.
    {
        typedef void (*UpdFn)(const char*);
        UpdFn upd = (UpdFn) dlsym(RTLD_DEFAULT, "android_update_LD_LIBRARY_PATH");
        char lp[3200];
        snprintf(lp, sizeof lp, "%s/lib:%s/lib/server:%s/lib/jli", home, home, home);
        if (upd) { upd(lp); LOG("android_update_LD_LIBRARY_PATH: %s", lp); }
        else LOG("android_update_LD_LIBRARY_PATH недоступна");
        const char* pre[] = { "libjava.so", "libverify.so", "libzip.so", "libjimage.so",
                              "libnet.so", "libnio.so", "libextnet.so", NULL };
        for (int i = 0; pre[i]; i++) {
            char pp[1100];
            snprintf(pp, sizeof pp, "%s/lib/%s", home, pre[i]);
            void* ph = dlopen(pp, RTLD_NOW | RTLD_GLOBAL);
            LOG("preload %s -> %s", pre[i], ph ? "ok" : dlerror());
        }
    }
    int n = (*env)->GetArrayLength(env, opts);
    JavaVMOption* o = (JavaVMOption*) calloc(n, sizeof(JavaVMOption));
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, opts, i);
        o[i].optionString = (char*) (*env)->GetStringUTFChars(env, s, NULL);
    }
    JavaVMInitArgs args;
    memset(&args, 0, sizeof args);
    args.version = JNI_VERSION_1_8;
    args.nOptions = n;
    args.options = o;
    args.ignoreUnrecognized = JNI_TRUE;
    jint rc = create(jvm, (void**) jenv, &args);
    if (rc != JNI_OK || !*jenv) { snprintf(err, cap, "JVM: JNI_CreateJavaVM вернул %d", (int) rc); return -1; }
    return 0;
}


// Запуск main(String[]) внутри процесса. Блокирует до завершения игры.
JNIEXPORT jstring JNICALL Java_dev_mclauncher_Native_runMain(JNIEnv* env, jclass cls, jstring javaHome,
        jobjectArray opts, jstring mainClass, jobjectArray gameArgs) {
    char e[512];
    JavaVM* jvm = NULL; JNIEnv* jenv = NULL;
    if (create_jvm(env, javaHome, opts, &jvm, &jenv, e, sizeof e) != 0) return mk(env, e);
    const char* mc = (*env)->GetStringUTFChars(env, mainClass, NULL);
    char cname[512];
    snprintf(cname, sizeof cname, "%s", mc);
    for (char* c = cname; *c; c++) if (*c == '.') *c = '/';
    jclass main = (*jenv)->FindClass(jenv, cname);
    if (!main) { (*jenv)->ExceptionDescribe(jenv); return mk(env, "JVM: главный класс не найден"); }
    jmethodID mm = (*jenv)->GetStaticMethodID(jenv, main, "main", "([Ljava/lang/String;)V");
    if (!mm) { (*jenv)->ExceptionDescribe(jenv); return mk(env, "JVM: нет метода main"); }
    int n = (*env)->GetArrayLength(env, gameArgs);
    jclass strc = (*jenv)->FindClass(jenv, "java/lang/String");
    jobjectArray arr = (*jenv)->NewObjectArray(jenv, n, strc, NULL);
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, gameArgs, i);
        const char* u = (*env)->GetStringUTFChars(env, s, NULL);
        (*jenv)->SetObjectArrayElement(jenv, arr, i, (*jenv)->NewStringUTF(jenv, u));
        (*env)->ReleaseStringUTFChars(env, s, u);
    }
    LOG("runMain %s", cname);
    (*jenv)->CallStaticVoidMethod(jenv, main, mm, arr);
    if ((*jenv)->ExceptionCheck(jenv)) { (*jenv)->ExceptionDescribe(jenv); return mk(env, "JVM: main завершился исключением"); }
    return mk(env, "Игра завершилась");
}

JNIEXPORT void JNICALL Java_dev_mclauncher_Native_setSurface(JNIEnv* env, jclass cls, jobject surface, jint w, jint h) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    smc_set_surface(win, w, h);
}

JNIEXPORT void JNICALL Java_dev_mclauncher_Native_pushEvent(JNIEnv* env, jclass cls, jint type, jdouble a, jdouble b, jint c) {
    smc_push_event(type, a, b, c);
}

JNIEXPORT void JNICALL Java_dev_mclauncher_Native_setGlLib(JNIEnv* env, jclass cls, jstring path) {
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    smc_set_gl_lib(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
}

// stdout/stderr процесса -> файл (Java пишет туда логи игры)
static int g_pipe_r = -1;
static FILE* g_logf = NULL;
static void* pump(void* arg) {
    char buf[2048];
    for (;;) {
        ssize_t n = read(g_pipe_r, buf, sizeof buf - 1);
        if (n <= 0) break;
        buf[n] = 0;
        if (g_logf) { fwrite(buf, 1, n, g_logf); fflush(g_logf); }
        __android_log_write(ANDROID_LOG_INFO, "SimpleMC-Game", buf);
    }
    return NULL;
}
JNIEXPORT void JNICALL Java_dev_mclauncher_Native_chdir(JNIEnv* env, jclass cls, jstring path) {
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    chdir(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
}
JNIEXPORT void JNICALL Java_dev_mclauncher_Native_redirectOutput(JNIEnv* env, jclass cls, jstring path) {
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    g_logf = fopen(p, "w");
    (*env)->ReleaseStringUTFChars(env, path, p);
    int fds[2];
    if (pipe(fds) != 0) return;
    g_pipe_r = fds[0];
    dup2(fds[1], 1);
    dup2(fds[1], 2);
    pthread_t t;
    pthread_create(&t, NULL, pump, NULL);
}

extern void smc_surface_lost(void);
JNIEXPORT void JNICALL Java_dev_mclauncher_Native_surfaceLost(JNIEnv* env, jclass cls) { smc_surface_lost(); }
extern int smc_cursor_mode(void);
JNIEXPORT jint JNICALL Java_dev_mclauncher_Native_cursorMode(JNIEnv* env, jclass cls) { return smc_cursor_mode(); }

extern void smc_set_fps(int fps);
JNIEXPORT void JNICALL Java_dev_mclauncher_Native_setFpsCap(JNIEnv* env, jclass cls, jint fps) { smc_set_fps(fps); }
