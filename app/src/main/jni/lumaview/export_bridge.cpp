#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <sstream>
#include <stdexcept>
#include <algorithm>
#include <unistd.h>
#include <sys/stat.h>
#include <cerrno>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
}
namespace {
std::atomic<bool> stopped(false);
std::atomic<double> fraction(0);
std::string quote(const std::string &s){std::string o="\"";for(unsigned char c:s){if(c=='"'||c=='\\'){o+='\\';o+=c;}else if(c>=32)o+=c;else o+=' ';}return o+'"';}
void fail(int rc,const char *what){if(rc<0){char b[AV_ERROR_MAX_STRING_SIZE];av_strerror(rc,b,sizeof(b));throw std::runtime_error(std::string(what)+": "+b);}}
struct Input {
 AVFormatContext *f=nullptr;AVIOContext *io=nullptr;int fd;int64_t offset=0,size=-1;
 explicit Input(int descriptor):fd(descriptor){
  struct stat st{};if(fstat(fd,&st)==0&&S_ISREG(st.st_mode))size=st.st_size;
  auto buf=(unsigned char*)av_malloc(65536);if(!buf)throw std::runtime_error("内存不足");
  io=avio_alloc_context(buf,65536,0,this,read,nullptr,seek);if(!io){av_free(buf);throw std::runtime_error("IO初始化失败");}
  f=avformat_alloc_context();f->pb=io;f->flags|=AVFMT_FLAG_CUSTOM_IO;f->interrupt_callback={interrupt,nullptr};
  try{fail(avformat_open_input(&f,nullptr,nullptr,nullptr),"打开源文件");fail(avformat_find_stream_info(f,nullptr),"读取媒体轨道");}catch(...){cleanup();throw;}
 }
 ~Input(){cleanup();}
 void cleanup(){if(f)avformat_close_input(&f);if(io){av_freep(&io->buffer);avio_context_free(&io);}}
 static int read(void *p,uint8_t *buf,int count){auto s=(Input*)p;if(stopped.load())return AVERROR_EXIT;ssize_t n;do{n=pread(s->fd,buf,count,s->offset);}while(n<0&&errno==EINTR);if(n<0)return AVERROR(errno);if(n==0)return AVERROR_EOF;s->offset+=n;return (int)n;}
 static int64_t seek(void *p,int64_t off,int whence){auto s=(Input*)p;if(whence==AVSEEK_SIZE)return s->size;whence&=~AVSEEK_FORCE;int64_t target=whence==SEEK_SET?off:whence==SEEK_CUR?s->offset+off:whence==SEEK_END&&s->size>=0?s->size+off:-1;if(target<0)return AVERROR(EINVAL);s->offset=target;return target;}
 static int interrupt(void*){return stopped.load()?1:0;}
};
int64_t us(int64_t pts,AVRational base){return pts==AV_NOPTS_VALUE?AV_NOPTS_VALUE:av_rescale_q(pts,base,AV_TIME_BASE_Q);}
int select(AVFormatContext *f,AVMediaType type,int requested){
 if(requested==-2)return -1;
 if(requested>=0){if(requested>=(int)f->nb_streams||f->streams[requested]->codecpar->codec_type!=type)throw std::runtime_error("所选轨道映射失效，请重新打开视频");return requested;}
 int index=-1,count=0;for(unsigned i=0;i<f->nb_streams;i++)if(f->streams[i]->codecpar->codec_type==type&&!(f->streams[i]->disposition&AV_DISPOSITION_ATTACHED_PIC)){index=i;count++;}
 if(count>1)throw std::runtime_error("多轨道需要明确选择，不能默认导出错误轨道");return index;
}
bool idr(const AVPacket *p,const AVCodecParameters *par){
 const uint8_t *data=p->data;int size=p->size;if(size<5)return false;
 auto isIDR=[&](uint8_t h){int type=par->codec_id==AV_CODEC_ID_H264?h&31:(h>>1)&63;return par->codec_id==AV_CODEC_ID_H264?type==5:type==19||type==20;};
 if(par->codec_id!=AV_CODEC_ID_H264&&par->codec_id!=AV_CODEC_ID_HEVC)return false;
 bool lengthPrefixed=par->extradata_size>0&&par->extradata[0]==1;
 if(lengthPrefixed){int n=par->codec_id==AV_CODEC_ID_H264?(par->extradata_size>=5?(par->extradata[4]&3)+1:0):(par->extradata_size>=22?(par->extradata[21]&3)+1:0);if(!n)return false;int i=0;while(i+n<size){uint32_t len=0;for(int j=0;j<n;j++)len=(len<<8)|data[i++];if(!len||len>(uint32_t)(size-i))return false;if(isIDR(data[i]))return true;i+=len;}return false;}
 for(int i=0;i+4<size;i++){int prefix= data[i]==0&&data[i+1]==0&&data[i+2]==1?3:(data[i]==0&&data[i+1]==0&&data[i+2]==0&&data[i+3]==1?4:0);if(prefix&&i+prefix<size&&isIDR(data[i+prefix]))return true;}
 return false;
}
struct Plan {int video,audio,sub;int64_t origin,start,end,duration,askedA,askedB;std::string container,dropped;bool hdr;int width,height;};
Plan analyze(Input &input,int vi,int ai,int si,int64_t a,int64_t b){
 auto f=input.f;Plan p{};p.video=select(f,AVMEDIA_TYPE_VIDEO,vi);p.audio=select(f,AVMEDIA_TYPE_AUDIO,ai);p.sub=select(f,AVMEDIA_TYPE_SUBTITLE,si);
 if(p.video<0)throw std::runtime_error("没有可导出的视频轨道");
 auto par=f->streams[p.video]->codecpar;
 if(par->codec_id!=AV_CODEC_ID_H264&&par->codec_id!=AV_CODEC_ID_HEVC)throw std::runtime_error("原码流安全边界仅验证 H.264 / HEVC，请选择精确模式");
 p.origin=f->start_time==AV_NOPTS_VALUE?0:f->start_time;p.duration=f->duration;p.askedA=a;p.askedB=b;
 if(a<0||b<=a||p.duration<=0||b>p.duration+100000)throw std::runtime_error("剪辑区间无效或源文件时长未知");
 p.hdr=par->color_trc==AVCOL_TRC_SMPTE2084||par->color_trc==AVCOL_TRC_ARIB_STD_B67;p.width=par->width;p.height=par->height;
 p.container=std::string(f->iformat->name).find("mov")!=std::string::npos?"mp4":"matroska";
 p.start=AV_NOPTS_VALUE;p.end=p.origin+p.duration;
 auto packet=av_packet_alloc();if(!packet)throw std::runtime_error("内存不足");
 // Analyze on a worker; no approximate 'key-frame' flag is taken as proof of an IDR.
 int rc=0;while((rc=av_read_frame(f,packet))>=0){
  if(stopped.load()){av_packet_free(&packet);throw std::runtime_error("已取消");}
  if(packet->stream_index==p.video){int64_t t=us(packet->pts,f->streams[p.video]->time_base);
   if(t!=AV_NOPTS_VALUE){fraction.store(std::min(.99,double(std::max<int64_t>(0,t-p.origin))/b));
    if(idr(packet,par)){if(t<=p.origin+a)p.start=t;else if(t>=p.origin+b){p.end=t;av_packet_unref(packet);break;}}
   }
  };av_packet_unref(packet);
 }
 av_packet_free(&packet);if(stopped.load())throw std::runtime_error("已取消");if(rc<0&&rc!=AVERROR_EOF)fail(rc,"读取源视频");
 if(p.start==AV_NOPTS_VALUE)throw std::runtime_error("找不到可证明独立解码的 IDR 起点，请使用精确导出");
 if(p.end<=p.start)throw std::runtime_error("有效片段过短");
 for(unsigned i=0;i<f->nb_streams;i++)if((int)i!=p.video&&(int)i!=p.audio&&(int)i!=p.sub){if(!p.dropped.empty())p.dropped+=", ";p.dropped+=std::to_string(i)+" ("+av_get_media_type_string(f->streams[i]->codecpar->codec_type)+")";}
 return p;
}
std::string planJSON(const Plan &p){std::ostringstream s;s<<"{\"video\":"<<p.video<<",\"audio\":"<<p.audio<<",\"subtitle\":"<<p.sub<<",\"startUs\":"<<p.start-p.origin<<",\"endUs\":"<<p.end-p.origin<<",\"durationUs\":"<<p.duration<<",\"requestedStartUs\":"<<p.askedA<<",\"requestedEndUs\":"<<p.askedB<<",\"container\":"<<quote(p.container)<<",\"omitted\":"<<quote(p.dropped)<<",\"hdr\":"<<(p.hdr?"true":"false")<<",\"width\":"<<p.width<<",\"height\":"<<p.height<<"}";return s.str();}
std::string writeCopy(Input &input,int vi,int ai,int si,int64_t start,int64_t end,const std::string &path,const std::string &container){
 auto f=input.f;int video=select(f,AVMEDIA_TYPE_VIDEO,vi);int audio=select(f,AVMEDIA_TYPE_AUDIO,ai);int sub=select(f,AVMEDIA_TYPE_SUBTITLE,si);if(video<0)throw std::runtime_error("缺少视频轨道");
 const int64_t origin=f->start_time==AV_NOPTS_VALUE?0:f->start_time;start+=origin;end+=origin;
 AVFormatContext *out=nullptr;AVPacket *pkt=nullptr;bool header=false;long packets=0;
 try{
  fail(avformat_alloc_output_context2(&out,nullptr,container.c_str(),path.c_str()),"输出容器");if(!out)throw std::runtime_error("输出容器不支持");
  std::vector<int> map(f->nb_streams,-1);
  for(int i:{video,audio,sub})if(i>=0){if(avformat_query_codec(out->oformat,f->streams[i]->codecpar->codec_id,FF_COMPLIANCE_NORMAL)==0)throw std::runtime_error("所选轨道不兼容输出容器，请改用 MKV");auto st=avformat_new_stream(out,nullptr);if(!st)throw std::runtime_error("内存不足");map[i]=st->index;fail(avcodec_parameters_copy(st->codecpar,f->streams[i]->codecpar),"复制轨道参数");st->codecpar->codec_tag=0;st->time_base=f->streams[i]->time_base;av_dict_copy(&st->metadata,f->streams[i]->metadata,0);}
  av_dict_copy(&out->metadata,f->metadata,0);out->avoid_negative_ts=AVFMT_AVOID_NEG_TS_MAKE_NON_NEGATIVE;
  fail(avio_open(&out->pb,path.c_str(),AVIO_FLAG_WRITE),"创建暂存输出");fail(avformat_write_header(out,nullptr),"写入容器头");header=true;
  // Reopen/seeking doesn't touch the playback FD's offset: our AVIO reads via pread.
  fail(avformat_seek_file(f,-1,INT64_MIN,start,start,AVSEEK_FLAG_BACKWARD),"定位起点");
  pkt=av_packet_alloc();bool begun=false;int rc;
  while((rc=av_read_frame(f,pkt))>=0){
   if(stopped.load())throw std::runtime_error("已取消");int i=pkt->stream_index;int64_t t=us(pkt->pts,f->streams[i]->time_base);
   if(t==AV_NOPTS_VALUE)t=us(pkt->dts,f->streams[i]->time_base);
   if(i==video&&!begun){if(t==start&&idr(pkt,f->streams[i]->codecpar))begun=true;else if(t>start+1000000)throw std::runtime_error("实际随机访问点与确认区间不符");}
   if(t!=AV_NOPTS_VALUE&&t>end+2000000){av_packet_unref(pkt);break;}
   if(map[i]<0||t==AV_NOPTS_VALUE||t<start||t>=end||(i==video&&!begun)){av_packet_unref(pkt);continue;}
   auto src=f->streams[i];auto dst=out->streams[map[i]];auto shift=av_rescale_q(start,AV_TIME_BASE_Q,src->time_base);
   if(pkt->pts!=AV_NOPTS_VALUE)pkt->pts-=shift;if(pkt->dts!=AV_NOPTS_VALUE)pkt->dts-=shift;
   av_packet_rescale_ts(pkt,src->time_base,dst->time_base);pkt->stream_index=dst->index;pkt->pos=-1;
   fail(av_interleaved_write_frame(out,pkt),"写入压缩数据");if(i==video)packets++;fraction.store(std::min(.99,double(t-start)/(end-start)));av_packet_unref(pkt);
  }
  if(rc<0&&rc!=AVERROR_EOF)fail(rc,"读取源文件");if(!begun||packets==0)throw std::runtime_error("输出缺少可解码视频");
  fail(av_write_trailer(out),"容器收尾");header=false;av_packet_free(&pkt);avio_closep(&out->pb);avformat_free_context(out);out=nullptr;fraction.store(1);
  return "{\"copiedVideoPackets\":"+std::to_string(packets)+",\"process\":\"COPY\"}";
 }catch(...){if(pkt)av_packet_free(&pkt);if(out){if(header)av_write_trailer(out);if(out->pb)avio_closep(&out->pb);avformat_free_context(out);}unlink(path.c_str());throw;}
}
std::string get(JNIEnv *e,jstring s){const char *p=e->GetStringUTFChars(s,nullptr);std::string out=p;e->ReleaseStringUTFChars(s,p);return out;}
jstring error(JNIEnv *e,const std::exception &x){return e->NewStringUTF(("{\"error\":"+quote(x.what())+"}").c_str());}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_lumaview_mobile_export_NativeExporter_analyze(JNIEnv *e,jobject,jint fd,jint vi,jint ai,jint si,jlong a,jlong b){stopped=false;fraction=0;try{Input input(fd);return e->NewStringUTF(planJSON(analyze(input,vi,ai,si,a,b)).c_str());}catch(const std::exception &x){return error(e,x);}}
extern "C" JNIEXPORT jstring JNICALL Java_org_lumaview_mobile_export_NativeExporter_write(JNIEnv *e,jobject,jint fd,jint vi,jint ai,jint si,jlong a,jlong b,jstring path,jstring container){stopped=false;fraction=0;try{Input input(fd);return e->NewStringUTF(writeCopy(input,vi,ai,si,a,b,get(e,path),get(e,container)).c_str());}catch(const std::exception &x){return error(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_org_lumaview_mobile_export_NativeExporter_cancel(JNIEnv*,jobject){stopped=true;}
extern "C" JNIEXPORT jdouble JNICALL Java_org_lumaview_mobile_export_NativeExporter_progress(JNIEnv*,jobject){return fraction.load();}
