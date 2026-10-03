"""Apply the reviewed LumaView extension to the pinned mpv source tree."""
from pathlib import Path
import shutil,sys
root=Path(sys.argv[1] if len(sys.argv)>1 else 'buildscripts/deps/mpv')

def change(path,old,new):
 p=root/path;s=p.read_text()
 if new in s:return
 if old not in s:raise RuntimeError('anchor missing: '+str(p))
 p.write_text(s.replace(old,new,1))
def write(path,text):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
# The application requires GLES 3. An ES2 request can create an ES2 context on
# real drivers even when the emulator happens to return an ES3 context.
change('video/out/opengl/egl_helpers.c','rend = EGL_OPENGL_ES2_BIT;\n        name = "GLES 2.x +";','rend = EGL_OPENGL_ES3_BIT_KHR;\n        name = "GLES 3.x";')
change('video/out/opengl/egl_helpers.c','es ? EGL_CONTEXT_CLIENT_VERSION : EGL_NONE, 2,','es ? EGL_CONTEXT_CLIENT_VERSION : EGL_NONE, 3,')
write('include/mpv/lvm_ext.h',r'''/* LumaView private extension. Same ISC terms as the mpv client API. */
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <math.h>
#include "client.h"
struct lvm_frame_v1 {uint32_t size,version;uint64_t media_generation,surface_generation,view_revision,request_id;double source_to_screen[9],visible_rect[4];float context_fraction,context_weight;uint32_t mode,flags,reset_reason;float manual[6];uint32_t reserved[8];};
struct lvm_receipt_v1 {uint32_t size,version;uint64_t media_generation,surface_generation,view_revision,request_id;int64_t pts_us;uint32_t valid,effective_mode,reason,lock_applied;double actual_rect[4];uint64_t resource_bytes,rendered_frames;};
static inline int lvm_frame_valid(const struct lvm_frame_v1 *f) {
 if(!f||f->size!=sizeof(*f)||f->version!=1||f->mode>4)return 0;
 for(int i=0;i<8;i++)if(f->reserved[i])return 0;
 for(int i=0;i<6;i++)if(!isfinite(f->manual[i]))return 0;
 for(int i=0;i<9;i++)if(!isfinite(f->source_to_screen[i]))return 0;
 for(int i=0;i<4;i++)if(!isfinite(f->visible_rect[i]))return 0;
 return 1;
}
#ifdef __cplusplus
extern "C" {
#endif
MPV_EXPORT int mpv_lvm_submit(mpv_handle*,const struct lvm_frame_v1*);
MPV_EXPORT int mpv_lvm_poll_receipt(mpv_handle*,struct lvm_receipt_v1*);
#ifdef __cplusplus
}
#endif
''')
write('common/lvm_shared.h',r'''#pragma once
#include "osdep/threads.h"
#include "mpv/lvm_ext.h"
struct lvm_shared {mp_mutex lock;struct lvm_frame_v1 frame;struct lvm_receipt_v1 receipt;};
''')
change('common/global.h','    struct curl_ctx *curl;','    struct curl_ctx *curl;\n    struct lvm_shared *lvm;')
change('player/client.c','#include "common/global.h"','#include "common/global.h"\n#include "common/lvm_shared.h"\n#include "video/out/vo.h"')
change('player/client.c','mpv_handle *mpv_create(void)',r'''static void lvm_shared_destructor(void *ptr) {
 struct lvm_shared *s=ptr;mp_mutex_destroy(&s->lock);
}
int mpv_lvm_submit(mpv_handle *ctx,const struct lvm_frame_v1 *f) {
 if(!ctx||!lvm_frame_valid(f))return -1;
 struct lvm_shared *s=ctx->mpctx->global->lvm;if(!s)return -2;
 mp_mutex_lock(&s->lock);
 if(f->request_id<=s->frame.request_id){mp_mutex_unlock(&s->lock);return -3;}
 s->frame=*f;mp_mutex_unlock(&s->lock);
 lock_core(ctx);if(ctx->mpctx->video_out)vo_redraw(ctx->mpctx->video_out);unlock_core(ctx);return 0;
}
int mpv_lvm_poll_receipt(mpv_handle *ctx,struct lvm_receipt_v1 *r) {
 if(!ctx||!r||r->size!=sizeof(*r))return -1;
 struct lvm_shared *s=ctx->mpctx->global->lvm;if(!s)return -2;
 mp_mutex_lock(&s->lock);*r=s->receipt;mp_mutex_unlock(&s->lock);return r->valid?0:1;
}

mpv_handle *mpv_create(void)''')
change('player/client.c','    m_config_set_profile(mpctx->mconfig, "libmpv", 0);',r'''    struct lvm_shared *lvm=talloc_zero(mpctx->global,struct lvm_shared);
    mp_mutex_init(&lvm->lock);talloc_set_destructor(lvm,lvm_shared_destructor);
    lvm->frame.size=sizeof(lvm->frame);lvm->frame.version=1;
    mpctx->global->lvm=lvm;
    m_config_set_profile(mpctx->mconfig, "libmpv", 0);''')
