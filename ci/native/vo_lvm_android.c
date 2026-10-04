/* Software Android presentation for LumaView. ISC license. */
#include <android/native_window.h>
#include <math.h>
#include <string.h>
#include <libswscale/swscale.h>
#include "common/global.h"
#include "common/lvm_shared.h"
#include "common/msg.h"
#include "video/mp_image.h"
#include "video/sws_utils.h"
#include "sub/osd.h"
#include "vo.h"
#include "android_common.h"
#include "lvm_cpu.h"

struct priv {
 struct mp_sws_context *sws;
 struct mp_image *scaled,*display;
 struct mp_rect src,dst;
 struct mp_osd_res osd;
 struct lvm_cpu_state exposure;
 struct lvm_frame_v1 settings;
 struct lvm_receipt_v1 pending;
 int screen_w,screen_h;
 uint64_t posted;
 bool ready;
};
static void output_error(struct vo *vo,const char *operation,int code){
 struct priv *p=vo->priv;MP_ERR(vo,"LVM software output %s failed (%d)\n",operation,code);
 if(vo->global->lvm){struct lvm_shared *s=vo->global->lvm;mp_mutex_lock(&s->lock);
  s->receipt=p->pending;s->receipt.valid=1;s->receipt.reason=4;s->receipt.effective_mode=0;mp_mutex_unlock(&s->lock);}
}
static int resize(struct vo *vo){
 struct priv *p=vo->priv;int w,h;
 if(!vo_android_surface_size(vo,&w,&h))return -1;
 double factor=fmin(1.,960./fmax(w,h));int bw=MPMAX(1,lrint(w*factor)),bh=MPMAX(1,lrint(h*factor));
 if(!p->display||p->display->w!=bw||p->display->h!=bh){
  int rc=ANativeWindow_setBuffersGeometry(vo_android_native_window(vo),bw,bh,WINDOW_FORMAT_RGBA_8888);
  if(rc){output_error(vo,"geometry",rc);return -1;}
  mp_image_unrefp(&p->display);p->display=mp_image_alloc(IMGFMT_RGBA,bw,bh);if(!p->display)return -1;
  MP_INFO(vo,"LVM software output: Surface %dx%d, RGBA buffer %dx%d, no EGL\n",w,h,bw,bh);
 }
 p->screen_w=w;p->screen_h=h;vo->dwidth=bw;vo->dheight=bh;
 vo_get_src_dst_rects(vo,&p->src,&p->dst,&p->osd);return 0;
}
static int preinit(struct vo *vo){
 struct priv *p=vo->priv;if(!vo_android_init(vo))return -1;
 p->sws=mp_sws_alloc(vo);p->sws->log=vo->log;p->sws->flags=SWS_BILINEAR;
 return 0;
}
static int query_format(struct vo *vo,int format){return mp_sws_supported_format(format);}
static int reconfig(struct vo *vo,struct mp_image_params *params){
 struct priv *p=vo->priv;p->exposure.valid=false;return resize(vo);
}
static bool draw_frame(struct vo *vo,struct vo_frame *frame){
 struct priv *p=vo->priv;struct mp_image *image=frame->current;p->ready=false;
 if(!image||image->params.force_window)return false;
 int w,h;if(!vo_android_surface_size(vo,&w,&h))return false;
 if(w!=p->screen_w||h!=p->screen_h||!p->display)if(resize(vo)<0)return false;
 bool reset=!p->exposure.valid;
 struct lvm_shared *shared=vo->global->lvm;
 if(shared){mp_mutex_lock(&shared->lock);struct lvm_frame_v1 next=shared->frame;mp_mutex_unlock(&shared->lock);
  if(next.request_id!=p->settings.request_id){reset|=next.reset_reason||next.media_generation!=p->settings.media_generation||next.surface_generation!=p->settings.surface_generation||next.view_revision!=p->settings.view_revision;p->settings=next;}}
 struct mp_rect crop=p->src;
 int rotation=((image->params.rotate%360)+360)%360;
 mp_rect_rotate(&crop,image->w,image->h,(360-rotation)%360);
 if(image->params.vflip){int top=image->h-crop.y1;crop.y1=image->h-crop.y0;crop.y0=top;}
 crop.x0=MP_ALIGN_DOWN(MPCLAMP(crop.x0,0,image->w-1),image->fmt.align_x);
 crop.y0=MP_ALIGN_DOWN(MPCLAMP(crop.y0,0,image->h-1),image->fmt.align_y);
 crop.x1=MPCLAMP(crop.x1,crop.x0+1,image->w);crop.y1=MPCLAMP(crop.y1,crop.y0+1,image->h);
 int dw=p->dst.x1-p->dst.x0,dh=p->dst.y1-p->dst.y0;
 if(dw<=0||dh<=0)return false;
 int sw=rotation%180==90?dh:dw,sh=rotation%180==90?dw:dh;
 if(!p->scaled||p->scaled->w!=sw||p->scaled->h!=sh){mp_image_unrefp(&p->scaled);p->scaled=mp_image_alloc(IMGFMT_RGBA,sw,sh);if(!p->scaled)return false;}
 struct mp_image source=*image;mp_image_crop_rc(&source,crop);
 if(mp_sws_scale(p->sws,p->scaled,&source)<0){output_error(vo,"pixel conversion",-1);return false;}
 double pts=image->pts==MP_NOPTS_VALUE?0:image->pts;
 bool hdr=pl_color_space_is_hdr(&image->params.color);
 struct lvm_frame_v1 *f=&p->settings;
 struct lvm_cpu_controls controls={.mode=f->mode,.bypass=(f->flags&1)||hdr,.locked=f->flags&2,
  .manual=f->manual[0],.shadows=f->manual[1]/100.,.contrast=f->manual[2]/100.,.saturation=f->manual[3]/100.,.denoise=f->manual[4]/100.,.detail=f->manual[5]/100.};
 p->pending=(struct lvm_receipt_v1){.size=sizeof(p->pending),.version=1,.media_generation=f->media_generation,.surface_generation=f->surface_generation,.view_revision=f->view_revision,.request_id=f->request_id,.pts_us=llrint(pts*1e6),.valid=1,.effective_mode=controls.bypass?0:f->mode,.reason=hdr?1:0,.lock_applied=controls.locked&&p->exposure.valid,.actual_rect={crop.x0,crop.y0,crop.x1,crop.y1},.resource_bytes=(uint64_t)(p->display->stride[0]*p->display->h+p->scaled->stride[0]*p->scaled->h)};
 int rc=lvm_cpu_apply(p->scaled->planes[0],sw,sh,p->scaled->stride[0],&controls,&p->exposure,pts,reset);
 if(rc){output_error(vo,"enhancement",rc);return false;}
 // Clear borders with opaque alpha. Never reuse an Android compositor buffer.
 for(int y=0;y<p->display->h;y++){uint8_t *row=p->display->planes[0]+y*p->display->stride[0];for(int x=0;x<p->display->w;x++){row[x*4]=row[x*4+1]=row[x*4+2]=0;row[x*4+3]=255;}}
 for(int y=0;y<dh;y++)for(int x=0;x<dw;x++){
  int sx,sy;lvm_cpu_source_point(x,y,sw,sh,rotation,image->params.vflip,&sx,&sy);
  memcpy(p->display->planes[0]+(y+p->dst.y0)*p->display->stride[0]+(x+p->dst.x0)*4,p->scaled->planes[0]+sy*p->scaled->stride[0]+sx*4,4);
 }
 p->display->pts=pts;osd_draw_on_image(vo->osd,p->osd,pts,0,p->display);
 p->ready=true;return true;
}
static void flip_page(struct vo *vo){
 struct priv *p=vo->priv;if(!p->ready)return;
 ANativeWindow_Buffer buffer;ANativeWindow *window=vo_android_native_window(vo);
 int rc=ANativeWindow_lock(window,&buffer,NULL);if(rc){output_error(vo,"lock",rc);return;}
 bool valid=buffer.bits&&buffer.format==WINDOW_FORMAT_RGBA_8888&&buffer.width==p->display->w&&buffer.height==p->display->h&&buffer.stride>=buffer.width;
 if(valid)for(int y=0;y<buffer.height;y++)memcpy((uint8_t *)buffer.bits+y*buffer.stride*4,p->display->planes[0]+y*p->display->stride[0],buffer.width*4);
 rc=ANativeWindow_unlockAndPost(window);
 if(!valid||rc){output_error(vo,"post",rc?rc:-1);return;}
 p->pending.rendered_frames=++p->posted;
 if(p->posted==1)MP_INFO(vo,"LVM software output first buffer posted successfully\n");
 if(vo->global->lvm){struct lvm_shared *s=vo->global->lvm;mp_mutex_lock(&s->lock);s->receipt=p->pending;mp_mutex_unlock(&s->lock);}
}
static int control(struct vo *vo,uint32_t request,void *data){
 struct priv *p=vo->priv;
 switch(request){
 case VOCTRL_SET_PANSCAN:case VOCTRL_EXTERNAL_RESIZE:if(resize(vo)<0)return VO_FALSE;vo->want_redraw=true;return VO_TRUE;
 case VOCTRL_RESET:p->exposure.valid=false;return VO_TRUE;
 case VOCTRL_SCREENSHOT:{struct voctrl_screenshot *args=data;if(args->scaled&&p->display){args->res=mp_image_new_copy(p->display);return VO_TRUE;}return VO_NOTIMPL;}
 }
 return VO_NOTIMPL;
}
static void uninit(struct vo *vo){struct priv *p=vo->priv;mp_image_unrefp(&p->scaled);mp_image_unrefp(&p->display);vo_android_uninit(vo);}
const struct vo_driver video_out_lvm_android={.description="LumaView Android software compatibility output",.name="lvm-android",.caps=VO_CAP_ROTATE90|VO_CAP_VFLIP,.preinit=preinit,.query_format=query_format,.reconfig=reconfig,.control=control,.draw_frame=draw_frame,.flip_page=flip_page,.uninit=uninit,.priv_size=sizeof(struct priv)};
