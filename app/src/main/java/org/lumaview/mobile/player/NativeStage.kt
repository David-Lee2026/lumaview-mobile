package org.lumaview.mobile.player
/** These functions are implemented in our recompiled JNI library, not stock libmpv. */
object NativeStage {
 external fun submit(values:FloatArray,media:Long,surface:Long,view:Long,request:Long,reset:Boolean):Int
 external fun receipt():DoubleArray?
 external fun releaseSurfaceAfterShutdown()
}
