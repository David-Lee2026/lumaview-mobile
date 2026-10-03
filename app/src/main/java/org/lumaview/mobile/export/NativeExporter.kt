package org.lumaview.mobile.export
object NativeExporter {
 external fun analyze(fd:Int,video:Int,audio:Int,subtitle:Int,startUs:Long,endUs:Long):String
 external fun write(fd:Int,video:Int,audio:Int,subtitle:Int,startUs:Long,endUs:Long,path:String,container:String):String
 external fun cancel()
 external fun progress():Double
}
