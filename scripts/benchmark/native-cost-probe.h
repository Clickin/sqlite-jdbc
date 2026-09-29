/* Measurement-only JNI probes. Never include in the production native library.
 * One start per JVM; stop rejects new entries and drains admitted scopes.
 * Counters have one OS-thread writer; no per-call global atomic RMW.
 */
#ifndef SQLITE_JDBC_NATIVE_COST_PROBE_H
#define SQLITE_JDBC_NATIVE_COST_PROBE_H
#include <jni.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    _Atomic uint64_t calls, samples, wall_ns, cpu_ns;
} cp_counter;
typedef struct __attribute__((aligned(64))) cp_shard {
    struct cp_shard *next;
    unsigned ordinal;
    _Atomic unsigned active;
    cp_counter counters[CP_CATEGORY_COUNT];
} cp_shard;
typedef struct {
    cp_counter *counter;
    uint64_t wall_start, cpu_start;
    int entered;
} cp_scope;

static _Atomic int cp_mode;
static _Atomic unsigned cp_clock_errors;
static pthread_mutex_t cp_registry_lock = PTHREAD_MUTEX_INITIALIZER;
static cp_shard *cp_shards;
static _Thread_local cp_shard *cp_local;
static _Thread_local unsigned cp_depth;
static unsigned cp_shard_count;
static int cp_running, cp_started_mode, cp_ever_started;
static uint64_t cp_calibration_wall, cp_calibration_cpu;
static const char *cp_names[] = CP_NAMES;
static const int cp_present[] = CP_PRESENT;
#define CP_CALIBRATION_ITERATIONS 4096

static uint64_t cp_clock(clockid_t clock) {
    struct timespec value;
    if (clock_gettime(clock, &value) != 0) {
        atomic_fetch_add_explicit(&cp_clock_errors, 1, memory_order_relaxed);
        return 0;
    }
    return (uint64_t)value.tv_sec * UINT64_C(1000000000) + (uint64_t)value.tv_nsec;
}

/* Exactly one OS thread writes each counter; avoid locked fetch-add instructions. */
static uint64_t cp_add(_Atomic uint64_t *counter, uint64_t delta) {
    uint64_t value = atomic_load_explicit(counter, memory_order_relaxed) + delta;
    atomic_store_explicit(counter, value, memory_order_relaxed);
    return value;
}

static void cp_leave(void) {
    if (--cp_depth == 0)
        atomic_store_explicit(&cp_local->active, 0, memory_order_seq_cst);
}

static cp_scope cp_begin(unsigned category) {
    cp_scope scope = {0};
    int mode = atomic_load_explicit(&cp_mode, memory_order_seq_cst);
    if (mode == 0) return scope;
    int saved_errno = errno;
    if (!cp_local) {
        /* Once per carrier/OS thread; cache-line separated and retained for
         * post-exit counts. No allocator or global RMW on the steady-state path. */
        cp_shard *shard = aligned_alloc(64, sizeof(*shard));
        if (!shard) abort(); /* Never turn incomplete measurements into valid data. */
        memset(shard, 0, sizeof(*shard));
        atomic_init(&shard->active, 0);
        for (unsigned i = 0; i < CP_CATEGORY_COUNT; i++) {
            atomic_init(&shard->counters[i].calls, 0);
            atomic_init(&shard->counters[i].samples, 0);
            atomic_init(&shard->counters[i].wall_ns, 0);
            atomic_init(&shard->counters[i].cpu_ns, 0);
        }
        pthread_mutex_lock(&cp_registry_lock);
        if (atomic_load_explicit(&cp_mode, memory_order_seq_cst) == 0) {
            pthread_mutex_unlock(&cp_registry_lock);
            free(shard);
            errno = saved_errno;
            return scope;
        }
        shard->ordinal = cp_shard_count++;
        shard->next = cp_shards;
        cp_shards = shard;
        pthread_mutex_unlock(&cp_registry_lock);
        cp_local = shard;
    }
    /* Sequential consistency orders publication/recheck against stop's mode
     * store and active reads. A late publication cannot write any counters. */
    if (cp_depth++ == 0)
        atomic_store_explicit(&cp_local->active, 1, memory_order_seq_cst);
    mode = atomic_load_explicit(&cp_mode, memory_order_seq_cst);
    if (mode == 0) {
        cp_leave();
        errno = saved_errno;
        return scope;
    }
    scope.entered = 1;
    cp_counter *counter = &cp_local->counters[category];
    uint64_t calls = cp_add(&counter->calls, 1);
    /* Separate deterministic phases avoid systematically sampling nested calls
     * together. Each category/thread still samples exactly one call per 256. */
    unsigned phase = (cp_local->ordinal * 73u + category * 151u) & 255u;
    if (mode == 2 && ((calls + phase) & 255u) == 0) {
        scope.counter = counter;
        scope.wall_start = cp_clock(CLOCK_MONOTONIC);
        scope.cpu_start = cp_clock(CLOCK_THREAD_CPUTIME_ID);
    }
    errno = saved_errno;
    return scope;
}

static void cp_end(cp_scope *scope) {
    if (!scope->entered) return;
    int saved_errno = errno;
    if (scope->counter) {
        uint64_t cpu_end = cp_clock(CLOCK_THREAD_CPUTIME_ID);
        uint64_t wall_end = cp_clock(CLOCK_MONOTONIC);
        cp_add(&scope->counter->samples, 1);
        cp_add(&scope->counter->cpu_ns, cpu_end - scope->cpu_start);
        cp_add(&scope->counter->wall_ns, wall_end - scope->wall_start);
    }
    cp_leave();
    errno = saved_errno;
}

