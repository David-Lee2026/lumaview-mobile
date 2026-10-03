package org.lumaview.mobile.export
object NativeExporter {
 external fun begin():Long
 external fun analyze(fd:Int,video:Int,audio:Int,subtitle:Int,startUs:Long,endUs:Long,token:Long):String
 external fun write(fd:Int,video:Int,audio:Int,subtitle:Int,startUs:Long,endUs:Long,path:String,container:String,token:Long):String
 external fun cancel()
 external fun progress():Double
}
