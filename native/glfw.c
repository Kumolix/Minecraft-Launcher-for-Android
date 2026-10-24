// libglfw.so для Android: подмножество GLFW 3.3 API поверх EGL.
// LWJGL загружает эту библиотеку вместо настоящей GLFW.
#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "SimpleMC-GLFW", __VA_ARGS__)
#define EXPORT __attribute__((visibility("default")))

typedef struct { int width, height, redBits, greenBits, blueBits, refreshRate; } VidMode;
typedef void (*errfun)(int, const char*);
typedef void (*keyfun)(void*, int, int, int, int);
typedef void (*charfun)(void*, unsigned int);
typedef void (*charmodsfun)(void*, unsigned int, int);
typedef void (*mbfun)(void*, int, int, int);
typedef void (*cposfun)(void*, double, double);
typedef void (*centerfun)(void*, int);
typedef void (*scrollfun)(void*, double, double);
typedef void (*dropfun)(void*, int, const char**);
typedef void (*sizefun)(void*, int, int);
typedef void (*focusfun)(void*, int);
typedef void (*closefun)(void*);
typedef void (*scalefun)(void*, float, float);

static struct {
    ANativeWindow* volatile win; int w, h, size_dirty; volatile int surf_dirty;
    EGLDisplay dpy; EGLSurface surf; EGLContext ctx; EGLConfig cfg;
    void* gl_lib;
    errfun err; keyfun key; charfun chr; charmodsfun chrmods; mbfun mbtn; cposfun cpos;
    centerfun center; scrollfun scroll; dropfun drop; sizefun size; sizefun fbsize; sizefun pos;
    focusfun focus; closefun close; centerfun iconify; closefun refresh; centerfun maximize; scalefun scale;
    double cx, cy; int cursor_mode; char keys[512]; char mouse[16];
    int should_close, created; void* user; char* clip; struct timespec t0; double toff;
    VidMode vm; int mon; void* monlist[1];
} G = { .cursor_mode = 0x34001 };

// ---------- очередь событий из Android ----------
typedef struct { int type; double a, b; int c; } Ev;
#define QN 2048
static Ev q[QN]; static int qh = 0, qt = 0;
static pthread_mutex_t qm = PTHREAD_MUTEX_INITIALIZER;

EXPORT void smc_push_event(int type, double a, double b, int c) {
    pthread_mutex_lock(&qm);
    q[qt].type = type; q[qt].a = a; q[qt].b = b; q[qt].c = c;
    qt = (qt + 1) % QN;
    if (qt == qh) qh = (qh + 1) % QN;
    pthread_mutex_unlock(&qm);
}

EXPORT void smc_surface_lost(void) { G.win = NULL; }

EXPORT void smc_set_surface(ANativeWindow* w, int width, int height) {
    if (G.created && w && w != G.win) G.surf_dirty = 1;
    G.win = w;
    if (G.w != width || G.h != height) G.size_dirty = 1;
    G.w = width; G.h = height;
}

EXPORT void smc_set_gl_lib(const char* path) {
    G.gl_lib = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    LOG("gl lib %s -> %p", path, G.gl_lib);
}

static double now(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (t.tv_sec - G.t0.tv_sec) + (t.tv_nsec - G.t0.tv_nsec) / 1e9;
}

// ---------- инициализация ----------
EXPORT int glfwInit(void) {
    clock_gettime(CLOCK_MONOTONIC, &G.t0);
    G.vm.width = G.w ? G.w : 1920; G.vm.height = G.h ? G.h : 1080;
    G.vm.redBits = G.vm.greenBits = G.vm.blueBits = 8; G.vm.refreshRate = 60;
    G.monlist[0] = &G.mon;
    LOG("glfwInit");
    return 1;
}
EXPORT void glfwTerminate(void) { LOG("glfwTerminate"); }
EXPORT void glfwInitHint(int h, int v) {}
EXPORT void glfwGetVersion(int* a, int* b, int* c) { if (a) *a = 3; if (b) *b = 3; if (c) *c = 8; }
EXPORT const char* glfwGetVersionString(void) { return "3.3.8 SimpleMC (Android EGL)"; }
EXPORT int glfwGetError(const char** d) { if (d) *d = NULL; return 0; }
EXPORT errfun glfwSetErrorCallback(errfun f) { errfun o = G.err; G.err = f; return o; }
EXPORT int glfwGetPlatform(void) { return 0x60005; }
EXPORT int glfwPlatformSupported(int p) { return p == 0x60005; }

