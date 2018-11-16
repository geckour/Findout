package com.geckour.findout.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.databinding.DataBindingUtil
import com.geckour.findout.ClassifyResults
import com.geckour.findout.R
import com.geckour.findout.TFImageClassifier
import com.geckour.findout.databinding.ActivityIdentifyBinding
import com.geckour.findout.util.centerFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class IdentifyActivity : ScopedActivity() {

    private enum class RequestCode {
        PERMISSIONS_REQUEST,
        REQUEST_CODE_PICK_MEDIA
    }

    private enum class SourceMode {
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
        private const val INPUT_SIZE = 224L
        private const val IMAGE_MEAN = 128L
        private const val IMAGE_STD = 128.0f
        private const val INPUT_NAME = "input"
        private const val OUTPUT_NAME = "final_result"
        private const val MODEL_FILE = "file:///android_asset/graph.pb"
        private const val LABEL_FILE = "file:///android_asset/labels.txt"

        private const val THREAD_NAME_SURFACE = "thread_name_surface"

        private const val COLLECTING_TIME_TO_SUGGEST_MS = 2000
    }

    private val cameraManager: CameraManager by lazy { getSystemService(CameraManager::class.java) }
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var sensorOrientation: Int? = null
    private var previewSession: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val cameraLock = Semaphore(1)

    private var classifier: TFImageClassifier? = null

    private var sourceMode = SourceMode.CAMERA

    private var identifyJob: Job? = null

    private val collectedRecognitions: MutableList<Pair<TFImageClassifier.Recognition, Long>> = mutableListOf()

    private lateinit var binding: ActivityIdentifyBinding

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {}

    private var captureRequestBuilder: CaptureRequest.Builder? = null

    private val imageAvailableListener: (ImageReader) -> Unit = {
        var image: Image? = null

        val bitmap = try {
            image = it.acquireLatestImage()
            image?.use {
                if (identifyJob?.isActive != true) {
                    val bytes = it.planes[0].buffer.let { ByteArray(it.capacity()).apply { it.get(this) } }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
                            .centerFit(
                                    INPUT_SIZE.toInt(),
                                    INPUT_SIZE.toInt(),
                                    (windowManager.defaultDisplay.rotation.getDegreeFromSurfaceRotate()
                                            + (sensorOrientation ?: 0)).toFloat()
                            )
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            Timber.e(t)
            image?.close()
            null
        }

        if (bitmap != null) {
            identifyJob = launch(Dispatchers.IO) {
                classifier?.recognizeImage(bitmap)
                        ?.take(5)
                        ?.apply { applyRecognizeResult(this) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_identify)

        if (needToRequirePermissions()) {
            requestPermission()
        } else {
            onPermissionsGranted()
        }

        binding.fabSwitchSource.setOnClickListener { switchSource() }
        binding.buttonChangeMedia.setOnClickListener { enter() }
        binding.mediaPreview.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnTouchStateChangeListener { invokeMediaIdentify() }
        }
        binding.cameraPreview.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_POINTER_UP)
                return@setOnTouchListener true

            if (isAFSupported) {
                val sensorArraySize: Rect =
                        cameraManager.getCameraCharacteristics(
                                cameraDevice?.id ?: return@setOnTouchListener false)
                                .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                                ?: return@setOnTouchListener false

                val rotated: Boolean = sensorOrientation != 0 && sensorOrientation != 180
                val minSensorSideLength = min(sensorArraySize.width(), sensorArraySize.height())
                val scale = minSensorSideLength / binding.cameraPreview.width

                val adjustedTouchArea =
                        if (rotated)
                            Rect((sensorArraySize.width() - minSensorSideLength) / 2 + (max((event.x - sqrt(event.size) / 2) * scale, 0f)).toInt(),
                                    (sensorArraySize.height() - minSensorSideLength) / 2 + (max((event.y - sqrt(event.size) / 2) * scale, 0f)).toInt(),
                                    (sensorArraySize.width() - minSensorSideLength) / 2 + ((event.x + sqrt(event.size) / 2) * scale).toInt(),
                                    (sensorArraySize.height() - minSensorSideLength) / 2 + ((event.y + sqrt(event.size) / 2) * scale).toInt())
                        else Rect((sensorArraySize.height() - minSensorSideLength) / 2 + (max((event.y - sqrt(event.size) / 2) * scale, 0f)).toInt(),
                                (sensorArraySize.width() - minSensorSideLength) / 2 + (max((event.x - sqrt(event.size) / 2) * scale, 0f)).toInt(),
                                (sensorArraySize.height() - minSensorSideLength) / 2 + ((event.y + sqrt(event.size) / 2) * scale).toInt(),
                                (sensorArraySize.width() - minSensorSideLength) / 2 + ((event.x + sqrt(event.size) / 2) * scale).toInt())

                Timber.d("fgeck adjusted touch area: $adjustedTouchArea")

                val focusArea = MeteringRectangle(adjustedTouchArea, MeteringRectangle.METERING_WEIGHT_MAX)
                captureRequestBuilder?.apply {
                    previewSession?.stopRepeating()
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    previewSession?.capture(this.build(), captureCallback, handler)

                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    previewSession?.setRepeatingRequest(this.build(), captureCallback, handler)
                }
            }

            return@setOnTouchListener true
        }
    }

    override fun onResume() {
        super.onResume()

        enter(fromResume = true)
    }

    override fun onPause() {
        super.onPause()

        leave()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            RequestCode.PERMISSIONS_REQUEST.ordinal -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                    onPermissionsGranted()
                else requestPermission()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            RequestCode.REQUEST_CODE_PICK_MEDIA.ordinal -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.extractMediaBitmap()?.apply {
                        binding.mediaPreview.setImageBitmap(this)
                        invokeMediaIdentify()
                    }
                }
            }
        }
    }

    private val isAFSupported: Boolean
        get() =
            cameraDevice?.let {
                (cameraManager.getCameraCharacteristics(it.id)
                        .get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
                        ?: 0) > 0
            } ?: false

    private fun applyRecognizeResult(result: List<TFImageClassifier.Recognition>) {
        val now = System.currentTimeMillis()
        collectedRecognitions.removeAll {
            it.second < now - COLLECTING_TIME_TO_SUGGEST_MS
        }
        result.firstOrNull()?.apply {
            collectedRecognitions.add(this to now)
        }
        binding.suggest = getSuggestName()
        binding.results = ClassifyResults(result)
    }

    private fun getSuggestName(): String? =
            collectedRecognitions.map { it.first }
                    .groupBy { it }
                    .map { it.key to it.value.size }
                    .maxBy { it.second }
                    ?.first?.title

    private fun switchSource() {
        sourceMode = when (sourceMode) {
            SourceMode.CAMERA -> SourceMode.MEDIA
            SourceMode.MEDIA -> SourceMode.CAMERA
        }

        enter()
    }

    private fun enter(fromResume: Boolean = false) {
        leave()

        when (sourceMode) {
            SourceMode.CAMERA -> enterWithCamera()
            SourceMode.MEDIA -> {
                if (fromResume.not()) enterWithMedia()
            }
        }
    }

    private fun enterWithCamera() {
        startThread()

        if (binding.cameraPreview.isAvailable) {
            openCamera()
        } else {
            binding.cameraPreview.surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            openCamera()
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

    private fun enterWithMedia() {
        startThread()

        pickMedia()

        binding.fabSwitchSource.setImageResource(R.drawable.ic_camera)
        binding.cameraPreview.visibility = View.GONE
        binding.mediaPreview.visibility = View.VISIBLE
        binding.buttonChangeMedia.visibility = View.VISIBLE
    }

    private fun leave() {
        identifyJob?.cancel()
        collectedRecognitions.clear()
        closeCamera()
        stopThread()
    }

    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        startActivityForResult(intent, RequestCode.REQUEST_CODE_PICK_MEDIA.ordinal)
    }

    private fun invokeMediaIdentify() {
        if (identifyJob?.isActive == true) return

        binding.mediaPreviewWrapper.also { wrapper ->
            try {
                val bitmap: Bitmap = Bitmap.createScaledBitmap(
                        Bitmap.createBitmap(wrapper.width, wrapper.height,
                                Bitmap.Config.ARGB_8888).apply {
                            wrapper.draw(Canvas(this))
                        },
                        INPUT_SIZE.toInt(),
                        INPUT_SIZE.toInt(),
                        false
                )

                identifyJob = launch(Dispatchers.IO) {
                    classifier?.recognizeImage(bitmap)
                            ?.take(5)
                            ?.apply { applyRecognizeResult(this) }
                }
            } catch (t: Throwable) {
                Timber.e(t)
            }
        }
    }

    private fun requestPermission() {
        requestPermissions(
                arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE),
                RequestCode.PERMISSIONS_REQUEST.ordinal)
    }

    private fun onPermissionsGranted() {
        startIdentify()
    }

    private fun needToRequirePermissions(): Boolean =
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

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

    private fun startIdentify() {
        classifier =
                TFImageClassifier(
                        assets,
                        MODEL_FILE,
                        LABEL_FILE,
                        INPUT_SIZE,
                        IMAGE_MEAN,
                        IMAGE_STD,
                        INPUT_NAME,
                        OUTPUT_NAME)

        enter()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraId = setUpCamera()

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
                        createCameraPreviewSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraLock.release()
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        onDisconnected(camera)
                        Toast.makeText(this@IdentifyActivity, "Error: Camera state turn in error [$error].", Toast.LENGTH_SHORT).show()
                    }
                }

                if (needToRequirePermissions()) {
                    requestPermission()
                } else {
                    cameraManager.openCamera(this, stateCallback, handler)
                }
            } catch (t: Throwable) {
                Timber.e(t)
            }
        }
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

    private fun setUpCamera(): String? {
        try {
            cameraManager.cameraIdList
                    .forEach {
                        val characteristics = cameraManager.getCameraCharacteristics(it)

                        val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                        if (cameraDirection != null &&
                                cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
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

    private fun createCameraPreviewSession() {
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
                                    Toast.makeText(this@IdentifyActivity,
                                            "Error: Failed to create capture session's configure.",
                                            Toast.LENGTH_SHORT).show()
                                }

                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (cameraDevice == null) return

                                    previewSession = session
                                    this@apply.set(CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON)
                                    this@apply.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                                    previewSession?.setRepeatingRequest(this@apply.build(), captureCallback, handler)
                                }
                            }

                            cameraDevice?.createCaptureSession(listOf(surface, imageReader?.surface),
                                    captureStateCallback, handler)
                        }
            }
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    private fun List<Size>.chooseOptimalSize(
            preferredSize: Size
    ): Size {
        val shortSideLength = min(preferredSize.width, preferredSize.height)
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

    private fun Int.getDegreeFromSurfaceRotate(): Int =
            when (this) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> throw IllegalArgumentException("Use this method to Surface's constant. given value: $this")
            }

    private fun Intent.extractMediaBitmap(): Bitmap? =
            data?.let { MediaStore.Images.Media.getBitmap(contentResolver, it) }

}
