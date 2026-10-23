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

JNIEXPORT jstring JNICALL Java_dev_mclauncher_Native_eglClear(JNIEnv* env, jclass cls, jobject surface) {
    char out[1024];
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return mk(env, "EGL: ANativeWindow_fromSurface вернул NULL");
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj = 0, min = 0;
    if (!eglInitialize(dpy, &maj, &min)) {
        snprintf(out, sizeof out, "EGL: eglInitialize не удалось (0x%x)", eglGetError());
        return mk(env, out);
    }
    const EGLint cfgAttr[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_NONE };
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n) || n < 1) {
        snprintf(out, sizeof out, "EGL: нет подходящей конфигурации (0x%x)", eglGetError());
        return mk(env, out);
    }
    EGLSurface surf = eglCreateWindowSurface(dpy, cfg, win, NULL);
    const EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (surf == EGL_NO_SURFACE || ctx == EGL_NO_CONTEXT) {
        snprintf(out, sizeof out, "EGL: не создан surface/context (0x%x)", eglGetError());
        return mk(env, out);
    }
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) {
        snprintf(out, sizeof out, "EGL: eglMakeCurrent не удалось (0x%x)", eglGetError());
        return mk(env, out);
    }
    const char* ver = (const char*) glGetString(GL_VERSION);
    const char* ren = (const char*) glGetString(GL_RENDERER);
    glClearColor(0.0f, 0.42f, 0.47f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(dpy, surf);
    snprintf(out, sizeof out, "EGL %d.%d OK. GL: %s | %s", maj, min, ver ? ver : "?", ren ? ren : "?");
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    LOG("%s", out);
    return mk(env, out);
}

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

JNIEXPORT jstring JNICALL Java_dev_mclauncher_Native_startJvm(JNIEnv* env, jclass cls, jstring javaHome, jobjectArray opts) {
    char out[2048], e[512];
    JavaVM* jvm = NULL; JNIEnv* jenv = NULL;
    if (create_jvm(env, javaHome, opts, &jvm, &jenv, e, sizeof e) != 0) return mk(env, e);
    jclass sys = (*jenv)->FindClass(jenv, "java/lang/System");
    jmethodID gp = (*jenv)->GetStaticMethodID(jenv, sys, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    char ver[128], vm[128], arch[64];
    getProp(jenv, sys, gp, "java.version", ver, sizeof ver);
    getProp(jenv, sys, gp, "java.vm.name", vm, sizeof vm);
    getProp(jenv, sys, gp, "os.arch", arch, sizeof arch);
    snprintf(out, sizeof out, "JVM в процессе приложения запущена: Java %s, %s, %s", ver, vm, arch);
    LOG("%s", out);
    return mk(env, out);
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