// ---------- окно и контекст ----------
EXPORT void glfwDefaultWindowHints(void) {}
EXPORT void glfwWindowHint(int h, int v) {}
EXPORT void glfwWindowHintString(int h, const char* v) {}

EXPORT void* glfwCreateWindow(int width, int height, const char* title, void* monitor, void* share) {
    if (!G.win) { if (G.err) G.err(0x10008, "нет Android-поверхности"); return NULL; }
    G.dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(G.dpy, 0, 0)) { LOG("eglInitialize fail"); return NULL; }
    const EGLint attr[] = { EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_NONE };
    EGLint n = 0;
    if (!eglChooseConfig(G.dpy, attr, &G.cfg, 1, &n) || n < 1) { LOG("no egl config"); return NULL; }
    G.surf = eglCreateWindowSurface(G.dpy, G.cfg, G.win, NULL);
    const EGLint c32[] = { 0x3098, 3, 0x30FB, 2, EGL_NONE };
    const EGLint c30[] = { 0x3098, 3, EGL_NONE };
    G.ctx = eglCreateContext(G.dpy, G.cfg, EGL_NO_CONTEXT, c32);
    if (G.ctx == EGL_NO_CONTEXT) G.ctx = eglCreateContext(G.dpy, G.cfg, EGL_NO_CONTEXT, c30);
    if (G.surf == EGL_NO_SURFACE || G.ctx == EGL_NO_CONTEXT) { LOG("egl surface/context fail 0x%x", eglGetError()); return NULL; }
    G.created = 1;
    LOG("glfwCreateWindow ok %dx%d", G.w, G.h);
    return &G;
}
EXPORT void glfwDestroyWindow(void* w) {
    if (!G.created) return;
    eglMakeCurrent(G.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(G.dpy, G.ctx); eglDestroySurface(G.dpy, G.surf);
    G.created = 0;
}
EXPORT void glfwMakeContextCurrent(void* w) {
    if (w) eglMakeCurrent(G.dpy, G.surf, G.surf, G.ctx);
    else eglMakeCurrent(G.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}
EXPORT void* glfwGetCurrentContext(void) { return eglGetCurrentContext() != EGL_NO_CONTEXT ? (void*) &G : NULL; }
EXPORT void glfwSwapInterval(int i) { if (G.dpy) eglSwapInterval(G.dpy, i); }
static int g_fps = 0;
static double g_lastFrame = 0;
EXPORT void smc_set_fps(int fps) { g_fps = fps; }

EXPORT void glfwSwapBuffers(void* w) {
    if (!G.created) return;
    if (!G.win) { usleep(30000); return; }            // поверхность потеряна (приложение свёрнуто)
    if (G.surf_dirty) {                                // вернулись: новая поверхность
        G.surf_dirty = 0;
        eglMakeCurrent(G.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, G.ctx);
        if (G.surf != EGL_NO_SURFACE) eglDestroySurface(G.dpy, G.surf);
        G.surf = eglCreateWindowSurface(G.dpy, G.cfg, G.win, NULL);
        eglMakeCurrent(G.dpy, G.surf, G.surf, G.ctx);
        G.size_dirty = 1;
        LOG("поверхность пересоздана %dx%d", G.w, G.h);
    }
    if (eglSwapBuffers(G.dpy, G.surf) == EGL_FALSE) G.surf_dirty = G.win ? 1 : 0;
    if (g_fps > 0) {                                   // ограничение FPS (стабилизация производительности)
        double target = 1.0 / g_fps, t = now(), wait = g_lastFrame + target - t;
        if (wait > 0 && wait < 0.2) usleep((useconds_t) (wait * 1e6));
        g_lastFrame = now();
    }
}

EXPORT void* glfwGetProcAddress(const char* name) {
    void* p = G.gl_lib ? dlsym(G.gl_lib, name) : NULL;
    return p ? p : (void*) eglGetProcAddress(name);
}
EXPORT int glfwExtensionSupported(const char* e) { return 0; }

// ---------- события ----------
EXPORT void glfwPollEvents(void) {
    if (G.size_dirty) {
        G.size_dirty = 0;
        G.vm.width = G.w; G.vm.height = G.h;
        if (G.fbsize) G.fbsize(&G, G.w, G.h);
        if (G.size) G.size(&G, G.w, G.h);
    }
    for (;;) {
        Ev e;
        pthread_mutex_lock(&qm);
        if (qh == qt) { pthread_mutex_unlock(&qm); break; }
        e = q[qh]; qh = (qh + 1) % QN;
        pthread_mutex_unlock(&qm);
        switch (e.type) {
            case 1: { int k = (int) e.a, act = e.c & 15, mods = e.c >> 4;
                if (k >= 0 && k < 512) G.keys[k] = (char) act;
                if (G.key) G.key(&G, k, (int) e.b, act, mods); break; }
            case 2: if (G.chr) G.chr(&G, (unsigned) e.a); if (G.chrmods) G.chrmods(&G, (unsigned) e.a, 0); break;
            case 3: G.cx = e.a; G.cy = e.b; if (G.cpos) G.cpos(&G, e.a, e.b); break;
            case 4: { int b = (int) e.a; if (b >= 0 && b < 16) G.mouse[b] = (char) e.c;
                if (G.mbtn) G.mbtn(&G, b, e.c, 0); break; }
            case 5: if (G.scroll) G.scroll(&G, e.a, e.b); break;
            case 6: if (G.focus) G.focus(&G, e.c); break;
        }
    }
}
EXPORT void glfwWaitEvents(void) { usleep(2000); glfwPollEvents(); }
EXPORT void glfwWaitEventsTimeout(double t) { usleep(2000); glfwPollEvents(); }
EXPORT void glfwPostEmptyEvent(void) {}

#define SETCB(name, type, field) EXPORT type name(void* w, type f) { type o = G.field; G.field = f; return o; }
SETCB(glfwSetKeyCallback, keyfun, key)
SETCB(glfwSetCharCallback, charfun, chr)
SETCB(glfwSetCharModsCallback, charmodsfun, chrmods)
SETCB(glfwSetMouseButtonCallback, mbfun, mbtn)
SETCB(glfwSetCursorPosCallback, cposfun, cpos)
SETCB(glfwSetCursorEnterCallback, centerfun, center)
SETCB(glfwSetScrollCallback, scrollfun, scroll)
SETCB(glfwSetDropCallback, dropfun, drop)
SETCB(glfwSetWindowSizeCallback, sizefun, size)
SETCB(glfwSetFramebufferSizeCallback, sizefun, fbsize)
SETCB(glfwSetWindowPosCallback, sizefun, pos)
SETCB(glfwSetWindowFocusCallback, focusfun, focus)
SETCB(glfwSetWindowCloseCallback, closefun, close)
SETCB(glfwSetWindowIconifyCallback, centerfun, iconify)
SETCB(glfwSetWindowRefreshCallback, closefun, refresh)
SETCB(glfwSetWindowMaximizeCallback, centerfun, maximize)
SETCB(glfwSetWindowContentScaleCallback, scalefun, scale)

// ---------- состояние окна ----------
EXPORT int glfwWindowShouldClose(void* w) { return G.should_close; }
EXPORT void glfwSetWindowShouldClose(void* w, int v) { G.should_close = v; }
EXPORT void glfwGetFramebufferSize(void* w, int* a, int* b) { if (a) *a = G.w; if (b) *b = G.h; }
EXPORT void glfwGetWindowSize(void* w, int* a, int* b) { if (a) *a = G.w; if (b) *b = G.h; }
EXPORT void glfwGetWindowPos(void* w, int* a, int* b) { if (a) *a = 0; if (b) *b = 0; }
EXPORT void glfwGetWindowFrameSize(void* w, int* l, int* t, int* r, int* b) { if (l) *l = 0; if (t) *t = 0; if (r) *r = 0; if (b) *b = 0; }
EXPORT void glfwGetWindowContentScale(void* w, float* x, float* y) { if (x) *x = 1.0f; if (y) *y = 1.0f; }
EXPORT void glfwSetWindowSize(void* w, int a, int b) {}
EXPORT void glfwSetWindowPos(void* w, int a, int b) {}
EXPORT void glfwSetWindowTitle(void* w, const char* t) {}
EXPORT void glfwSetWindowIcon(void* w, int n, const void* img) {}
EXPORT void glfwSetWindowSizeLimits(void* w, int a, int b, int c, int d) {}
EXPORT void glfwSetWindowAspectRatio(void* w, int a, int b) {}
EXPORT void glfwShowWindow(void* w) {}
EXPORT void glfwHideWindow(void* w) {}
EXPORT void glfwFocusWindow(void* w) {}
EXPORT void glfwMaximizeWindow(void* w) {}
EXPORT void glfwRestoreWindow(void* w) {}
EXPORT void glfwIconifyWindow(void* w) {}
EXPORT void glfwRequestWindowAttention(void* w) {}
EXPORT void glfwSetWindowAttrib(void* w, int a, int v) {}
EXPORT int glfwGetWindowAttrib(void* w, int a) {
    switch (a) { case 0x20001: return 1; case 0x20004: return 1; case 0x2000B: return 1; default: return 0; }
}
EXPORT void* glfwGetWindowMonitor(void* w) { return NULL; }
EXPORT void glfwSetWindowMonitor(void* w, void* m, int x, int y, int ww, int h, int r) {}
EXPORT void glfwSetWindowUserPointer(void* w, void* p) { G.user = p; }
EXPORT void* glfwGetWindowUserPointer(void* w) { return G.user; }

// ---------- мониторы ----------
EXPORT void* glfwGetPrimaryMonitor(void) { return &G.mon; }
EXPORT void** glfwGetMonitors(int* n) { if (n) *n = 1; G.monlist[0] = &G.mon; return G.monlist; }
EXPORT void glfwGetMonitorPos(void* m, int* x, int* y) { if (x) *x = 0; if (y) *y = 0; }
EXPORT void glfwGetMonitorPhysicalSize(void* m, int* x, int* y) { if (x) *x = 0; if (y) *y = 0; }
EXPORT void glfwGetMonitorContentScale(void* m, float* x, float* y) { if (x) *x = 1.0f; if (y) *y = 1.0f; }
EXPORT const char* glfwGetMonitorName(void* m) { return "Android"; }
EXPORT const VidMode* glfwGetVideoMode(void* m) { return &G.vm; }
EXPORT const VidMode* glfwGetVideoModes(void* m, int* n) { if (n) *n = 1; return &G.vm; }
EXPORT void* glfwSetMonitorCallback(void* f) { return NULL; }

// ---------- ввод ----------
EXPORT void glfwSetInputMode(void* w, int mode, int v) { if (mode == 0x33001) G.cursor_mode = v; }
EXPORT int glfwGetInputMode(void* w, int mode) { return mode == 0x33001 ? G.cursor_mode : 0; }
EXPORT int glfwRawMouseMotionSupported(void) { return 0; }
EXPORT int glfwGetKey(void* w, int k) { return (k >= 0 && k < 512) ? G.keys[k] : 0; }
EXPORT int glfwGetMouseButton(void* w, int b) { return (b >= 0 && b < 16) ? G.mouse[b] : 0; }
EXPORT void glfwGetCursorPos(void* w, double* x, double* y) { if (x) *x = G.cx; if (y) *y = G.cy; }
EXPORT void glfwSetCursorPos(void* w, double x, double y) { G.cx = x; G.cy = y; }
EXPORT const char* glfwGetKeyName(int k, int s) { return NULL; }
EXPORT int glfwGetKeyScancode(int k) { return k; }
EXPORT void* glfwCreateCursor(const void* img, int x, int y) { return (void*) 1; }
EXPORT void* glfwCreateStandardCursor(int s) { return (void*) 1; }
EXPORT void glfwDestroyCursor(void* c) {}
EXPORT void glfwSetCursor(void* w, void* c) {}
EXPORT void glfwSetClipboardString(void* w, const char* s) { free(G.clip); G.clip = s ? strdup(s) : NULL; }
EXPORT const char* glfwGetClipboardString(void* w) { return G.clip ? G.clip : ""; }

// ---------- время ----------
EXPORT double glfwGetTime(void) { return now() + G.toff; }
EXPORT void glfwSetTime(double t) { G.toff = t - now(); }
EXPORT uint64_t glfwGetTimerValue(void) { return (uint64_t) (now() * 1e9); }
EXPORT uint64_t glfwGetTimerFrequency(void) { return 1000000000ULL; }

// ---------- прочее ----------
EXPORT int glfwJoystickPresent(int j) { return 0; }
EXPORT int glfwJoystickIsGamepad(int j) { return 0; }
EXPORT const char* glfwGetJoystickName(int j) { return NULL; }
EXPORT const char* glfwGetJoystickGUID(int j) { return NULL; }
EXPORT const float* glfwGetJoystickAxes(int j, int* n) { if (n) *n = 0; return NULL; }
EXPORT const unsigned char* glfwGetJoystickButtons(int j, int* n) { if (n) *n = 0; return NULL; }
EXPORT const unsigned char* glfwGetJoystickHats(int j, int* n) { if (n) *n = 0; return NULL; }
EXPORT const char* glfwGetGamepadName(int j) { return NULL; }
EXPORT int glfwGetGamepadState(int j, void* s) { return 0; }
EXPORT int glfwUpdateGamepadMappings(const char* s) { return 1; }
EXPORT void* glfwSetJoystickCallback(void* f) { return NULL; }
EXPORT int glfwVulkanSupported(void) { return 0; }
EXPORT const char** glfwGetRequiredInstanceExtensions(uint32_t* c) { if (c) *c = 0; return NULL; }

// ---------- дополнительные заглушки (нужны LWJGL 3.3.3 при загрузке) ----------
EXPORT void glfwInitAllocator(const void* a) { (void)a; }
EXPORT const void* glfwGetGammaRamp(void* m) { (void)m; return NULL; }
EXPORT void glfwSetGamma(void* m, float g) { (void)m; (void)g; }
EXPORT void glfwSetGammaRamp(void* m, const void* r) { (void)m; (void)r; }
EXPORT void* glfwGetInstanceProcAddress(void* i, const char* n) { (void)i; (void)n; return NULL; }
EXPORT void* glfwGetJoystickUserPointer(int j) { (void)j; return NULL; }
EXPORT void glfwSetJoystickUserPointer(int j, void* p) { (void)j; (void)p; }
static void* g_monptr;
EXPORT void* glfwGetMonitorUserPointer(void* m) { (void)m; return g_monptr; }
EXPORT void glfwSetMonitorUserPointer(void* m, void* p) { (void)m; g_monptr = p; }
EXPORT void glfwGetMonitorWorkarea(void* m, int* x, int* y, int* w, int* h) {
    (void)m; if (x) *x = 0; if (y) *y = 0; if (w) *w = G.w; if (h) *h = G.h;
}
EXPORT int glfwGetPhysicalDevicePresentationSupport(void* i, void* d, unsigned q) { (void)i; (void)d; (void)q; return 0; }
EXPORT int glfwCreateWindowSurface(void* i, void* w, const void* a, void* s) { (void)i; (void)w; (void)a; (void)s; return -7; }
EXPORT float glfwGetWindowOpacity(void* w) { (void)w; return 1.0f; }
EXPORT void glfwSetWindowOpacity(void* w, float o) { (void)w; (void)o; }
EXPORT const char* glfwGetWindowTitle(void* w) { (void)w; return "Minecraft"; }

EXPORT int smc_cursor_mode(void) { return G.cursor_mode; }
