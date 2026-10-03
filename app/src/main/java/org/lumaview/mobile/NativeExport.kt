package org.lumaview.mobile

object NativeExport {
    init { System.loadLibrary("lumaexport") }
    external fun probe(path:String, startUs:Long, videoIndex:Int):String
    external fun remux(path:String, output:String, startUs:Long, endUs:Long, videoIndex:Int, audioIndex:Int):String
    external fun cancel()
}
