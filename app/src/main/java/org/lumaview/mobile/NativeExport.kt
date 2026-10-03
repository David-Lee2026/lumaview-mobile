package org.lumaview.mobile
object NativeExport {
    init { System.loadLibrary("lumaexport") }
    external fun begin():Long
    external fun probe(path:String,startUs:Long,endUs:Long,videoIndex:Int,token:Long):String
    external fun remux(path:String,output:String,startUs:Long,endUs:Long,videoIndex:Int,audioIndex:Int,token:Long):String
    external fun cancel()
}
