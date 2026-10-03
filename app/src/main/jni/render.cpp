#include <jni.h>
#include <vector>

#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"

extern "C" {
    jni_func(void, attachSurface, jobject surface_);
    jni_func(void, detachSurface);
};

static jobject surface;
static std::vector<jobject> retired_surfaces;
// mpv VO changes are queued. Keep every JNI handle alive until terminate_destroy
// has joined the VO thread, then release all handles together.
void release_surfaces(JNIEnv *env) {
    if(surface)env->DeleteGlobalRef(surface);
    surface=nullptr;
    for(auto ref:retired_surfaces)env->DeleteGlobalRef(ref);
    retired_surfaces.clear();
}

jni_func(void, attachSurface, jobject surface_) {
    CHECK_MPV_INIT();

    if(surface)retired_surfaces.push_back(surface);
    surface = env->NewGlobalRef(surface_);
    if (!surface)
        die("invalid surface provided");
    int64_t wid = reinterpret_cast<intptr_t>(surface);
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));
}

jni_func(void, detachSurface) {
    CHECK_MPV_INIT();

    int64_t wid = 0;
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));

    if(surface)env->DeleteGlobalRef(surface);
    surface = NULL;
}
