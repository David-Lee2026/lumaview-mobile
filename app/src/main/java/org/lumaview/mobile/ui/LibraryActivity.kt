package org.lumaview.mobile.ui
import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.lumaview.mobile.storage.*

class LibraryActivity:Activity(){
 private lateinit var access:DocumentAccess;private lateinit var rows:LinearLayout;private var tree:Uri?=null;private val stack=ArrayList<String>();private lateinit var status:TextView
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);access=DocumentAccess(this)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20.dp,24.dp,20.dp,12.dp);setBackgroundColor(0xff0e1725.toInt())}
  root.addView(TextView(this).apply{text="LumaView Mobile";textSize=26f;setTextColor(0xffedf6ff.toInt())});root.addView(TextView(this).apply{text="光影增强播放器 · 0.1.0-test";textSize=15f;setTextColor(0xff55d4c5.toInt());setPadding(0,8.dp,0,16.dp)})
  fun button(title:String,action:()->Unit){root.addView(Button(this).apply{text=title;minHeight=52.dp;setOnClickListener{action()}},LinearLayout.LayoutParams(-1,-2))}
  button("打开视频"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),1)}
  button("授权一个视频文件夹"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),2)}
  button("最近播放"){showRecent()};button("清除最近记录"){getSharedPreferences("history",0).edit().clear().apply();showRecent()}
  status=TextView(this).apply{text="本地处理 · 无账号与云端上传\n请用系统选择器授权视频。原始文件只读。";textSize=14f;setTextColor(0xffa8bbd1.toInt());setPadding(0,12.dp,0,12.dp)};root.addView(status)
  rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val scroll=ScrollView(this);scroll.addView(rows);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
  root.setOnApplyWindowInsetsListener{v,i->v.setPadding(20.dp,i.systemWindowInsetTop+12.dp,20.dp,i.systemWindowInsetBottom+12.dp);i};showRecent()
 }
 private fun play(uri:Uri){startActivity(Intent(this,PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
 private fun showRecent(){rows.removeAllViews();val p=getSharedPreferences("history",0);val recent=runCatching{JSONArray(p.getString("recent","[]"))}.getOrDefault(JSONArray());for(i in 0 until recent.length()){val o=recent.getJSONObject(i);rows.addView(Button(this).apply{text=o.optString("name","视频");isAllCaps=false;minHeight=48.dp;setOnClickListener{play(Uri.parse(o.getString("uri")))}})}}
 private fun list(parent:String?=null){val t=tree?:return;status.text="读取已授权文件夹…";access.listChildren(t,parent){r->if(isFinishing)return@listChildren;rows.removeAllViews();r.onSuccess{items->status.text="${items.size} 个条目 · 仅列出当前目录";if(stack.isNotEmpty())rows.addView(Button(this).apply{text="← 上一级";setOnClickListener{stack.removeAt(stack.lastIndex);list(stack.lastOrNull())}});items.forEach{e->rows.addView(Button(this).apply{text=(if(e.directory)"📁 " else "▶ ")+e.name;isAllCaps=false;minHeight=48.dp;setOnClickListener{if(e.directory){val id=DocumentsContract.getDocumentId(e.uri);stack.add(id);list(id)}else play(e.uri)}})}}.onFailure{status.text="读取失败：${it.message}"}}}
 @Deprecated("Activity result") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return;val uri=data?.data?:return;access.rememberGrant(uri,data.flags);if(requestCode==1)play(uri)else if(requestCode==2){tree=uri;stack.clear();list()}}
 private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
}
