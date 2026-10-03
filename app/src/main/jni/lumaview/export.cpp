#include <jni.h>
#include <atomic>
#include <sstream>
#include <string>
#include <algorithm>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/error.h>
}

static std::atomic<int64_t> generation(0);
static bool cancelled(int64_t token){return generation.load()!=token;}
struct Input {
    AVFormatContext *ctx=nullptr;
    ~Input(){avformat_close_input(&ctx);}
    int open(const char *path){int r=avformat_open_input(&ctx,path,nullptr,nullptr);return r<0?r:avformat_find_stream_info(ctx,nullptr);}
};
static jstring result(JNIEnv *e,const std::string &s){return e->NewStringUTF(s.c_str());}
static std::string error(int n){char b[128];av_strerror(n,b,sizeof(b));return std::string("{\"error\":\"")+b+"\"}";}
static bool idr(const AVPacket *p,const AVCodecParameters *c){
    if(c->codec_id!=AV_CODEC_ID_H264)return false;
    int size=(c->extradata_size>4&&c->extradata[0]==1)?(c->extradata[4]&3)+1:0;
    if(size){int pos=0;while(pos+size<=p->size){unsigned n=0;for(int k=0;k<size;k++)n=(n<<8)|p->data[pos++];if(!n||n>(unsigned)(p->size-pos))return false;if((p->data[pos]&31)==5)return true;pos+=(int)n;}}
    else for(int i=0;i+4<p->size;i++)if(p->data[i]==0&&p->data[i+1]==0){int j=p->data[i+2]==1?i+3:(p->data[i+2]==0&&p->data[i+3]==1?i+4:-1);if(j>=0&&(p->data[j]&31)==5)return true;}
    return false;
}
static int video(Input &in,int index){if(index<0)return av_find_best_stream(in.ctx,AVMEDIA_TYPE_VIDEO,-1,-1,nullptr,0);return index<(int)in.ctx->nb_streams&&in.ctx->streams[index]->codecpar->codec_type==AVMEDIA_TYPE_VIDEO?index:AVERROR(EINVAL);}
static int64_t us(AVStream *s,int64_t pts){return pts==AV_NOPTS_VALUE?AV_NOPTS_VALUE:av_rescale_q(pts,s->time_base,AV_TIME_BASE_Q);}
static int64_t origin(AVFormatContext *c){return c->start_time==AV_NOPTS_VALUE?0:c->start_time;}
static int64_t findStart(Input &in,int vi,int64_t a,int64_t token){
    AVPacket *p=av_packet_alloc();int64_t found=AV_NOPTS_VALUE;int64_t limit=a+origin(in.ctx);
    while(!cancelled(token)&&av_read_frame(in.ctx,p)>=0){
        if(p->stream_index==vi){int64_t t=us(in.ctx->streams[vi],p->pts);if(t!=AV_NOPTS_VALUE&&t>limit){av_packet_unref(p);break;}if(t<=limit&&t!=AV_NOPTS_VALUE&&(p->flags&AV_PKT_FLAG_KEY)&&idr(p,in.ctx->streams[vi]->codecpar))found=t;}
        av_packet_unref(p);
    }
    av_packet_free(&p);return found;
}
// Extend copy end to the next IDR: preserve every decode-order dependency in the GOP.
static int64_t findEnd(Input &in,int vi,int64_t b,int64_t token){
    AVPacket *p=av_packet_alloc();int64_t limit=b+origin(in.ctx),last=AV_NOPTS_VALUE;
    while(!cancelled(token)&&av_read_frame(in.ctx,p)>=0){
        if(p->stream_index==vi){auto *st=in.ctx->streams[vi];int64_t t=us(st,p->pts);
            if(t!=AV_NOPTS_VALUE){last=std::max(last,t+std::max<int64_t>(0,us(st,p->duration)));
                if(t>=limit&&(p->flags&AV_PKT_FLAG_KEY)&&idr(p,st->codecpar)){av_packet_free(&p);return t;}}}
        av_packet_unref(p);
    }
    av_packet_free(&p);return last; // complete final GOP at EOF
}
extern "C" JNIEXPORT jlong JNICALL Java_org_lumaview_mobile_NativeExport_begin(JNIEnv*,jobject){return ++generation;}
extern "C" JNIEXPORT jstring JNICALL Java_org_lumaview_mobile_NativeExport_probe(JNIEnv *e,jobject,jstring jp,jlong a,jlong b,jint idx,jlong token){
    if(cancelled(token))return result(e,error(AVERROR_EXIT));const char *path=e->GetStringUTFChars(jp,nullptr);Input in;int r=in.open(path);e->ReleaseStringUTFChars(jp,path);if(r<0)return result(e,error(r));
    int vi=video(in,idx);if(vi<0)return result(e,error(vi));int64_t at=findStart(in,vi,a,token);
    r=av_seek_frame(in.ctx,vi,av_rescale_q(origin(in.ctx),AV_TIME_BASE_Q,in.ctx->streams[vi]->time_base),AVSEEK_FLAG_BACKWARD);if(r<0)return result(e,error(r));
    int64_t end=findEnd(in,vi,b,token);if(cancelled(token))return result(e,error(AVERROR_EXIT));
    std::ostringstream out;out<<"{\"videoIndex\":"<<vi<<",\"startUs\":"<<(at==AV_NOPTS_VALUE?-1:at-origin(in.ctx))<<",\"endUs\":"<<(end==AV_NOPTS_VALUE?-1:end-origin(in.ctx))<<",\"tracks\":[";
    for(unsigned i=0;i<in.ctx->nb_streams;i++){if(i)out<<",";auto *c=in.ctx->streams[i]->codecpar;out<<"{\"index\":"<<i<<",\"type\":\""<<(c->codec_type==AVMEDIA_TYPE_VIDEO?"video":c->codec_type==AVMEDIA_TYPE_AUDIO?"audio":"other")<<"\",\"codec\":\""<<avcodec_get_name(c->codec_id)<<"\"}";}
    out<<"]}";return result(e,out.str());
}
extern "C" JNIEXPORT void JNICALL Java_org_lumaview_mobile_NativeExport_cancel(JNIEnv*,jobject){++generation;}
extern "C" JNIEXPORT jstring JNICALL Java_org_lumaview_mobile_NativeExport_remux(JNIEnv *e,jobject,jstring jp,jstring jo,jlong a,jlong b,jint idx,jint ai,jlong token){
    if(cancelled(token))return result(e,error(AVERROR_EXIT));if(a<0||b<=a)return result(e,error(AVERROR(EINVAL)));
    const char *path=e->GetStringUTFChars(jp,nullptr);Input in;int r=in.open(path);e->ReleaseStringUTFChars(jp,path);if(r<0)return result(e,error(r));
    int vi=video(in,idx);if(vi<0)return result(e,error(vi));
    if(ai>=0&&(ai>=(int)in.ctx->nb_streams||in.ctx->streams[ai]->codecpar->codec_type!=AVMEDIA_TYPE_AUDIO))return result(e,error(AVERROR(EINVAL)));
    int64_t base=findStart(in,vi,a,token);if(base==AV_NOPTS_VALUE)return result(e,"{\"error\":\"No provable H.264 IDR start; use precise export\"}");
    r=av_seek_frame(in.ctx,vi,av_rescale_q(origin(in.ctx),AV_TIME_BASE_Q,in.ctx->streams[vi]->time_base),AVSEEK_FLAG_BACKWARD);if(r<0)return result(e,error(r));
    int64_t end=findEnd(in,vi,b,token);if(cancelled(token))return result(e,error(AVERROR_EXIT));if(end==AV_NOPTS_VALUE||end<=base)return result(e,error(AVERROR_INVALIDDATA));
    r=av_seek_frame(in.ctx,vi,av_rescale_q(base,AV_TIME_BASE_Q,in.ctx->streams[vi]->time_base),AVSEEK_FLAG_BACKWARD);if(r<0)return result(e,error(r));
    const char *output=e->GetStringUTFChars(jo,nullptr);AVFormatContext *out=nullptr;r=avformat_alloc_output_context2(&out,nullptr,nullptr,output);
    if(r<0||!out){e->ReleaseStringUTFChars(jo,output);return result(e,error(r<0?r:AVERROR(EINVAL)));}
    int mapping[2]={vi,ai};bool wroteVideo=false;int64_t last=base;
    for(int k=0;k<2;k++)if(mapping[k]>=0){auto *st=avformat_new_stream(out,nullptr);auto *src=in.ctx->streams[mapping[k]];r=avcodec_parameters_copy(st->codecpar,src->codecpar);st->codecpar->codec_tag=0;st->time_base=src->time_base;av_dict_copy(&st->metadata,src->metadata,0);if(r<0)break;}
    if(r>=0)r=avio_open(&out->pb,output,AVIO_FLAG_WRITE);
    AVDictionary *opts=nullptr;av_dict_set(&opts,"avoid_negative_ts","make_non_negative",0);if(r>=0)r=avformat_write_header(out,&opts);av_dict_free(&opts);
    AVPacket *p=av_packet_alloc();
    while(r>=0&&!cancelled(token)){int read=av_read_frame(in.ctx,p);if(read<0)break;int k=p->stream_index==vi?0:(p->stream_index==ai&&ai>=0?1:-1);if(k<0){av_packet_unref(p);continue;}
        AVStream *src=in.ctx->streams[p->stream_index];int64_t t=us(src,p->pts);
        if(t==AV_NOPTS_VALUE){r=AVERROR_INVALIDDATA;av_packet_unref(p);break;}
        if(t<base||t>=end){av_packet_unref(p);continue;}
        if(k==0&&!wroteVideo){if(!(p->flags&AV_PKT_FLAG_KEY)||!idr(p,src->codecpar)){av_packet_unref(p);continue;}wroteVideo=true;}
        last=std::max(last,t+us(src,p->duration));
        auto *dst=out->streams[k];int64_t shift=av_rescale_q(base,AV_TIME_BASE_Q,src->time_base);
        if(p->pts!=AV_NOPTS_VALUE)p->pts-=shift;if(p->dts!=AV_NOPTS_VALUE)p->dts-=shift;
        av_packet_rescale_ts(p,src->time_base,dst->time_base);p->stream_index=k;p->pos=-1;r=av_interleaved_write_frame(out,p);av_packet_unref(p);
    }
    if(cancelled(token))r=AVERROR_EXIT;if(!wroteVideo&&r>=0)r=AVERROR_INVALIDDATA;
    if(r>=0)r=av_write_trailer(out);av_packet_free(&p);if(out->pb)avio_closep(&out->pb);avformat_free_context(out);e->ReleaseStringUTFChars(jo,output);
    if(r<0)return result(e,error(r));std::ostringstream s;s<<"{\"startUs\":"<<base-origin(in.ctx)<<",\"endUs\":"<<last-origin(in.ctx)<<",\"videoIndex\":"<<vi<<",\"audioIndex\":"<<ai<<",\"video\":\"copy\",\"audio\":\""<<(ai>=0?"copy":"absent")<<"\"}";return result(e,s.str());
}