change('meson.build',"headers = ['include/mpv/client.h', 'include/mpv/render.h',","headers = ['include/mpv/lvm_ext.h', 'include/mpv/client.h', 'include/mpv/render.h',")
change('video/out/gpu/video.c','#include "video.h"','#include "video.h"\n#include "common/global.h"\n#include "common/lvm_shared.h"')
change('video/out/gpu/video.c','    struct gl_lcms *cms;',r'''    struct gl_lcms *cms;
    struct lvm_frame_v1 lvm;
    struct ra_tex *lvm_history;
    bool lvm_history_valid, lvm_reset, lvm_hdr, lvm_applied, lvm_over_budget;
    double lvm_pts;
    float lvm_dt, lvm_roi_lo[2], lvm_roi_hi[2];
    int lvm_work_w, lvm_work_h;
    uint64_t lvm_rendered;''')
# Stable texture binding for the GPU-only previous exposure state.
change('video/out/gpu/video.c','        struct image bind_img;',r'''        if (strcmp(bind_name, "LVM_HISTORY") == 0 && p->lvm_history) {
            struct image prev=image_wrap(p->lvm_history,PLANE_RGB,4);
            hook_prelude(p,bind_name,pass_bind(p,prev),prev);
            continue;
        }
        struct image bind_img;''')
change('video/out/gpu/video.c','        bool is_overwrite = strcmp(store_name, name) == 0;',r'''        bool lvm_final=strcmp(store_name,"LVM_FINAL")==0;
        bool is_overwrite = strcmp(store_name, name) == 0 || lvm_final;''')
change('video/out/gpu/video.c','        struct ra_tex **tex = next_hook_tex(p);\n        finish_pass_tex(p, tex, w, h);',r'''        if(lvm_final) {
            p->lvm_applied=true;
            w=p->lvm_work_w;h=p->lvm_work_h;
            float rw=MPMAX(1,p->src_rect.x1-p->src_rect.x0);
            float rh=MPMAX(1,p->src_rect.y1-p->src_rect.y0);
            hook_off=(struct gl_transform){.m={{w/rw,0},{0,h/rh}},
              .t={-p->src_rect.x0*w/rw,-p->src_rect.y0*h/rh}};
        }
        struct ra_tex **tex = next_hook_tex(p);
        finish_pass_tex(p, tex, w, h);''')
change('video/out/gpu/video.c','        saved_img_store(p, store_name, saved_img);',r'''        saved_img_store(p, store_name, saved_img);
        if(strcmp(store_name,"LVM_EV")==0) {
            struct ra_tex *old=p->lvm_history;p->lvm_history=*tex;*tex=old;
            p->lvm_history_valid=true;
        }''')
# Width/height are dynamic without putting values into shader source.
change('video/out/gpu/video.c','    struct szexp_ctx *ctx = priv;\n\n    for (int i = 0; i < ctx->shader->num_params; i++)',r'''    struct szexp_ctx *ctx = priv;
    if(bstr_equals0(var,"LVM_WORK_W")){*out=ctx->p->lvm_work_w;return true;}
    if(bstr_equals0(var,"LVM_WORK_H")){*out=ctx->p->lvm_work_h;return true;}

    for (int i = 0; i < ctx->shader->num_params; i++)''')
change('video/out/gpu/video.c','    load_shader(p, shader->pass_body);',r'''    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_vec2(p->sc,"lvm_lo",p->lvm_roi_lo);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_vec2(p->sc,"lvm_hi",p->lvm_roi_hi);
    float cap[]={0,2,3,4,2};int mode=MPCLAMP(p->lvm.mode,0,4);
    bool bypass=(p->lvm.flags&1)||p->lvm_hdr||mode==0;
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_cap",cap[mode]);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_manual",MPCLAMP(p->lvm.manual[0],-1,1));
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_shadows",MPCLAMP(p->lvm.manual[1],0,100)/100);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_contrast",MPCLAMP(p->lvm.manual[2],-25,25)/100);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_saturation",MPCLAMP(p->lvm.manual[3],0,150)/100);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_denoise",mode==4?0:MPCLAMP(p->lvm.manual[4],0,100)/100);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_detail",mode==4?0:MPCLAMP(p->lvm.manual[5],0,30)/100);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_bypass",bypass?1:0);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_lock",(p->lvm.flags&2)?1:0);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_reset",p->lvm_reset?1:0);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_dt",p->lvm_dt);
    gl_sc_uniform_dynamic(p->sc);gl_sc_uniform_f(p->sc,"lvm_gamma",p->image_params.color.transfer==PL_COLOR_TRC_BT_1886?2.4:2.2);
    load_shader(p, shader->pass_body);''')
