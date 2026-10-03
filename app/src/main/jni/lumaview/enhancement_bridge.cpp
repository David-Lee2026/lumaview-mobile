#include <jni.h>
#include <cstring>
#include <mpv/lvm_ext.h>
#include "../globals.h"
extern "C" JNIEXPORT jint JNICALL Java_org_lumaview_mobile_player_NativeStage_submit(JNIEnv *env,jobject,jfloatArray input,jlong media,jlong surface,jlong view,jlong request,jboolean reset){
 if(!g_mpv||env->GetArrayLength(input)!=9)return -1;
 jfloat v[9];env->GetFloatArrayRegion(input,0,9,v);
 lvm_frame_v1 f{};f.size=sizeof(f);f.version=1;f.media_generation=media;f.surface_generation=surface;f.view_revision=view;f.request_id=request;
 f.source_to_screen[0]=f.source_to_screen[4]=f.source_to_screen[8]=1;
 f.mode=(uint32_t)v[0];for(int i=0;i<6;i++)f.manual[i]=v[i+1];
 f.flags=(v[7]>.5?1:0)|(v[8]>.5?2:0);f.reset_reason=reset?1:0;
 return mpv_lvm_submit(g_mpv,&f);
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_lumaview_mobile_player_NativeStage_receipt(JNIEnv *env,jobject){
 if(!g_mpv)return nullptr;
 lvm_receipt_v1 r{};r.size=sizeof(r);
 if(mpv_lvm_poll_receipt(g_mpv,&r)!=0)return nullptr;
 jdouble v[]={double(r.media_generation),double(r.surface_generation),double(r.view_revision),double(r.request_id),double(r.pts_us),double(r.effective_mode),double(r.reason),double(r.lock_applied),r.actual_rect[0],r.actual_rect[1],r.actual_rect[2],r.actual_rect[3],double(r.resource_bytes),double(r.rendered_frames)};
 jdoubleArray out=env->NewDoubleArray(14);env->SetDoubleArrayRegion(out,0,14,v);return out;
}
