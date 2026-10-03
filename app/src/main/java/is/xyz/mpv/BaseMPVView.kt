package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    var dispatch: ((() -> Unit) -> Unit) = { it() }
    @Volatile private var closing=false
    @Volatile private var ready=false
    @Volatile private var surfaceGeneration=0L
    private fun serial(block:()->Unit) { dispatch { if(ready&&!closing)block() } }
    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        MPVLib.create(context.applicationContext)

        /* set normal options (user-supplied config can override) */
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            MPVLib.setOptionString(opt, cacheDir)
        initOptions()

        MPVLib.init()

        /* set hardcoded options */
        postInitOptions()
        // could mess up VO init before surfaceCreated() is called
        MPVLib.setOptionString("force-window", "no")
        // need to idle at least once for playFile() logic to work
        MPVLib.setOptionString("idle", "once")

        ready=true
        post {
            if(!closing){
                holder.addCallback(this)
                // addCallback does not replay a Surface created during async initialization.
                if(holder.surface.isValid){surfaceCreated(holder);surfaceChanged(holder,0,width,height)}
            }
        }
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable surface callbacks to avoid using uninitialized mpv state
        holder.removeCallback(this)

        MPVLib.destroy()
        ready=false
    }

    fun prepareDestroy() {
        closing=true
        holder.removeCallback(this)
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()

    protected abstract fun observeProperties()

    @Volatile private var filePath: String? = null

    /**
     * Set the first file to be played once the player is ready.
     */
    fun playFile(filePath: String) {
        this.filePath = filePath
    }

    private var voInUse: String = "gpu"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val g=surfaceGeneration
        serial { if(g==surfaceGeneration)MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    }
    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface=holder.surface
        val g=++surfaceGeneration
        serial {
            if(g!=surfaceGeneration || !surface.isValid)return@serial
            MPVLib.attachSurface(surface)
            MPVLib.setOptionString("force-window", "yes")
            val path=filePath
            if(path!=null){MPVLib.command(arrayOf("loadfile",path));filePath=null}
            else MPVLib.setPropertyString("vo",voInUse)
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceGeneration++
        // SurfaceView requires stopping the old VO before returning this callback.
        // With config=no this synchronous property change joins VO destruction.
        if(ready&&!closing) {
            MPVLib.setPropertyString("vo","null")
            MPVLib.setPropertyString("force-window","no")
            MPVLib.detachSurface()
        }
    }

    companion object {
        private const val TAG = "mpv"
    }
}
