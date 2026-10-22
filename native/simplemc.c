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

JNIEXPORT jstring JNICALL Java_dev_mclauncher_Native_startJvm(JNIEnv* env, jclass cls, jstring javaHome, jobjectArray opts) {
    char out[2048], path[1024];
    const char* home = (*env)->GetStringUTFChars(env, javaHome, NULL);
    snprintf(path, sizeof path, "%s/lib/server/libjvm.so", home);
    void* h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        snprintf(out, sizeof out, "JVM: dlopen(%s) не удался: %s", path, dlerror());
        return mk(env, out);
    }
    CreateJvmFn create = (CreateJvmFn) dlsym(h, "JNI_CreateJavaVM");
    if (!create) return mk(env, "JVM: нет символа JNI_CreateJavaVM");
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
    JavaVM* jvm = NULL; JNIEnv* jenv = NULL;
    jint rc = create(&jvm, (void**) &jenv, &args);
    if (rc != JNI_OK || !jenv) {
        snprintf(out, sizeof out, "JVM: JNI_CreateJavaVM вернул %d", (int) rc);
        return mk(env, out);
    }
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
