// Отключает "маркировку указателей кучи" (heap tagging / TBI) внутри процесса Java.
// Без этого libjvm.so на Android 14+ падает: "Pointer tag ... was truncated".
// Подгружается через LD_PRELOAD.
#include <malloc.h>
#include <stdio.h>
#include <unistd.h>

#ifndef M_HEAP_TAGGING_LEVEL
#define M_HEAP_TAGGING_LEVEL (-204)
#endif
#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif

__attribute__((constructor(101)))
static void notag_init(void) {
    int rc = mallopt(M_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
    dprintf(2, "[notag] heap tagging off, mallopt=%d\n", rc);
}
