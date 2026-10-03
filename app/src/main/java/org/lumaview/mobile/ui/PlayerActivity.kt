package org.lumaview.mobile.ui

import android.app.*
import android.content.*
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import org.json.*
import org.lumaview.mobile.*
import org.lumaview.mobile.player.*
import org.lumaview.mobile.storage.*
import org.lumaview.mobile.export.*
import org.lumaview.mobile.viewport.RoiOverlayView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class PlayerActivity:Activity(),TextureView.SurfaceTextureListener {
 private lateinit var session:PlayerSession;private lateinit var access:DocumentAccess;private lateinit var audio:AudioManager
 private lateinit var root:LinearLayout;private lateinit var video:TextureView;private lateinit var overlay:RoiOverlayView;private lateinit var videoFrame:FrameLayout
 private lateinit var top:HorizontalScrollView;private lateinit var bottom:LinearLayout;private lateinit var selectionRow:LinearLayout;private lateinit var clipPanel:LinearLayout
 private lateinit var seek:SeekBar;private lateinit var volume:SeekBar;private lateinit var time:TextView;private lateinit var status:TextView;private lateinit var playButton:Button;private lateinit var speedButton:Button;private lateinit var lockButton:Button;private lateinit var applyButton:Button
 private lateinit var timeline:ClipTimelineView;private lateinit var clipText:TextView
 private var s=PlayerState();private var uri:Uri?=null;private var fileName="视频";private var crop:RoiRect?=null;private var userRotation=0
 private var enhancement=EnhanceSettings();private var priorPause=true;private var dragging=false;private var lastPreview=0L;private var priorSeekPause=true;private var controls=true;private var alwaysControls=false;private var touchLocked=false
 private var clipRange:ClipRange?=null;private var loop=false;private var gestureSeekStart=0L
 private val handler=Handler(Looper.getMainLooper());private val work=Executors.newSingleThreadExecutor();private var exact:ExactExporter?=null
 private var busy=false;private var pickingDestination=false;private var temporary:File?=null;private var outputSummary="";private var saveMime="video/mp4";private var saveExtension="mp4";private var dialog:ProgressDialog?=null;private var cancel=AtomicBoolean(false)
 private val governor=QualityGovernor();private var lastFrames=0L;private var lastDrops=0L;private var startAt=0L;private var lastSeekAt=0L;private var quality=0;private var thermal:Int?=null
 private val hide=Runnable{if(!s.paused&&!overlay.selecting&&clipPanel.visibility!=View.VISIBLE&&!alwaysControls&&!touchLocked&&!busy)showControls(false)}
 private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
 private fun button(label:String,action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;minWidth=48.dp;minimumHeight=48.dp;textSize=13f;setPadding(10.dp,0,10.dp,0);setOnClickListener{if(!touchLocked||this==lockButton){wake();action()}}}
 private fun text(value:String,size:Float=13f)=TextView(this).apply{this.text=value;textSize=size;setTextColor(0xffdfebfa.toInt());setPadding(4.dp,2.dp,4.dp,2.dp)}
 private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
 private fun message(value:String){if(!isFinishing)AlertDialog.Builder(this).setTitle("LumaView").setMessage(value).setPositiveButton("知道了",null).show()}
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);audio=getSystemService(AUDIO_SERVICE) as AudioManager;volumeControlStream=AudioManager.STREAM_MUSIC
  access=DocumentAccess(this);session=PlayerSession(this){state->render(state)}
  buildUi()
  val u=intent.data
  if(u==null||u.scheme !in listOf("content","file")){message("请从文件页或系统文件选择器打开视频");startActivity(Intent(this,LibraryActivity::class.java));finish();return}
  uri=u;access.rememberGrant(u,intent.flags);open(u)
 }
 private fun buildUi(){
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff0b1420.toInt())}
  top=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false};val tools=row();top.addView(tools)
  tools.addView(button("← 文件"){finish()});tools.addView(button("画面增强"){enhancePanel()});tools.addView(button("区域放大"){beginRoi()});tools.addView(button("全画面"){resetView()});tools.addView(button("片段剪辑"){toggleClip()});tools.addView(button("旋转"){userRotation=(userRotation+90)%360;crop=null;overlay.current=null;enhancement=enhancement.copy(locked=false);session.viewport(null,userRotation);refreshMath()});tools.addView(button("更多"){more()});root.addView(top,LinearLayout.LayoutParams(-1,48.dp))
  selectionRow=row();selectionRow.addView(text("选框后点应用"),LinearLayout.LayoutParams(0,48.dp,1f));applyButton=button("应用"){val r=overlay.apply();if(r!=null){crop=r;session.viewport(r,userRotation);session.pause(priorPause);selectionRow.visibility=View.GONE;refreshMath()}};selectionRow.addView(applyButton);selectionRow.addView(button("取消"){overlay.cancel();selectionRow.visibility=View.GONE;session.pause(priorPause);refreshMath()});selectionRow.visibility=View.GONE;root.addView(selectionRow)
  videoFrame=FrameLayout(this).apply{setBackgroundColor(0xff000000.toInt())};video=TextureView(this).apply{surfaceTextureListener=this@PlayerActivity};videoFrame.addView(video,FrameLayout.LayoutParams(-1,-1));overlay=RoiOverlayView(this);videoFrame.addView(overlay,FrameLayout.LayoutParams(-1,-1));root.addView(videoFrame,LinearLayout.LayoutParams(-1,0,1f))
  status=text("正在打开视频…",12f);root.addView(status)
  clipPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff172437.toInt());visibility=View.GONE}
  clipText=text("A — B",12f);clipPanel.addView(clipText);timeline=ClipTimelineView(this);clipPanel.addView(timeline,LinearLayout.LayoutParams(-1,84.dp))
  val cs=HorizontalScrollView(this);val cr=row();cs.addView(cr)
  cr.addView(button("A=当前"){setMark(true)});cr.addView(button("B=当前"){setMark(false)});cr.addView(button("输入时间"){timeInput()});cr.addView(button("循环预览"){toggleLoop()});cr.addView(button("时间轴 +"){timeline.zoomAt(2.0)});cr.addView(button("时间轴 −"){timeline.zoomAt(.5)});cr.addView(button("原码流"){exportCopy()});cr.addView(button("精确导出"){exportExact()});clipPanel.addView(cs);root.addView(clipPanel)
  bottom=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff101d2d.toInt())};time=text("--:-- / --:--",13f);bottom.addView(time)
  seek=SeekBar(this).apply{max=10000;contentDescription="播放进度"};bottom.addView(seek,LinearLayout.LayoutParams(-1,36.dp))
  val commands=row();commands.gravity=Gravity.CENTER;commands.addView(button("−10秒"){relative(-10_000_000)});playButton=button("播放"){session.pause(!s.paused)};commands.addView(playButton,LinearLayout.LayoutParams(0,48.dp,1f));commands.addView(button("+30秒"){relative(30_000_000)});speedButton=button("1.00×"){speedPanel()};commands.addView(speedButton);lockButton=button("锁定"){touchLocked=!touchLocked;overlay.locked=touchLocked;lockButton.text=if(touchLocked)"解锁" else "锁定";if(touchLocked){top.visibility=View.GONE;clipPanel.visibility=View.GONE;status.text="触控锁定：点击解锁恢复操作"}else showControls(true)};commands.addView(lockButton);bottom.addView(commands)
  val vr=row();vr.addView(button("音量"){val muted=audio.getStreamVolume(AudioManager.STREAM_MUSIC)==0;audio.setStreamVolume(AudioManager.STREAM_MUSIC,if(muted)(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2).coerceAtLeast(1)else 0,0);updateVolume()})
  volume=SeekBar(this).apply{max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);contentDescription="媒体音量"};vr.addView(volume,LinearLayout.LayoutParams(0,48.dp,1f));vr.addView(button("横/竖屏"){requestedOrientation=if(resources.configuration.orientation==2)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE});bottom.addView(vr);root.addView(bottom);setContentView(root)
  root.setOnApplyWindowInsetsListener{v,i->v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
  overlay.onSelection={applyButton.isEnabled=it};overlay.onTap={if(!touchLocked){if(controls)showControls(false)else wake()}};overlay.onDouble={left->if(!touchLocked)relative(if(left)-10_000_000 else 30_000_000)}
  overlay.onViewport={r->crop=r;enhancement=enhancement.copy(locked=false);session.viewport(r,userRotation);refreshMath();wake()}
  overlay.onVolume={delta->val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);val step=(delta*max*3).roundToInt();if(step!=0)audio.setStreamVolume(AudioManager.STREAM_MUSIC,(audio.getStreamVolume(AudioManager.STREAM_MUSIC)+step).coerceIn(0,max),0);updateVolume();wake()}
  overlay.onBrightness={delta->val a=window.attributes;val current=if(a.screenBrightness<0).5f else a.screenBrightness;a.screenBrightness=(current+delta*1.5f).coerceIn(.02f,1f);window.attributes=a;wake()}
  overlay.onSeek={fraction,end->if(!dragging){gestureSeekStart=s.positionUs?:0;priorSeekPause=s.paused;session.pause(true);dragging=true};val target=(gestureSeekStart+fraction*(s.durationUs?:0)).toLong();preview(target,end,if(end)priorSeekPause else null);if(end)dragging=false;wake()}
  seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onStartTrackingTouch(bar:SeekBar){if(touchLocked)return;dragging=true;priorSeekPause=s.paused;session.pause(true);wake()}
   override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){if(fromUser&&!touchLocked)s.durationUs?.let{preview((it*(value/10000.0)).toLong(),false)}}
   override fun onStopTrackingTouch(bar:SeekBar){s.durationUs?.let{preview((it*(bar.progress/10000.0)).toLong(),true,priorSeekPause)};dragging=false}
  })
  volume.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean){if(user&&!touchLocked)audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0)}
   override fun onStartTrackingTouch(bar:SeekBar){wake()};override fun onStopTrackingTouch(bar:SeekBar){}
  })
  timeline.onChange={range,end->clipRange=range;session.pause(true);val before=timeline.tag as? ClipRange;val target=if(before==null||before.startUs!=range.startUs)range.startUs else range.endUs;timeline.tag=range;preview(target,end);if(loop)session.setLoop(range);updateClip();wake()}
  videoFrame.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->refreshMath()};updateVolume()
 }
 private fun open(u:Uri,allowCache:Boolean=false){access.openRead(u,allowCache){result->if(isDestroyed){result.getOrNull()?.close();return@openRead};result.onSuccess{lease->fileName=lease.name;uri=u;rememberRecent(u,lease.name);val pos=getSharedPreferences("history",0).getLong("position:$u",0);startAt=SystemClock.elapsedRealtime();session.open(lease,pos);session.enhance(enhancement,true)}.onFailure{e->if(e.message=="CACHE_CONSENT_REQUIRED")AlertDialog.Builder(this).setTitle("该文档不可直接定位").setMessage("需要复制到应用缓存后播放和剪辑。保留至少 256 MiB 空间；不会上传视频。").setPositiveButton("同意缓存"){_,_->open(u,true)}.setNegativeButton("取消",null).show()else message("打开失败：${e.message}\n文件移动或授权失效后请重新选择。")}}}
 private fun rememberRecent(u:Uri,name:String){val pref=getSharedPreferences("history",0);val old=runCatching{JSONArray(pref.getString("recent","[]"))}.getOrDefault(JSONArray());val fresh=JSONArray().put(JSONObject().put("uri",u.toString()).put("name",name));for(i in 0 until old.length()){val v=old.getJSONObject(i);if(v.optString("uri")!=u.toString()&&fresh.length()<20)fresh.put(v)};pref.edit().putString("recent",fresh.toString()).apply()}
 private fun render(state:PlayerState){
  val wasPaused=s.paused;s=state;val duration=s.durationUs;seek.isEnabled=duration!=null&&duration>0&&s.seekable&&!touchLocked
  if(!dragging&&duration!=null&&duration>0)seek.progress=((s.positionUs?:0)*10000.0/duration).toInt().coerceIn(0,10000)
  time.text="${formatTime(s.positionUs)} / ${formatTime(duration)}";playButton.text=if(s.paused)"▶ 播放" else "Ⅱ 暂停";speedButton.text=String.format(java.util.Locale.ROOT,"%.2f×",s.speed)
  val names=arrayOf("原画","自动均衡","弱光增强","极弱光增强","流畅优先")
  val r=s.receipt;val label=if(s.hdr)"HDR：SDR增强已旁路" else if(enhancement.bypass)"原画对比（保持选区）" else if(r!=null&&r[6]==0.0)names[r[5].toInt().coerceIn(0,4)] else if(r!=null&&r[6]==2.0)"增强不可用：渲染未通过" else "增强等待渲染回执"
  status.text=s.error?:"$label${if(crop!=null)" · 区域增强" else ""}${if(quality>0)" · 负载降级 $quality" else ""} · ${s.width}×${s.height}"
  refreshMath();if(clipPanel.visibility==View.VISIBLE){if(clipRange==null&&duration!=null&&duration>0)clipRange=ClipRange(0,duration);updateClip()}
  if(s.paused)showControls(true)else if(wasPaused&&controls){handler.removeCallbacks(hide);handler.postDelayed(hide,3000)}
  if(Build.VERSION.SDK_INT>=29)thermal=runCatching{(getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus}.getOrNull()
  val now=SystemClock.elapsedRealtime();val frames=r?.get(13)?.toLong();val drop=s.dropped
  val previous=quality;quality=governor.update(now,frames?.minus(lastFrames),drop?.minus(lastDrops),!s.paused&&!dragging&&now-startAt>5000&&now-lastSeekAt>2000,thermal);if(frames!=null)lastFrames=frames;if(drop!=null)lastDrops=drop
  if(previous!=quality)sendEnhancement()
 }
 private fun sendEnhancement(){val effective=when(quality){1->enhancement.copy(detail=0f);2->enhancement.copy(detail=0f,denoise=0f);3->enhancement.copy(mode=4,detail=0f,denoise=0f);4->enhancement.copy(bypass=true);else->enhancement};session.enhance(effective)}
 private fun refreshMath(){if(s.width<=0||s.height<=0||videoFrame.width<=0||videoFrame.height<=0)return;overlay.math=RoiMath(s.width,s.height,videoFrame.width,videoFrame.height,s.rotation,s.sar,crop?:RoiRect(0.0,0.0,s.width.toDouble(),s.height.toDouble()));overlay.current=crop;overlay.invalidate()}
 private fun preview(us:Long,end:Boolean,restore:Boolean?=null){val now=SystemClock.elapsedRealtime();if(end||now-lastPreview>=150){lastPreview=now;lastSeekAt=now;session.seek(us,end,restore)}}
 private fun relative(delta:Long){if(touchLocked)return;session.seek(((s.positionUs?:0)+delta).coerceAtLeast(0));lastSeekAt=SystemClock.elapsedRealtime();wake()}
 private fun updateVolume(){if(::volume.isInitialized)volume.progress=audio.getStreamVolume(AudioManager.STREAM_MUSIC)}
 private fun wake(){showControls(true);handler.removeCallbacks(hide);handler.postDelayed(hide,3000)}
 private fun showControls(value:Boolean){controls=value;top.visibility=if(value&&!touchLocked)View.VISIBLE else View.GONE;bottom.visibility=if(value||touchLocked)View.VISIBLE else View.GONE;time.visibility=if(touchLocked)View.GONE else View.VISIBLE;seek.isEnabled=!touchLocked&&s.seekable;volume.isEnabled=!touchLocked;playButton.isEnabled=!touchLocked;speedButton.isEnabled=!touchLocked;if(value)handler.removeCallbacks(hide)}
 private fun beginRoi(){if(touchLocked||s.width<=0)return;priorPause=s.paused;session.pause(true);selectionRow.visibility=View.VISIBLE;applyButton.isEnabled=false;overlay.begin();wake()}
 private fun resetView(){overlay.cancel();selectionRow.visibility=View.GONE;crop=null;overlay.current=null;session.viewport(null,userRotation);enhancement=enhancement.copy(locked=false);refreshMath()}
 private fun speedPanel(){val speeds=doubleArrayOf(.25,.5,.75,1.0,1.25,1.5,2.0);AlertDialog.Builder(this).setTitle("播放速度").setSingleChoiceItems(speeds.map{"${it}×"}.toTypedArray(),speeds.indexOfFirst{it==s.speed}){d,i->session.speed(speeds[i]);d.dismiss()}.show()}
 private fun enhancePanel(){
  val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16.dp,4.dp,16.dp,8.dp)}
  val modes=Spinner(this);modes.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("原画","自动均衡","弱光增强","极弱光增强","流畅优先"));modes.setSelection(enhancement.mode);panel.addView(modes,LinearLayout.LayoutParams(-1,48.dp));modes.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long){enhancement=enhancement.copy(mode=i,bypass=false,locked=false);sendEnhancement()}}
  fun slider(label:String,min:Int,max:Int,value:Int,apply:(Int)->Unit){val title=text("$label：$value",13f);panel.addView(title);val bar=SeekBar(this).apply{this.max=max-min;progress=value-min};panel.addView(bar,LinearLayout.LayoutParams(-1,42.dp));bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){};override fun onProgressChanged(b:SeekBar,v:Int,user:Boolean){if(user){title.text="$label：${v+min}";apply(v+min);sendEnhancement()}}})}
  slider("曝光偏移（百分之一EV）",-100,100,(enhancement.manualEv*100).toInt()){enhancement=enhancement.copy(manualEv=it/100f,locked=false)}
  slider("暗部／局部提亮",0,100,enhancement.shadows.toInt()){enhancement=enhancement.copy(shadows=it.toFloat())}
  slider("对比度",-25,25,enhancement.contrast.toInt()){enhancement=enhancement.copy(contrast=it.toFloat())}
  slider("饱和度",0,150,enhancement.saturation.toInt()){enhancement=enhancement.copy(saturation=it.toFloat())}
  slider("保边空间降噪",0,100,enhancement.denoise.toInt()){enhancement=enhancement.copy(denoise=it.toFloat())}
  slider("细节（有噪声门控）",0,30,enhancement.detail.toInt()){enhancement=enhancement.copy(detail=it.toFloat())}
  panel.addView(CheckBox(this).apply{text="原画对比（选区与倍数不变）";isChecked=enhancement.bypass;setOnCheckedChangeListener{_,v->enhancement=enhancement.copy(bypass=v);sendEnhancement()}})
  panel.addView(CheckBox(this).apply{text="锁定当前曝光（重选区域后解除）";isChecked=enhancement.locked;isEnabled=s.receipt!=null;setOnCheckedChangeListener{_,v->enhancement=enhancement.copy(locked=v);sendEnhancement()}})
  val scroll=ScrollView(this);scroll.addView(panel);AlertDialog.Builder(this).setTitle("视频像素增强 · 非屏幕背光").setView(scroll).setPositiveButton("完成",null).setNeutralButton("重置"){_,_->enhancement=EnhanceSettings();quality=0;sendEnhancement()}.show()
 }
 private fun toggleClip(){clipPanel.visibility=if(clipPanel.visibility==View.VISIBLE)View.GONE else View.VISIBLE;if(clipPanel.visibility==View.VISIBLE){s.durationUs?.takeIf{it>0}?.let{if(clipRange==null)clipRange=ClipRange(0,it)};updateClip()}else{loop=false;session.setLoop(null)}}
 private fun updateClip(){val r=clipRange?:return;timeline.durationUs=s.durationUs?:0;timeline.range=r;timeline.positionUs=s.positionUs?:0;timeline.invalidate();clipText.text="A ${formatTime(r.startUs,true)}    B ${formatTime(r.endUs,true)}    选中 ${formatTime(r.endUs-r.startUs,true)}${if(loop)" · 循环" else ""}"}
 private fun setMark(start:Boolean){val d=s.durationUs?:return;val r=clipRange?:ClipRange(0,d);val p=(s.positionUs?:0).coerceIn(0,d);clipRange=if(start)ClipRange(p.coerceAtMost(d-1),if(r.endUs<=p)d else r.endUs)else ClipRange(if(r.startUs>=p)0 else r.startUs,p.coerceAtLeast(1));if(loop)session.setLoop(clipRange);updateClip()}
 private fun toggleLoop(){val r=clipRange?:return;loop=!loop;session.setLoop(if(loop)r else null);if(loop){session.seek(r.startUs);session.pause(false)};updateClip()}
 private fun timeInput(){val r=clipRange?:return;val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16.dp,0,16.dp,0)};val a=EditText(this).apply{setText(formatTime(r.startUs,true));hint="起点 HH:MM:SS.ffffff"};val b=EditText(this).apply{setText(formatTime(r.endUs,true));hint="终点 HH:MM:SS.ffffff"};box.addView(a);box.addView(b);val d=AlertDialog.Builder(this).setTitle("输入剪辑范围").setView(box).setPositiveButton("应用",null).setNegativeButton("取消",null).create();d.setOnShowListener{d.getButton(-1).setOnClickListener{val x=parseTime(a.text.toString());val y=parseTime(b.text.toString());if(x==null||y==null||!ClipRange(x,y).valid(s.durationUs)){a.error="需满足 0 ≤ A < B ≤ 总时长"}else{clipRange=ClipRange(x,y);updateClip();if(loop)session.setLoop(clipRange);d.dismiss()}}};d.show()}
 private fun more(){val items=arrayOf("切换音轨","切换字幕","加载外部字幕","保存当前画面","逐帧前进","逐帧后退","锁定／自动屏幕方向","控制栏常显／自动隐藏","诊断信息")
  AlertDialog.Builder(this).setTitle("更多").setItems(items){_,i->when(i){0->tracks("audio");1->tracks("sub");2->startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),3);3->capture();4->session.frame(true);5->session.frame(false);6->requestedOrientation=if(requestedOrientation==ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;7->{alwaysControls=!alwaysControls;wake()};8->session.diagnostic{message(it+"\n本测试版未完成 Mate 20 X 真机长时验收。热状态：${thermal?:"不可用"}")}}}.show()
 }
 private fun tracks(type:String){val list=s.tracks.filter{it.type==type};val labels=arrayOf("关闭")+list.map{"${it.id} · ${it.title}"};AlertDialog.Builder(this).setTitle(if(type=="audio")"音轨" else "字幕").setItems(labels){_,i->session.selectTrack(if(type=="audio")"audio" else "sub",if(i==0)null else list[i-1].id)}.show()}
 private fun requestedTracks():IntArray {fun id(type:String):Int{val all=s.tracks.filter{it.type==type};val selected=all.firstOrNull{it.selected};if(selected==null)return if(type=="video")-1 else -2;return selected.ffIndex?:if(all.size==1)-1 else throw IllegalStateException("多轨道编号未确认，请重新打开视频后选择轨道")};return intArrayOf(id("video"),id("audio"),id("sub"))}
 private fun startProgress(title:String){busy=true;cancel=AtomicBoolean(false);session.pause(true);dialog=ProgressDialog(this).apply{setTitle(title);setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);max=100;setCancelable(false);setButton(DialogInterface.BUTTON_NEGATIVE,"取消"){_,_->cancelJob()};show()};wake()}
 private fun closeProgress(){dialog?.dismiss();dialog=null}
 private fun cancelJob(){cancel.set(true);NativeExporter.cancel();exact?.cancel();status.text="正在安全取消…"}
 private fun failJob(t:Throwable){closeProgress();busy=false;temporary?.delete();temporary=null;exact=null;session.resumeAfterExport();message("未完成：${t.message}")}
 private fun rangeReady():ClipRange?{val r=clipRange;if(busy){message("已有导出任务，请先完成或取消");return null};if(r==null||!r.valid(s.durationUs)){message("请先选择有效的 A／B 范围");return null};return r}
 private fun exportCopy(){val r=rangeReady()?:return;val source=uri?:return;val ids=try{requestedTracks()}catch(e:Exception){message(e.message?:"轨道错误");return};startProgress("分析可独立解码的边界")
  val poll=object:Runnable{override fun run(){if(!busy||dialog==null)return;dialog?.progress=(NativeExporter.progress()*100).toInt();handler.postDelayed(this,400)}};handler.post(poll)
  access.openRead(source){opened->opened.onSuccess{lease->work.execute{val result=runCatching{val o=JSONObject(NativeExporter.analyze(lease.fd,ids[0],ids[1],ids[2],r.startUs,r.endUs));if(o.has("error"))error(o.getString("error"));o};lease.close();handler.post{closeProgress();if(cancel.get()){busy=false;return@post};result.onSuccess{plan->confirmCopy(plan)}.onFailure{failJob(it)}}}}.onFailure{failJob(it)}}
 }
 private fun confirmCopy(plan:JSONObject){
  val a=plan.getLong("startUs");val b=plan.getLong("endUs");val container=plan.getString("container")
  AlertDialog.Builder(this).setTitle("原码流导出确认").setMessage("请求：${formatTime(plan.getLong("requestedStartUs"),true)} — ${formatTime(plan.getLong("requestedEndUs"),true)}\n实际安全区间：${formatTime(a,true)} — ${formatTime(b,true)}\n起止向外扩至安全解码边界，可能包含额外内容。\n不重新编码；不会写入播放增强、放大或临时旋转。\n不保留轨道：${plan.optString("omitted").ifEmpty{"无"}}\n输出：${if(container=="mp4")"MP4" else "MKV"}")
   .setNegativeButton("取消"){_,_->busy=false}.setPositiveButton("导出"){_,_->writeCopy(plan,container)}.setNeutralButton("改用 MKV"){_,_->writeCopy(plan,"matroska")}.setOnCancelListener{busy=false}.show()
 }
 private fun writeCopy(plan:JSONObject,container:String){val source=uri?:return;startProgress("复制原始压缩数据");saveExtension=if(container=="mp4")"mp4" else "mkv";saveMime=if(container=="mp4")"video/mp4" else "video/x-matroska";val out=File(cacheDir,"clip-${System.nanoTime()}.$saveExtension");temporary=out
  access.openRead(source){result->result.onSuccess{lease->work.execute{val done=runCatching{val json=JSONObject(NativeExporter.write(lease.fd,plan.getInt("video"),plan.getInt("audio").let{if(it<0)-2 else it},plan.getInt("subtitle").let{if(it<0)-2 else it},plan.getLong("startUs"),plan.getLong("endUs"),out.absolutePath,container));if(json.has("error"))error(json.getString("error"));json.toString()};lease.close();handler.post{done.onSuccess{outputSummary="原码流导出\n${formatTime(plan.getLong("startUs"),true)} — ${formatTime(plan.getLong("endUs"),true)}\n$it";chooseDestination()}.onFailure{failJob(it)}}}}.onFailure{failJob(it)}}
  val poll=object:Runnable{override fun run(){if(!busy||dialog==null)return;dialog?.progress=(NativeExporter.progress()*100).toInt();handler.postDelayed(this,400)}};handler.post(poll)
 }
 private fun exportExact(){val r=rangeReady()?:return;val source=uri?:return
  if(s.hdr){message("首版精确导出仅验证 SDR；HDR 请使用原码流模式。");return}
  if(s.tracks.count{it.type=="video"}>1||s.tracks.count{it.type=="audio"}>1){message("当前精确模式不能可靠映射多视频／多音轨源，请先用原码流导出所选轨道，再打开该片段精确裁剪。不会默认导出错误音轨。");return}
  val hasAudio=s.tracks.any{it.type=="audio"&&it.selected};val bitrate=(s.width.toLong()*s.height*4).coerceIn(2_000_000,32_000_000).toInt()
  AlertDialog.Builder(this).setTitle("精确重编码导出").setMessage("范围：${formatTime(r.startUs,true)} — ${formatTime(r.endUs,true)}\nH.264 / AAC，目标码率 ${bitrate/1_000_000.0} Mbps，优先保持原尺寸。\n字幕不写入；无播放增强、区域裁剪或临时旋转。\n这不是原码流；设备不支持会停止，不静默降低分辨率。")
   .setNegativeButton("取消",null).setPositiveButton("开始"){_,_->startProgress("精确重编码");saveMime="video/mp4";saveExtension="mp4";val out=File(cacheDir,"exact-${System.nanoTime()}.mp4");temporary=out;session.suspendForExport{exact=ExactExporter(this);exact!!.start(source,r,out,hasAudio,bitrate,{dialog?.progress=it}){res->res.onSuccess{outputSummary=it;exact=null;session.resumeAfterExport();chooseDestination()}.onFailure{failJob(it)}}}}.show()
 }
 private fun capture(){if(busy)return;startProgress("保存当前视频画面");val out=File(cacheDir,"frame-${System.nanoTime()}.png");temporary=out;saveMime="image/png";saveExtension="png";session.screenshot(out){r->r.onSuccess{outputSummary="截图包括当前区域、像素增强和播放器已显示的字幕，不含 Android 控制按钮。";chooseDestination()}.onFailure{failJob(it)}}}
 private fun chooseDestination(){closeProgress();if(cancel.get()){failJob(IllegalStateException("已取消"));return};pickingDestination=true;startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(saveMime).putExtra(Intent.EXTRA_TITLE,"${fileName.substringBeforeLast('.')}-LumaView-${System.currentTimeMillis()}.$saveExtension"),4)}
 @Deprecated("Activity result") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==3&&resultCode==RESULT_OK){data?.data?.let{u->access.rememberGrant(u,data.flags);access.openRead(u){r->r.onSuccess{session.externalSubtitle(it)}.onFailure{message(it.message?:"字幕不可读")}}};return}
  if(requestCode==4){pickingDestination=false;val dest=data?.data;val temp=temporary;if(resultCode!=RESULT_OK||dest==null||temp==null){temp?.delete();temporary=null;busy=false;return};startProgress("写入新文件");work.execute{val r=runCatching{OutputTransaction.saveNew(this,temp,dest,uri,cancel){p->handler.post{dialog?.progress=(p*100).toInt()}}};handler.post{closeProgress();busy=false;temporary=null;temp.delete();r.onSuccess{message("已保存到系统选择的位置。\n$outputSummary")}.onFailure{message("保存失败：${it.message}")}}}}
 }
 override fun onSurfaceTextureAvailable(st:SurfaceTexture,w:Int,h:Int){session.attach(st,w,h)}
 override fun onSurfaceTextureSizeChanged(st:SurfaceTexture,w:Int,h:Int){session.attach(st,w,h);refreshMath()}
 override fun onSurfaceTextureDestroyed(st:SurfaceTexture):Boolean {session.detach(st);return false}
 override fun onSurfaceTextureUpdated(st:SurfaceTexture){}
 override fun onPause(){super.onPause();if(::session.isInitialized){session.pause(true);uri?.let{s.positionUs?.let{pos->getSharedPreferences("history",0).edit().putLong("position:$it",pos).apply()}}}}
 override fun onStop(){super.onStop();if(busy&&!pickingDestination&&!isChangingConfigurations)cancelJob()}
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(::session.isInitialized)session.close();if(busy)cancelJob();super.onDestroy()}
 @Deprecated("Back") override fun onBackPressed(){when{touchLocked->{touchLocked=false;overlay.locked=false;lockButton.text="锁定";wake()};overlay.selecting->{overlay.cancel();selectionRow.visibility=View.GONE;session.pause(priorPause)};clipPanel.visibility==View.VISIBLE->toggleClip();else->super.onBackPressed()}}
}
