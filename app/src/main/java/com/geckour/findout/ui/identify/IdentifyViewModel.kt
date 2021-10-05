package com.geckour.findout.ui.identify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geckour.findout.ClassifyResults
import com.geckour.findout.R
import com.geckour.findout.TFImageClassifier
import com.geckour.findout.databinding.ActivityIdentifyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class IdentifyViewModel : ViewModel() {

    enum class SourceMode {
        CAMERA,
        MEDIA
    }

    companion object {
        /**
         * Max cameraPreview width that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_WIDTH = 1920
        /**
         * Max cameraPreview height that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_HEIGHT = 1080
        private val PREVIEW_SIZE = Size(640, 480)
        internal const val INPUT_SIZE = 224L
        private const val IMAGE_MEAN = 128L
        private const val IMAGE_STD = 128.0f
        private const val INPUT_NAME = "input"
        private const val OUTPUT_NAME = "final_result"
        private const val MODEL_FILE = "file:///android_asset/graph.pb"
        private const val LABEL_FILE = "file:///android_asset/labels.txt"

        private const val THREAD_NAME_SURFACE = "thread_name_surface"

        private const val COLLECTING_TIME_TO_SUGGEST_MS = 2000
    }

    internal var sensorOrientation: Int? = null
    internal var previewSession: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    internal var handler: Handler? = null
    private val cameraLock = Semaphore(1)
    private var imageReader: ImageReader? = null
    internal var cameraDevice: CameraDevice? = null

    internal val captureCallback = object : CameraCaptureSession.CaptureCallback() {}

    internal var captureRequestBuilder: CaptureRequest.Builder? = null

    private var classifier: TFImageClassifier? = null

    private var sourceMode = SourceMode.CAMERA

    private var identifyJob: Job? = null
    internal val isIdentifyJobActive get() = identifyJob?.isActive == true

    private val collectedRecognitions = mutableListOf<List<TFImageClassifier.Recognition>>()

    internal var mediaPickedFlag = false

    internal fun startIdentifyJob(bitmap: Bitmap, binding: ActivityIdentifyBinding) {
        identifyJob = viewModelScope.launch(Dispatchers.IO) {
            classifier?.recognizeImage(bitmap)
                ?.take(5)
                ?.let { applyRecognizeResult(it, binding) }
        }
    }

    private fun applyRecognizeResult(result: List<TFImageClassifier.Recognition>, binding: ActivityIdentifyBinding) {
        collectedRecognitions.add(result)
        if (collectedRecognitions.size > 20) collectedRecognitions.removeAt(0)
        binding.suggest = getSuggestName()
        binding.results = ClassifyResults(result)
    }

    private fun getSuggestName(): String? =
        collectedRecognitions
            .flatten()
            .groupBy { it.id }
            .map { (_, recognition) -> recognition.first().title to recognition.map { it.confidence }.sum() }
            .maxBy { it.second }
            ?.first

    internal fun toggleSource(
        activity: Activity,
        binding: ActivityIdentifyBinding,
        cameraManager: CameraManager,
        imageAvailableListener: (ImageReader) -> Unit
    ) {
        val sourceMode = when (sourceMode) {
            SourceMode.CAMERA -> SourceMode.MEDIA
            SourceMode.MEDIA -> SourceMode.CAMERA
        }
        switchSource(sourceMode, activity, binding, cameraManager, imageAvailableListener)
    }

    internal fun switchSource(
        sourceMode: SourceMode,
        activity: Activity,
        binding: ActivityIdentifyBinding,
        cameraManager: CameraManager,
        imageAvailableListener: (ImageReader) -> Unit
    ) {
        this.sourceMode = sourceMode
        if (sourceMode == SourceMode.MEDIA && mediaPickedFlag.not()) pickMedia(activity)
        startIdentify(activity, binding, cameraManager, imageAvailableListener)
    }

    private fun enter(
        activity: Activity,
        binding: ActivityIdentifyBinding,
        cameraManager: CameraManager,
        imageAvailableListener: (ImageReader) -> Unit
    ) {
        leave()

        when (sourceMode) {
            SourceMode.CAMERA -> enterWithCamera(cameraManager, activity, binding, imageAvailableListener)
            SourceMode.MEDIA -> enterWithMedia(binding)
        }
        mediaPickedFlag = false
    }

    private fun enterWithCamera(
        cameraManager: CameraManager,
        context: Context,
        binding: ActivityIdentifyBinding,
        imageAvailableListener: (ImageReader) -> Unit
    ) {
        startThread()

        if (binding.cameraPreview.isAvailable) {
            openCamera(context, binding, cameraManager, imageAvailableListener)
        } else {
            binding.cameraPreview.surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        openCamera(context, binding, cameraManager, imageAvailableListener)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
        }

        binding.fabSwitchSource.setImageResource(R.drawable.ic_media)
        binding.cameraPreview.visibility = View.VISIBLE
        binding.mediaPreview.visibility = View.GONE
        binding.buttonChangeMedia.visibility = View.GONE
    }

    private fun enterWithMedia(binding: ActivityIdentifyBinding) {
        startThread()

        binding.fabSwitchSource.setImageResource(R.drawable.ic_camera)
        binding.cameraPreview.visibility = View.GONE
        binding.mediaPreview.visibility = View.VISIBLE
        binding.buttonChangeMedia.visibility = View.VISIBLE
    }

    private fun pickMedia(activity: Activity) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        activity.startActivityForResult(intent, IdentifyActivity.RequestCode.REQUEST_CODE_PICK_MEDIA.ordinal)
    }

    internal fun leave() {
        identifyJob?.cancel()
        collectedRecognitions.clear()
        closeCamera()
        stopThread()
    }

    internal fun invokeMediaIdentify(binding: ActivityIdentifyBinding) {
        if (identifyJob?.isActive == true) return

        binding.mediaPreviewWrapper.also { wrapper ->
            try {
                val bitmap: Bitmap = Bitmap.createScaledBitmap(
                    Bitmap.createBitmap(
                        wrapper.width, wrapper.height,
                        Bitmap.Config.ARGB_8888
                    ).apply {
                        wrapper.draw(Canvas(this))
                    },
                    INPUT_SIZE.toInt(),
                    INPUT_SIZE.toInt(),
                    false
                )

                startIdentifyJob(bitmap, binding)
            } catch (t: Throwable) {
                Timber.e(t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    internal fun openCamera(
        context: Context,
        binding: ActivityIdentifyBinding,
        cameraManager: CameraManager,
        imageAvailableListener: (ImageReader) -> Unit
    ) {
        val cameraId = setUpCamera(cameraManager, binding, imageAvailableListener)

        cameraId?.apply {
            Timber.d("camera id: $this")
            try {
                if (cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS).not()) {
                    throw RuntimeException("Time out waiting to lock camera on opening.")
                }

                val stateCallback = object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraLock.release()
                        cameraDevice = camera
                        createCameraPreviewSession(context, binding)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraLock.release()
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        onDisconnected(camera)
                        Toast.makeText(context, "Error: Camera state turn in error [$error].", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                cameraManager.openCamera(this, stateCallback, handler)
            } catch (t: Throwable) {
                Timber.e(t)
            }
        }
    }

    private fun setUpCamera(
        cameraManager: CameraManager,
        binding: ActivityIdentifyBinding,
        imageAvailableListener: (ImageReader) -> Unit
    ): String? {
        try {
            cameraManager.cameraIdList
                .forEach {
                    val characteristics = cameraManager.getCameraCharacteristics(it)

                    val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (cameraDirection != null &&
                        cameraDirection == CameraCharacteristics.LENS_FACING_FRONT
                    ) {
                        return@forEach
                    }

                    val map: StreamConfigurationMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?: return@forEach

                    val previewSize =
                        map.getOutputSizes(SurfaceTexture::class.java).toList()
                            .chooseOptimalSize(
                                Size(
                                    if (PREVIEW_SIZE.width > MAX_PREVIEW_WIDTH)
                                        MAX_PREVIEW_WIDTH
                                    else PREVIEW_SIZE.width,
                                    if (PREVIEW_SIZE.height > MAX_PREVIEW_HEIGHT)
                                        MAX_PREVIEW_HEIGHT
                                    else PREVIEW_SIZE.height
                                )
                            )

                    imageReader =
                        ImageReader.newInstance(
                            previewSize.width,
                            previewSize.height,
                            ImageFormat.JPEG,
                            2
                        ).apply { setOnImageAvailableListener(imageAvailableListener, handler) }

                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    binding.cameraPreview.ratio = previewSize

                    return it
                }
            return null
        } catch (t: Throwable) {
            Timber.e(t)
            return null
        }
    }

    private fun createCameraPreviewSession(context: Context, binding: ActivityIdentifyBinding) {
        try {
            binding.cameraPreview.surfaceTexture.also {
                it.setDefaultBufferSize(binding.cameraPreview.ratio.width, binding.cameraPreview.ratio.height)

                val surface = Surface(it)
                captureRequestBuilder =
                    cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                        addTarget(surface)
                        addTarget(imageReader?.surface ?: return@apply)

                        val captureStateCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Toast.makeText(
                                    context,
                                    "Error: Failed to create capture session's configure.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            override fun onConfigured(session: CameraCaptureSession) {
                                if (cameraDevice == null) return

                                previewSession = session
                                this@apply.set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )
                                this@apply.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )

                                previewSession?.setRepeatingRequest(this@apply.build(), captureCallback, handler)
                            }
                        }

                        cameraDevice?.createCaptureSession(
                            listOf(surface, imageReader?.surface),
                            captureStateCallback, handler
                        )
                    }
            }
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    internal fun toggleLight(isFlashSupported: Boolean, binding: ActivityIdentifyBinding) {
        if (isFlashSupported) {
            binding.lightEnabled = binding.lightEnabled?.not() ?: true
            captureRequestBuilder?.apply {
                previewSession?.stopRepeating()
                set(
                    CaptureRequest.FLASH_MODE,
                    if (binding.lightEnabled == true) CaptureRequest.FLASH_MODE_TORCH
                    else CaptureRequest.FLASH_MODE_OFF
                )
                previewSession?.setRepeatingRequest(this.build(), captureCallback, handler)
            }
        }
    }

    private fun startThread() {
        stopThread()

        thread = HandlerThread(THREAD_NAME_SURFACE).apply { start() }
        handler = Handler(thread?.looper)
    }

    private fun stopThread() {
        thread?.quitSafely()

        try {
            thread?.join()
            thread = null
            handler = null
        } catch (e: InterruptedException) {
            Timber.e(e)
        }
    }

    internal fun startIdentify(
        activity: Activity,
        binding: ActivityIdentifyBinding,
        cameraManager: CameraManager,
        imageAvailableListener: (ImageReader) -> Unit
    ) {
        classifier =
            TFImageClassifier(
                activity.assets,
                MODEL_FILE,
                LABEL_FILE,
                INPUT_SIZE,
                IMAGE_MEAN,
                IMAGE_STD,
                INPUT_NAME,
                OUTPUT_NAME
            )

        enter(activity, binding, cameraManager, imageAvailableListener)
    }

    private fun closeCamera() {
        if (cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS).not()) {
            cameraLock.release()
            throw RuntimeException("Time out waiting to lock camera on closing.")
        }

        try {
            previewSession?.close()
            previewSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null
        } catch (t: Throwable) {
            Timber.e(t)
        }

        cameraLock.release()
    }

    private fun List<Size>.chooseOptimalSize(
        preferredSize: Size
    ): Size {
        val shortSideLength = kotlin.math.min(preferredSize.width, preferredSize.height)
        val bigEnough = ArrayList<Size>()
        val notBigEnough = ArrayList<Size>()

        this.forEach {
            if (it == preferredSize) return it

            (if (it.width >= shortSideLength && it.height >= shortSideLength) bigEnough
            else notBigEnough)
                .add(it)
        }

        return when {
            bigEnough.isNotEmpty() -> bigEnough.minBy { it.width * it.height } ?: this[0]
            notBigEnough.isNotEmpty() -> notBigEnough.maxBy { it.width * it.height } ?: this[0]
            else -> {
                Timber.e("Error: Couldn't find any suitable cameraPreview size.")
                this[0]
            }
        }
    }
}