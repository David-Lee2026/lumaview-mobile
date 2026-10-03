package org.lumaview.mobile

import kotlin.math.abs
import kotlin.math.max

/** UI clock only. Export ranges and player seeks always use native timestamps. */
class PlaybackProgress {
 private var generation=Long.MIN_VALUE
 private var anchor:Long?=null;private var anchorMs=0L;private var shown=0L
 private var lastSample:Long?=null;private var duration:Long?=null
 private var paused=true;private var speed=1.0
 private var pending:Long?=null;private var pendingUntil=0L
 fun sample(media:Long,position:Long?,length:Long?,stopped:Boolean,rate:Double,now:Long){
  if(media!=generation){generation=media;anchor=null;lastSample=null;duration=null;shown=0;pending=null}
  duration=length?.takeIf{it>0}?:duration
  val current=this.position(now)
  if(position!=null&&position>=0){
   if(pending!=null&&abs(position-pending!!)>500_000&&now<pendingUntil){paused=stopped;speed=rate;return}
   pending=null
   val discontinuity=current==null||abs(position-current)>1_000_000
   if(discontinuity||stopped){anchor=position;shown=position;anchorMs=now}
   else if((lastSample==null||position>lastSample!!)||stopped!=paused||rate!=speed){anchor=if(stopped)max(shown,position)else position;anchorMs=now}
   lastSample=position
  }else if(current!=null&&(stopped!=paused||rate!=speed)){anchor=current;anchorMs=now}
  paused=stopped;speed=rate.coerceIn(.25,2.0)
 }
 fun seek(position:Long,now:Long){anchor=position.coerceAtLeast(0);shown=anchor!!;anchorMs=now;pending=anchor;pendingUntil=now+2000}
 fun position(now:Long):Long? {
  val base=anchor?:return null
  val elapsed=if(paused)0L else (now-anchorMs).coerceIn(0,350)
  val predicted=base+(elapsed*1000.0*speed).toLong()
  shown=max(shown,predicted).coerceAtMost(duration?:Long.MAX_VALUE)
  return shown
 }
}