# Prepare source rectangle from the renderer, so statistics and display cannot diverge.
change('video/out/gpu/video.c','    pass_opt_hook_point(p, "MAIN", &p->texture_offset);',r'''    p->lvm_applied=false;
    if(p->global->lvm) {
        int fw=p->texture_w,fh=p->texture_h;
        float lo[2]={(float)p->src_rect.x0/fw,(float)p->src_rect.y0/fh};
        float hi[2]={(float)p->src_rect.x1/fw,(float)p->src_rect.y1/fh};
        if(memcmp(lo,p->lvm_roi_lo,sizeof(lo))||memcmp(hi,p->lvm_roi_hi,sizeof(hi)))p->lvm_reset=true;
        memcpy(p->lvm_roi_lo,lo,sizeof(lo));memcpy(p->lvm_roi_hi,hi,sizeof(hi));
        int rw=MPMAX(1,p->src_rect.x1-p->src_rect.x0),rh=MPMAX(1,p->src_rect.y1-p->src_rect.y0);
        double scale=MPMIN(1.0,1920.0/MPMAX(rw,rh));scale=MPMIN(scale,sqrt(2073600.0/((double)rw*rh)));
        p->lvm_work_w=MPMAX(1,lrint(rw*scale));p->lvm_work_h=MPMAX(1,lrint(rh*scale));
        p->lvm_hdr=pl_color_space_is_hdr(&p->image_params.color)||pl_color_space_is_hdr(&p->real_image_params.color);
        double pts=mpi->pts;
        p->lvm_dt=pts==MP_NOPTS_VALUE?0:MPCLAMP(pts-p->lvm_pts,0,1);
        if(pts==MP_NOPTS_VALUE||pts<p->lvm_pts||pts-p->lvm_pts>1)p->lvm_reset=true;
        p->lvm_pts=pts;
        if(!p->lvm_history) {
            unsigned char zero[4]={0};
            struct ra_tex_params par={.dimensions=2,.w=1,.h=1,.d=1,.format=ra_find_unorm_format(p->ra,1,4),.render_src=true,.render_dst=true,.src_linear=false,.initial_data=zero};
            p->lvm_history=ra_tex_create(p->ra,&par);
        }
        if(!p->lvm_history_valid)p->lvm_reset=true;
    }
    p->lvm_over_budget=(uint64_t)p->texture_w*p->texture_h>2073600;
    if(p->lvm.mode>0 && !(p->lvm.flags&1) && !p->lvm_hdr && !p->lvm_over_budget)
        pass_opt_hook_point(p, "MAIN", &p->texture_offset);
    p->lvm_reset=false;''')
change('video/out/gpu/video.c','    gl_video_update_options(p);\n\n    struct mp_rect target_rc',r'''    gl_video_update_options(p);
    if(p->global->lvm) {
        struct lvm_shared *s=p->global->lvm;mp_mutex_lock(&s->lock);
        if(s->frame.request_id!=p->lvm.request_id) {
            if(s->frame.reset_reason||s->frame.media_generation!=p->lvm.media_generation||s->frame.surface_generation!=p->lvm.surface_generation)p->lvm_reset=true;
            p->lvm=s->frame;p->output_tex_valid=false;
        }
        mp_mutex_unlock(&s->lock);
    }

    struct mp_rect target_rc''')
# Emit receipt only from the actual render function after a real video frame.
change('video/out/gpu/video.c','    p->broken_frame = false;', '    p->broken_frame = false;')
# Find end of render function by the following gl_video_screenshot symbol.
s=(root/'video/out/gpu/video.c').read_text();a=s.index('void gl_video_render_frame(');b=s.index('\nvoid gl_video_screenshot',a)
part=s[a:b];needle='done:'
if 'lvm_receipt_v1 result=' not in part:
 idx=s.index(needle,a)+len(needle)
 block=r'''
    if(p->global->lvm&&frame->current) {
        struct lvm_receipt_v1 result={.size=sizeof(result),.version=1,
          .media_generation=p->lvm.media_generation,.surface_generation=p->lvm.surface_generation,
          .view_revision=p->lvm.view_revision,.request_id=p->lvm.request_id,
          .pts_us=frame->current->pts==MP_NOPTS_VALUE?0:llrint(frame->current->pts*1e6),
          .valid=1,
          .effective_mode=((p->lvm.flags&1)||p->lvm_hdr||!p->lvm_applied||p->broken_frame||gl_sc_error_state(p->sc))?0:p->lvm.mode,
          .reason=(p->broken_frame||gl_sc_error_state(p->sc))?2:(p->lvm_hdr?1:(p->lvm_over_budget?3:((p->lvm.mode==0||(p->lvm.flags&1))?0:(!p->lvm_applied?2:0)))),
          .lock_applied=(p->lvm.flags&2)&&p->lvm_history_valid,
          .actual_rect={p->src_rect.x0,p->src_rect.y0,p->src_rect.x1,p->src_rect.y1},
          .resource_bytes=0, /* unavailable: not a measured allocator counter */
          .rendered_frames=++p->lvm_rendered};
        struct lvm_shared *shared=p->global->lvm;mp_mutex_lock(&shared->lock);
        shared->receipt=result;mp_mutex_unlock(&shared->lock);
    }
'''
 s=s[:idx]+block+s[idx:];(root/'video/out/gpu/video.c').write_text(s)
change('video/out/gpu/video.c','    uninit_video(p);\n    ra_hwdec_ctx_uninit', '    ra_tex_free(p->ra, &p->lvm_history);\n    uninit_video(p);\n    ra_hwdec_ctx_uninit')
print('LumaView native extension applied:',root)
