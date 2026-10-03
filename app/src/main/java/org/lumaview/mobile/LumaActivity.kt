package org.lumaview.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.R
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

@UnstableApi
class LumaActivity:Activity() {
    private lateinit var player:LumaPlayerView
    private lateinit var overlay:RoiOverlay
    private lateinit var status:TextView
    private lateinit var seek:SeekBar
    private lateinit var clipTimeline:ClipTimelineView
    private lateinit var clipText:TextView
    private lateinit var play:Button
    private lateinit var videoBox:FrameLayout
    private val io=Executors.newSingleThreadExecutor()
    private val commands=engineCommands
    companion object { private val engineCommands=Executors.newSingleThreadExecutor() }
    private val ui=Handler(Looper.getMainLooper())
    private val docs by lazy { DocumentAccess(this) }
    private val precise by lazy { PreciseExport(this) }
    @Volatile private var alive=false;@Volatile private var generation=0L;@Volatile private var exportGeneration=0L;private var current:File?=null;private var currentUri:Uri?=null
    private var title="请打开本地视频";private var paused=true;private var speed=1.0
    private var positionUs=0L;private var durationUs:Long?=null;private var dragging=false;private var beforeSeek=true;private var lastSeek=0L
    private var codedW=0;private var codedH=0;private var displayW=0;private var displayH=0;private var fileRotation=0;private var userRotation=0
    private var intrinsic:SourceRect?=null;private var selected:SourceRect?=null;private var beforeSelect=true;private var previousCrop:SourceRect?=null
    private var a=0L;private var b=0L;private var looping=false
    private var mode=1;private var ev=0f;private var shadows=.5f;private var contrast=0f;private var saturation=1f;private var denoise=.3f;private var detail=0f
    private var epoch=1;private var locked=false;private var compare=false;private var hdr=false;private var seekable=false;private var zoom=0.0;private var panX=0.0;private var panY=0.0
    private var exportBusy=false;private var output:File?=null;private var outputMime="video/mp4";private var volume=100.0;private var muted=false
    @Volatile private var renderState="等待实际渲染"
    private val logs=java.util.Collections.synchronizedList(mutableListOf<String>())
    private val logObserver=object:MPVLib.LogObserver { override fun logMessage(prefix:String,level:Int,text:String){if(text.contains("LVM_RENDER state=")){val state=Regex("state=(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull();renderState=when(state){-1->"GPU能力不足：增强旁路";-2->"HDR：增强旁路";-3->"着色器错误：增强未生效";0->"原画旁路";1->"自动均衡已渲染";2->"弱光增强已渲染";3->"极弱光增强已渲染";4->"流畅增强已渲染";else->"等待实际渲染"}};if(level<=40||text.contains("LVM")){logs.add("$prefix: $text");while(logs.size>300)logs.removeAt(0)}} }
    private val tick=object:Runnable {
        override fun run(){if(alive){val g=generation;commands.execute {
            if(!alive)return@execute
            val p=MPVLib.getPropertyDouble("time-pos");val d=MPVLib.getPropertyDouble("duration")
            val pause=MPVLib.getPropertyBoolean("pause")?:true;val rate=MPVLib.getPropertyDouble("speed")?:1.0
            val w=MPVLib.getPropertyInt("video-params/w")?:0;val h=MPVLib.getPropertyInt("video-params/h")?:0
            val dw=MPVLib.getPropertyInt("video-params/dw")?:w;val dh=MPVLib.getPropertyInt("video-params/dh")?:h
            val rot=MPVLib.getPropertyInt("video-params/rotate")?:0
            val cx=MPVLib.getPropertyInt("video-params/crop-x")?:0;val cy=MPVLib.getPropertyInt("video-params/crop-y")?:0
            val cw=MPVLib.getPropertyInt("video-params/crop-w")?:w;val ch=MPVLib.getPropertyInt("video-params/crop-h")?:h
            val trc=MPVLib.getPropertyString("video-params/gamma")?:""
            val isHdr=trc.contains("pq",true)||trc.contains("hlg",true)
            val canSeek=MPVLib.getPropertyBoolean("seekable")?:false
            ui.post {if(alive&&g==generation){
                p?.takeIf{it.isFinite()}?.let{positionUs=(it*1_000_000).toLong()};durationUs=d?.takeIf{it.isFinite()&&it>0}?.let{(it*1_000_000).toLong()}
                paused=pause;speed=rate;codedW=w;codedH=h;displayW=dw;displayH=dh;fileRotation=rot;intrinsic=SourceRect(cx,cy,cx+cw,cy+ch);seekable=canSeek
                if(hdr!=isHdr){hdr=isHdr;epoch++;applyEnhance()}
                play.text=if(paused)"▶ 播放" else "Ⅱ 暂停"
                status.text="$title\n${playbackLabel(paused,speed)} · ${time(positionUs)} / ${durationUs?.let{time(it)}?:"未知"} · ${if(hdr)"HDR：增强旁路" else listOf("原画","自动均衡","弱光增强","极弱光增强","流畅优先")[mode]}${if(compare)" · 原画对比" else ""} · $renderState"
                seek.isEnabled=canSeek&&durationUs!=null
                if(!dragging)seek.progress=durationUs?.let{((positionUs.toDouble()/it)*10000).toInt().coerceIn(0,10000)}?:0
                if(b==0L&&durationUs!=null)b=durationUs!!
                clipText.text="A ${time(a)}  →  B ${time(b)}${if(looping)" · 循环" else ""}"
                clipTimeline.durationUs=durationUs?:0;clipTimeline.startUs=a;clipTimeline.endUs=b;clipTimeline.positionUs=positionUs;clipTimeline.invalidate()
            }}
        }};ui.postDelayed(this,250)}
    }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setOnApplyWindowInsetsListener { v,ins -> v.setPadding(ins.systemWindowInsetLeft,ins.systemWindowInsetTop,ins.systemWindowInsetRight,ins.systemWindowInsetBottom);ins }
        buildUi();MPVLib.addLogObserver(logObserver)
        alive=true
        player.dispatch={block->commands.execute(block)}
        commands.execute {
            runCatching { player.initialize(filesDir.absolutePath,cacheDir.absolutePath) }
                .onSuccess { ui.post { if(alive){ui.post(tick);intent.data?.let { uri -> if(uri.scheme=="content")open(uri) else if(uri.scheme=="file")load(File(uri.path!!),null) }} } }
                .onFailure { e->ui.post { if(alive){alive=false;message(e.message?:"播放器初始化失败")} } }
        }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).roundToInt()
    private fun button(label:String,action:()->Unit)=Button(this).apply{text=label;contentDescription=label;minHeight=dp(48);setPadding(dp(8),0,dp(8),0);setOnClickListener{action()}}
    private fun row(parent:LinearLayout,vararg buttons:View){val scroll=HorizontalScrollView(this);val r=LinearLayout(this);buttons.forEach{r.addView(it)};scroll.addView(r);parent.addView(scroll,LinearLayout.LayoutParams(-1,dp(48)))}
    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff101b27.toInt())}
        status=TextView(this).apply{setTextColor(0xffe6eef7.toInt());textSize=14f;setPadding(dp(12),dp(4),dp(12),dp(4));text=title};root.addView(status)
        videoBox=FrameLayout(this).apply{setBackgroundColor(0xff000000.toInt())};player=layoutInflater.inflate(R.layout.lumaview_video,videoBox,false) as LumaPlayerView
        overlay=RoiOverlay(this);videoBox.addView(player);videoBox.addView(overlay,FrameLayout.LayoutParams(-1,-1));root.addView(videoBox,LinearLayout.LayoutParams(-1,0,1f))
        seek=SeekBar(this).apply{max=10000;contentDescription="播放进度"};root.addView(seek,LinearLayout.LayoutParams(-1,dp(40)))
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true;beforeSeek=paused;cmd{MPVLib.setPropertyBoolean("pause",true)}}
            override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean){if(user){val now=SystemClock.elapsedRealtime();if(now-lastSeek>=150){lastSeek=now;seekTo((durationUs?:0)*value/10000,false)}}}
            override fun onStopTrackingTouch(bar:SeekBar){seekTo((durationUs?:0)*bar.progress/10000,true);cmd{MPVLib.setPropertyBoolean("pause",beforeSeek)};dragging=false}
        })
        play=button("▶ 播放"){cmd{MPVLib.command(arrayOf("cycle","pause"))}}
        row(root,button("打开"){pick()},button("文件夹"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),11)},button("−10秒"){seekTo((positionUs-10_000_000).coerceAtLeast(0),true)},play,button("+30秒"){seekTo(positionUs+30_000_000,true)},button("倍速"){speedDialog()},button("音量"){volumeDialog()})
        row(root,button("区域放大"){armRoi()},button("应用选区"){applyRoi()},button("取消选区"){cancelRoi()},button("全画面"){resetView()},button("＋缩放"){setZoom(zoom+.5)},button("−缩放"){setZoom(zoom-.5)},button("增强"){enhanceDialog()},button("对比原画"){compare=!compare;applyEnhance()})
        clipText=TextView(this).apply{setTextColor(0xffb4d8ff.toInt());setPadding(dp(12),0,0,0)};root.addView(clipText)
        clipTimeline=ClipTimelineView(this);clipTimeline.contentDescription="剪辑A/B双手柄时间轴";clipTimeline.onRange={aa,bb->a=aa;b=bb;if(looping){looping=false;cmd{MPVLib.setPropertyString("ab-loop-a","no");MPVLib.setPropertyString("ab-loop-b","no")}}};root.addView(clipTimeline,LinearLayout.LayoutParams(-1,dp(64)))
        row(root,button("设A"){if(positionUs<b){a=positionUs}else message("A必须早于B")},button("设B"){if(positionUs>a){b=positionUs}else message("B必须晚于A")},button("时间输入"){clipDialog()},button("预览A"){preview(a)},button("预览B"){preview((b-1).coerceAtLeast(a))},button("循环A/B"){looping=!looping;cmd{MPVLib.setPropertyString("ab-loop-a",if(looping)(a/1e6).toString() else "no");MPVLib.setPropertyString("ab-loop-b",if(looping)(b/1e6).toString() else "no")}},button("导出片段"){exportDialog()},button("取消导出"){cancelExport()})
        row(root,button("旋转90°"){userRotation=(userRotation+90)%360;epoch++;locked=false;cmd{MPVLib.setPropertyInt("video-rotate",userRotation)};applyEnhance()},button("方向锁"){requestedOrientation=if(requestedOrientation==ActivityInfo.SCREEN_ORIENTATION_LOCKED)ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_LOCKED},button("字幕/音轨"){trackDialog()},button("屏幕亮度"){brightnessDialog()},button("截图"){capture()},button("最近播放"){recent()},button("诊断"){diagnostics()})
        overlay.onPan={dx,dy->panX=(panX+dx/overlay.width.coerceAtLeast(1)).coerceIn(-1.0,1.0);panY=(panY+dy/overlay.height.coerceAtLeast(1)).coerceIn(-1.0,1.0);epoch++;locked=false;cmd{MPVLib.setPropertyDouble("video-pan-x",panX);MPVLib.setPropertyDouble("video-pan-y",panY)};applyEnhance()}
        val scale=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:ScaleGestureDetector):Boolean{if(!overlay.armed){setZoom(zoom+log2(d.scaleFactor.toDouble()));return true};return false}})
        overlay.setOnTouchListener { _,e -> if(!overlay.armed&&e.pointerCount>1){scale.onTouchEvent(e);true}else false }
        setContentView(root)
    }
    private fun cmd(block:()->Unit){val g=generation;if(alive)commands.execute {if(alive&&generation==g)runCatching(block).onFailure{ui.post{message(it.message?:"播放命令失败")}}}}
    private fun pick(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),10)}
    private fun open(uri:Uri){if(exportBusy){message("请先完成或取消导出");return};docs.persist(uri);val g=++generation;NativeExport.cancel();title="正在缓存 ${docs.displayName(uri)}";io.execute {runCatching{docs.cache(uri)}.onSuccess{f->ui.post{if(g==generation&&!isFinishing)load(f,uri)else f.delete()}}.onFailure{e->ui.post{if(alive&&g==generation)message(e.message?:"读取失败")}}}}
    private fun load(f:File,uri:Uri?){current?.takeIf{it.parentFile==cacheDir}?.let{old->cmd{MPVLib.command(arrayOf("stop"));old.delete()}};current=f;currentUri=uri;title=uri?.let{docs.displayName(it)}?:f.name;durationUs=null;a=0;b=0;epoch++;locked=false;resetView()
        val resume=uri?.let{getPreferences(0).getLong("pos:$it",0L)}?:0L
        cmd{MPVLib.setOptionString("start",(resume/1e6).toString());if(player.holder.surface.isValid)MPVLib.command(arrayOf("loadfile",f.absolutePath))else player.playFile(f.absolutePath);MPVLib.setPropertyBoolean("pause",false)}
        uri?.let{getPreferences(0).edit().putString("recent",it.toString()).apply()}
    }
    private fun seekTo(us:Long,exact:Boolean){if(!seekable)return;epoch++;locked=false;val target=us.coerceIn(0,durationUs?:Long.MAX_VALUE);cmd{MPVLib.command(arrayOf("seek",(target/1e6).toString(),if(exact)"absolute+exact" else "absolute+keyframes"))};applyEnhance()}
    private fun preview(us:Long){cmd{MPVLib.setPropertyBoolean("pause",true)};seekTo(us,true)}
    private fun speedDialog(){val values=doubleArrayOf(.25,.5,.75,1.0,1.25,1.5,2.0);AlertDialog.Builder(this).setTitle("播放倍速").setItems(values.map{"${it}×"}.toTypedArray()){_,i->cmd{MPVLib.setPropertyDouble("speed",values[i])}}.show()}
    private fun slider(parent:LinearLayout,name:String,min:Float,max:Float,value:Float,changed:(Float)->Unit){val label=TextView(this).apply{text="$name：${String.format(Locale.US,"%.2f",value)}"};parent.addView(label);val s=SeekBar(this).apply{this.max=1000;progress=((value-min)/(max-min)*1000).toInt()};parent.addView(s);s.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){};override fun onProgressChanged(b:SeekBar,p:Int,u:Boolean){if(u){val v=min+(max-min)*p/1000;label.text="$name：${String.format(Locale.US,"%.2f",v)}";changed(v)}}})}
    private fun volumeDialog(){val box=LinearLayout(this).apply{orientation=1};slider(box,"音量",0f,100f,volume.toFloat()){volume=it.toDouble();muted=false;cmd{MPVLib.setPropertyBoolean("mute",false);MPVLib.setPropertyDouble("volume",volume)}};AlertDialog.Builder(this).setTitle("媒体音量").setView(box).setNeutralButton(if(muted)"取消静音" else "静音"){_,_->muted=!muted;cmd{MPVLib.setPropertyBoolean("mute",muted)}}.setPositiveButton("完成",null).show()}
    private fun brightnessDialog(){val box=LinearLayout(this).apply{orientation=1};slider(box,"当前窗口背光",.02f,1f,window.attributes.screenBrightness.takeIf{it>0}?:.5f){window.attributes=window.attributes.apply{screenBrightness=it}};AlertDialog.Builder(this).setTitle("屏幕背光（不改变视频像素）").setView(box).setPositiveButton("完成",null).show()}
    private fun enhanceDialog(){val box=LinearLayout(this).apply{orientation=1;setPadding(dp(12),0,dp(12),0)};val choices=arrayOf("原画","自动均衡","弱光增强","极弱光增强","流畅优先");val sp=Spinner(this);sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices);sp.setSelection(mode);box.addView(sp);sp.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long){mode=i;denoise=if(i==3).65f else .3f;applyEnhance()}}
        slider(box,"曝光EV",-1f,1f,ev){ev=it;locked=false;applyEnhance()};slider(box,"暗部",0f,1f,shadows){shadows=it;applyEnhance()};slider(box,"对比度",-.25f,.25f,contrast){contrast=it;applyEnhance()};slider(box,"饱和度",0f,1.5f,saturation){saturation=it;applyEnhance()};slider(box,"降噪",0f,1f,denoise){denoise=it;applyEnhance()};slider(box,"细节",0f,.3f,detail){detail=it;applyEnhance()}
        val scroll=ScrollView(this);scroll.addView(box);AlertDialog.Builder(this).setTitle(if(hdr)"HDR输入：SDR增强已旁路" else "按当前可见区域自适应增强").setView(scroll).setNeutralButton(if(locked)"解除锁定" else "锁定曝光"){_,_->locked=!locked;applyEnhance()}.setNegativeButton("重置"){_,_->ev=0f;shadows=.5f;contrast=0f;saturation=1f;denoise=.3f;detail=0f;locked=false;epoch++;applyEnhance()}.setPositiveButton("完成",null).show()}
    private fun applyEnhance(){val params="$mode $ev $shadows $contrast $saturation $denoise $detail $epoch ${if(locked)1 else 0} ${if(compare||hdr)1 else 0}";cmd{MPVLib.setPropertyString("lvm-params",params)}}
    private fun armRoi(){if(codedW<=0){message("尚未取得视频尺寸");return};beforeSelect=paused;previousCrop=selected;selected=null;zoom=0.0;panX=0.0;panY=0.0;cmd{MPVLib.setPropertyBoolean("pause",true);MPVLib.setPropertyString("video-crop","");MPVLib.setPropertyDouble("video-zoom",0.0);MPVLib.setPropertyDouble("video-pan-x",0.0);MPVLib.setPropertyDouble("video-pan-y",0.0)};overlay.armed=true;overlay.rect=null;overlay.zoomed=false;overlay.invalidate();message("拖出选框，可拖动四角或框内调整，再点应用选区")}
    private fun applyRoi(){if(!overlay.armed)return;val r=overlay.rect?:return;val rotation=((fileRotation%360)+360)%360;val swapped=rotation%180==90;val dw=if(swapped)displayH else displayW;val dh=if(swapped)displayW else displayH;val map=RoiMath(dw,dh,overlay.width,overlay.height);val pick=map.select(r.left.toDouble(),r.top.toDouble(),r.right.toDouble(),r.bottom.toDouble(),dp(24).toDouble())?:run{message("选区太小或落在黑边，请继续调整");return}
        val crop=intrinsic?:SourceRect(0,0,codedW,codedH);val points=listOf(pick.left.toDouble()/dw to pick.top.toDouble()/dh,pick.right.toDouble()/dw to pick.bottom.toDouble()/dh).flatMap{listOf(it)}
        fun inverse(u:Double,v:Double)=when(rotation){90->v to 1-u;180->1-u to 1-v;270->1-v to u;else->u to v}
        val p1=inverse(points[0].first,points[0].second);val p2=inverse(points[1].first,points[1].second)
        selected=SourceRect(crop.left+floor(min(p1.first,p2.first)*crop.width).toInt(),crop.top+floor(min(p1.second,p2.second)*crop.height).toInt(),crop.left+ceil(max(p1.first,p2.first)*crop.width).toInt(),crop.top+ceil(max(p1.second,p2.second)*crop.height).toInt())
        overlay.armed=false;overlay.rect=null;overlay.zoomed=true;overlay.invalidate();epoch++;locked=false;cmd{MPVLib.setPropertyString("video-crop",selected!!.crop());MPVLib.setPropertyBoolean("pause",beforeSelect)};applyEnhance()}
    private fun cancelRoi(){if(!overlay.armed)return;selected=previousCrop;overlay.armed=false;overlay.rect=null;overlay.zoomed=selected!=null;overlay.invalidate();cmd{MPVLib.setPropertyString("video-crop",selected?.crop()?:"");MPVLib.setPropertyBoolean("pause",beforeSelect)};epoch++;applyEnhance()}
    private fun resetView(){selected=null;zoom=0.0;panX=0.0;panY=0.0;overlay.armed=false;overlay.rect=null;overlay.zoomed=false;overlay.invalidate();epoch++;locked=false;cmd{MPVLib.setPropertyString("video-crop","");MPVLib.setPropertyDouble("video-zoom",0.0);MPVLib.setPropertyDouble("video-pan-x",0.0);MPVLib.setPropertyDouble("video-pan-y",0.0)};applyEnhance()}
    private fun setZoom(value:Double){zoom=value.coerceIn(0.0,3.0);overlay.zoomed=zoom>0||selected!=null;epoch++;locked=false;cmd{MPVLib.setPropertyDouble("video-zoom",zoom)};applyEnhance()}
    private fun clipDialog(){val box=LinearLayout(this).apply{orientation=1};val av=EditText(this).apply{hint="A（秒）";setText((a/1e6).toString());inputType=8194};val bv=EditText(this).apply{hint="B（秒）";setText((b/1e6).toString());inputType=8194};box.addView(av);box.addView(bv);AlertDialog.Builder(this).setTitle("输入剪辑时间（秒）").setView(box).setPositiveButton("应用"){_,_->val aa=av.text.toString().toDoubleOrNull();val bb=bv.text.toString().toDoubleOrNull();if(aa==null||bb==null||!aa.isFinite()||!bb.isFinite()||!ClipRange((aa*1e6).toLong(),(bb*1e6).toLong()).valid(durationUs))message("请输入0 ≤ A < B ≤ 视频时长")else{a=(aa*1e6).toLong();b=(bb*1e6).toLong()}}.setNegativeButton("取消",null).show()}
    private fun exportDialog(){val f=current?:return;if(exportBusy){message("已有导出任务");return};val range=ClipRange(a,b);if(!range.valid(durationUs)){message("请先设置有效的A/B范围");return};AlertDialog.Builder(this).setTitle("导出原文件片段").setMessage("不写入播放增强、选区或临时旋转。原码流模式仅支持可证实IDR起点的H.264；精确模式输出H.264/AAC，重新编码，可能耗时。字幕和额外轨道不保留。输入始终只读。").setPositiveButton("原码流"){_,_->probeCopy(f,range)}.setNeutralButton("精确重编码"){_,_->confirmPrecise(f,range)}.setNegativeButton("取消",null).show()}
    private fun probeCopy(f:File,range:ClipRange){if(!alive||current!=f)return;exportBusy=true;val task=++exportGeneration;val token=NativeExport.begin();io.execute{runCatching{JSONObject(NativeExport.probe(f.absolutePath,range.startUs,range.endUs,-1,token))}.onSuccess{r->ui.post{if(!alive||task!=exportGeneration)return@post;exportBusy=false;if(r.has("error")||r.optLong("startUs",-1)<0){message(r.optString("error","无法证明H.264 IDR边界，请选精确模式"));return@post};val tracks=r.getJSONArray("tracks");val audio=mutableListOf(-1 to "不保留音轨");for(i in 0 until tracks.length()){val t=tracks.getJSONObject(i);if(t.getString("type")=="audio")audio.add(t.getInt("index") to "音轨 ${t.getInt("index")}：${t.getString("codec")}")};AlertDialog.Builder(this).setTitle("选音轨；其他轨道和字幕不保留").setItems(audio.map{it.second}.toTypedArray()){_,i->val actual=r.getLong("startUs");AlertDialog.Builder(this).setTitle("确认原码流边界").setMessage("请求A ${time(range.startUs)}\n实际A′ ${time(actual)}\n请求B ${time(range.endUs)}\n实际B′ ${time(r.getLong("endUs"))}（延展到下一IDR或文件结尾）\n前段增加 ${time(range.startUs-actual)}\n整段GOP保持依赖完整，实际区间写入结果摘要。").setPositiveButton("开始"){_,_->startCopy(f,range,r.getInt("videoIndex"),audio[i].first)}.setNegativeButton("取消",null).show()}.show()}}.onFailure{ui.post{if(alive&&task==exportGeneration){exportBusy=false;message(it.message?:"片段分析失败")}}}}}
    private fun startCopy(f:File,range:ClipRange,vi:Int,ai:Int){if(!alive||current!=f){message("源文件已改变，请重新选择片段");return};exportBusy=true;val task=++exportGeneration;val token=NativeExport.begin();val out=File(cacheDir,"export-${System.nanoTime()}.mp4");output=out;io.execute{runCatching{JSONObject(NativeExport.remux(f.absolutePath,out.absolutePath,range.startUs,range.endUs,vi,ai,token))}.onSuccess{r->ui.post{if(!alive||task!=exportGeneration){out.delete();return@post};exportBusy=false;if(r.has("error")){out.delete();message(r.getString("error"))}else finishExport(out,r.toString(2))}}.onFailure{out.delete();ui.post{if(alive&&task==exportGeneration){exportBusy=false;message(it.message?:"导出失败")}}}}}
    private fun confirmPrecise(f:File,range:ClipRange){if(hdr){message("首版精确导出限定SDR输入");return};AlertDialog.Builder(this).setTitle("精确H.264/AAC重编码").setMessage("保留源尺寸，关闭裁剪优化及编辑列表预滚。不保留字幕。当前版本使用文件的默认视频/音频轨；请在多轨文件中核对。无音轨时输出纯视频。").setPositiveButton("带默认音轨"){_,_->startPrecise(f,range,true)}.setNeutralButton("纯视频"){_,_->startPrecise(f,range,false)}.setNegativeButton("取消",null).show()}
    private fun startPrecise(f:File,range:ClipRange,audio:Boolean){if(!alive||current!=f)return;exportBusy=true;val task=++exportGeneration;cmd{MPVLib.setPropertyBoolean("pause",true)};val out=File(cacheDir,"precise-${System.nanoTime()}.mp4");output=out;runCatching{precise.start(f,out,range,audio){result,error->if(!alive||task!=exportGeneration){out.delete();return@start};exportBusy=false;if(error!=null){out.delete();message(error)}else finishExport(out,result?:"完成")}}.onFailure{exportBusy=false;out.delete();message(it.message?:"无法启动编码器")}}
    private fun finishExport(out:File,result:String){File(filesDir,"last-export.json").writeText(result);AlertDialog.Builder(this).setTitle("片段已在暂存区生成").setMessage(result).setPositiveButton("另存新文件"){_,_->output=out;outputMime="video/mp4";createDocument("LumaView_${System.currentTimeMillis()}.mp4")}.setNegativeButton("稍后",null).show()}
    private fun createDocument(name:String){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(outputMime).putExtra(Intent.EXTRA_TITLE,name),12)}
    private fun cancelExport(){exportGeneration++;NativeExport.cancel();precise.cancel();exportBusy=false;output=null;message("已请求取消")}
    private fun trackDialog(){cmd{val count=MPVLib.getPropertyInt("track-list/count")?:0;val options=mutableListOf("关闭字幕");val actions=mutableListOf("sid" to "no");for(i in 0 until count){val type=MPVLib.getPropertyString("track-list/$i/type")?:continue;if(type=="audio"||type=="sub"){val id=MPVLib.getPropertyInt("track-list/$i/id")?:continue;options.add("${if(type=="audio")"音轨" else "字幕"} $id ${MPVLib.getPropertyString("track-list/$i/lang")?:""}");actions.add((if(type=="audio")"aid" else "sid") to id.toString())}};ui.post{if(!alive)return@post;AlertDialog.Builder(this).setTitle("播放轨道").setItems(options.toTypedArray()){_,i->cmd{MPVLib.setPropertyString(actions[i].first,actions[i].second)}}.show()}}}
    private fun capture(){if(Build.VERSION.SDK_INT<24){message("当前API不支持无控件Surface截图");return};val bmp=Bitmap.createBitmap(player.width.coerceAtLeast(1),player.height.coerceAtLeast(1),Bitmap.Config.ARGB_8888);PixelCopy.request(player,bmp,{code->if(!alive){bmp.recycle();return@request};if(code==PixelCopy.SUCCESS){val g=generation;io.execute{val f=File(cacheDir,"capture-${System.nanoTime()}.png");f.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle();ui.post{if(!alive||g!=generation){f.delete();return@post};output=f;outputMime="image/png";createDocument("LumaView_${System.currentTimeMillis()}.png")}}}else{bmp.recycle();message("截图失败：$code")}},ui)}
    private fun recent(){val s=getPreferences(0).getString("recent",null);if(s!=null)open(Uri.parse(s))else message("暂无最近播放记录")}
    private fun diagnostics(){cmd{val info=JSONObject().put("applicationId",packageName).put("api",Build.VERSION.SDK_INT).put("abis",Build.SUPPORTED_ABIS.joinToString()).put("device",Build.MANUFACTURER+" "+Build.MODEL).put("decoder",MPVLib.getPropertyString("hwdec-current")).put("vo",MPVLib.getPropertyString("current-vo")).put("gpuTimeNs",JSONObject.NULL).put("physicalHuaweiVerified",false).put("codecs",MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.joinToString{it.name}).put("logs",synchronized(logs){logs.joinToString("\n")});io.execute{val f=File(cacheDir,"diagnostics.json");f.writeText(info.toString(2));ui.post{if(!alive){f.delete();return@post};output=f;outputMime="application/json";AlertDialog.Builder(this).setTitle("本地诊断").setMessage(info.toString(2)).setPositiveButton("保存诊断"){_,_->createDocument("LumaView_diagnostics.json")}.setNegativeButton("关闭",null).show()}}}}
    private fun message(s:String){if(isFinishing||isDestroyed)return;Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
    private fun time(us:Long):String {val ms=(us.coerceAtLeast(0)/1000);return String.format(Locale.US,"%02d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000)}
    override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(result!=RESULT_OK)return
        val uri=data?.data?:return
        when(request) {
            10 -> open(uri)
            11 -> {
                docs.persist(uri)
                io.execute {
                    runCatching { docs.children(uri) }.onSuccess { items ->
                        ui.post {
                            if(!alive)return@post
                            if(items.isEmpty()) message("文件夹中没有直接可见的视频")
                            else AlertDialog.Builder(this).setTitle("文件列表")
                                .setItems(items.map{it.second}.toTypedArray()){_,i->open(items[i].first)}.show()
                        }
                    }.onFailure { e -> ui.post { message(e.message?:"授权失败") } }
                }
            }
            12 -> {
                val f=output?:return
                val task=++exportGeneration;val sourceUri=currentUri;exportBusy=true
                io.execute {
                    runCatching { docs.commit(f,uri,sourceUri){!alive||task!=exportGeneration} }
                        .onSuccess { f.delete();ui.post { if(alive&&task==exportGeneration){exportBusy=false;message("已保存新文件")} } }
                        .onFailure { e -> ui.post { if(alive&&task==exportGeneration){exportBusy=false;message(e.message?:"保存失败")} } }
                }
            }
        }
    }
    override fun onPause(){currentUri?.let{getPreferences(0).edit().putLong("pos:$it",positionUs).apply()};cmd{MPVLib.setPropertyBoolean("pause",true)};super.onPause()}
    override fun onDestroy(){ui.removeCallbacks(tick);precise.cancel();NativeExport.cancel();MPVLib.removeLogObserver(logObserver);player.prepareDestroy();alive=false;generation++;exportGeneration++;commands.execute{player.destroy()};io.shutdown();super.onDestroy()}
    @Deprecated("Deprecated in Java") override fun onBackPressed(){if(overlay.armed)cancelRoi()else if(exportBusy)message("请先完成或取消导出")else super.onBackPressed()}
}