#define CP_SCOPE(category) \
    cp_scope cp_measurement __attribute__((cleanup(cp_end))) = cp_begin(category)

static void cp_throw(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

JNIEXPORT void JNICALL Java_org_sqlite_core_CostProbe_start0(
        JNIEnv *env, jclass type, jint mode) {
    (void)type;
    if (cp_ever_started || mode < 0 || mode > 2) {
        cp_throw(env, "CostProbe permits one start per JVM and mode 0, 1 or 2");
        return;
    }
    cp_ever_started = 1;
    atomic_store_explicit(&cp_clock_errors, 0, memory_order_relaxed);
    cp_calibration_wall = cp_calibration_cpu = 0;
    for (unsigned i = 0; i < CP_CALIBRATION_ITERATIONS; i++) {
        uint64_t wall_start = cp_clock(CLOCK_MONOTONIC);
        uint64_t cpu_start = cp_clock(CLOCK_THREAD_CPUTIME_ID);
        uint64_t cpu_end = cp_clock(CLOCK_THREAD_CPUTIME_ID);
        uint64_t wall_end = cp_clock(CLOCK_MONOTONIC);
        cp_calibration_cpu += cpu_end - cpu_start;
        cp_calibration_wall += wall_end - wall_start;
    }
    if (atomic_load_explicit(&cp_clock_errors, memory_order_relaxed)) {
        cp_throw(env, "CostProbe Linux clock calibration failed");
        return;
    }
    cp_started_mode = mode;
    cp_running = 1;
    atomic_store_explicit(&cp_mode, mode, memory_order_seq_cst);
}

JNIEXPORT jobjectArray JNICALL Java_org_sqlite_core_CostProbe_names0(
        JNIEnv *env, jclass type) {
    (void)type;
    jclass string_type = (*env)->FindClass(env, "java/lang/String");
    if (!string_type) return NULL;
    jobjectArray names = (*env)->NewObjectArray(env, CP_CATEGORY_COUNT, string_type, NULL);
    if (!names) return NULL;
    for (unsigned i = 0; i < CP_CATEGORY_COUNT; i++) {
        jstring name = (*env)->NewStringUTF(env, cp_names[i]);
        if (!name) return NULL;
        (*env)->SetObjectArrayElement(env, names, i, name);
        (*env)->DeleteLocalRef(env, name);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    return names;
}

JNIEXPORT jlongArray JNICALL Java_org_sqlite_core_CostProbe_stop0(
        JNIEnv *env, jclass type) {
    (void)type;
    if (!cp_running) {
        cp_throw(env, "CostProbe is not running");
        return NULL;
    }
    /* Stop admission first; include complete costs of all previously admitted
     * scopes, including background SQL. Never reset live counter fields. */
    atomic_store_explicit(&cp_mode, 0, memory_order_seq_cst);
    cp_running = 0;
    jlong values[5 + CP_CATEGORY_COUNT * 5] = {0};
    values[0] = cp_started_mode;
    values[2] = CP_CALIBRATION_ITERATIONS;
    values[3] = (jlong)cp_calibration_wall;
    values[4] = (jlong)cp_calibration_cpu;
    pthread_mutex_lock(&cp_registry_lock);
    uint64_t deadline = cp_clock(CLOCK_MONOTONIC) + UINT64_C(5000000000);
    for (;;) {
        int active = 0;
        for (cp_shard *shard = cp_shards; shard; shard = shard->next)
            active |= atomic_load_explicit(&shard->active, memory_order_seq_cst) != 0;
        if (!active) break;
        if (atomic_load_explicit(&cp_clock_errors, memory_order_relaxed)
                || cp_clock(CLOCK_MONOTONIC) >= deadline) {
            pthread_mutex_unlock(&cp_registry_lock);
            cp_throw(env, "CostProbe could not drain admitted scopes within 5 seconds; results invalid");
            return NULL;
        }
        struct timespec pause = {0, 1000000};
        nanosleep(&pause, NULL);
    }
    if (atomic_load_explicit(&cp_clock_errors, memory_order_relaxed)) {
        pthread_mutex_unlock(&cp_registry_lock);
        cp_throw(env, "CostProbe clock failed during measurement; results invalid");
        return NULL;
    }
    values[1] = cp_shard_count;
    for (unsigned i = 0; i < CP_CATEGORY_COUNT; i++) {
        unsigned offset = 5 + i * 5;
        values[offset] = cp_present[i];
        for (cp_shard *shard = cp_shards; shard; shard = shard->next) {
            cp_counter *counter = &shard->counters[i];
            values[offset + 1] += (jlong)atomic_load_explicit(&counter->calls, memory_order_relaxed);
            values[offset + 2] += (jlong)atomic_load_explicit(&counter->samples, memory_order_relaxed);
            values[offset + 3] += (jlong)atomic_load_explicit(&counter->wall_ns, memory_order_relaxed);
            values[offset + 4] += (jlong)atomic_load_explicit(&counter->cpu_ns, memory_order_relaxed);
        }
    }
    pthread_mutex_unlock(&cp_registry_lock);
    jsize size = (jsize)(sizeof(values) / sizeof(values[0]));
    jlongArray result = (*env)->NewLongArray(env, size);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, size, values);
    return result;
}
#endif
