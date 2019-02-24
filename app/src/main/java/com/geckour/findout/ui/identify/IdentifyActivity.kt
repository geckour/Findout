package com.geckour.findout.ui.identify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.Surface
import android.widget.ImageView
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProviders
import com.geckour.findout.R
import com.geckour.findout.databinding.ActivityIdentifyBinding
import com.geckour.findout.ui.CrashlyticsEnabledActivity
import com.geckour.findout.util.centerFit
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.OnNeverAskAgain
import permissions.dispatcher.OnPermissionDenied
import permissions.dispatcher.RuntimePermissions
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@RuntimePermissions
class IdentifyActivity : CrashlyticsEnabledActivity() {

    internal enum class RequestCode {
        REQUEST_CODE_PICK_MEDIA
    }

    private val viewModel: IdentifyViewModel by lazy {
        ViewModelProviders.of(this)[IdentifyViewModel::class.java]
    }
    private val cameraManager: CameraManager by lazy {
        getSystemService(CameraManager::class.java)
    }
    private lateinit var binding: ActivityIdentifyBinding

    private val imageAvailableListener: (ImageReader) -> Unit = {
        val bitmap =
            it.acquireLatestImage()?.use {
                try {
                    if (viewModel.isIdentifyJobActive.not()) {
                        val bytes = it.planes[0].buffer.let {
                            ByteArray(it.capacity()).apply { it.get(this) }
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
                            .centerFit(
                                IdentifyViewModel.INPUT_SIZE.toInt(),
                                IdentifyViewModel.INPUT_SIZE.toInt(),
                                (windowManager.defaultDisplay.rotation.getDegreeFromSurfaceRotate()
                                        + (viewModel.sensorOrientation ?: 0)).toFloat()
                            )
                    } else null
                } catch (t: Throwable) {
                    Timber.e(t)
                    null
                }
            }
        if (bitmap != null) {
            viewModel.startIdentifyJob(bitmap, binding)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_identify)

        binding.fabSwitchSource.setOnClickListener {
            viewModel.toggleSource(this, binding, cameraManager, imageAvailableListener)
        }
        binding.buttonChangeMedia.setOnClickListener {
            switchSourceWithPermissionCheck(IdentifyViewModel.SourceMode.MEDIA)
        }
        binding.mediaPreview.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnTouchStateChangeListener { viewModel.invokeMediaIdentify(binding) }
        }
        binding.cameraPreview.setOnTouchListener { v, event ->
            if (event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_POINTER_UP)
                return@setOnTouchListener true

            if (isAFSupported) {
                val sensorArraySize: Rect =
                    cameraManager.getCameraCharacteristics(
                        viewModel.cameraDevice?.id ?: return@setOnTouchListener false
                    )
                        .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        ?: return@setOnTouchListener false

                val rotated: Boolean = viewModel.sensorOrientation != 0 && viewModel.sensorOrientation != 180
                val minSensorSideLength = min(sensorArraySize.width(), sensorArraySize.height())
                val scale = minSensorSideLength / v.width

                val marginLeft = (sensorArraySize.width() - minSensorSideLength) / 2
                val marginTop = (sensorArraySize.height() - minSensorSideLength) / 2
                val tapRect = sqrt(event.size).let { RectF(-it / 2, -it / 2, it / 2, it / 2) }
                val tapX = event.x
                val tapY = v.height - event.y
                val adjustedTouchArea =
                    if (rotated)
                        Rect(
                            marginLeft + (max((tapX + tapRect.left) * scale, 0f)).toInt(),
                            marginTop + (max((tapY + tapRect.top) * scale, 0f)).toInt(),
                            marginLeft + ((tapX + tapRect.right) * scale).toInt(),
                            marginTop + ((tapY + tapRect.bottom) * scale).toInt()
                        )
                    else Rect(
                        marginTop + (max((tapY + tapRect.left) * scale, 0f)).toInt(),
                        marginLeft + (max((tapX - tapRect.top) * scale, 0f)).toInt(),
                        marginTop + ((tapY + tapRect.right) * scale).toInt(),
                        marginLeft + ((tapX + tapRect.bottom) * scale).toInt()
                    )

                Timber.d("fgeck adjusted touch area: $adjustedTouchArea")

                val focusArea = MeteringRectangle(adjustedTouchArea, MeteringRectangle.METERING_WEIGHT_MAX)
                viewModel.captureRequestBuilder?.apply {
                    viewModel.previewSession?.stopRepeating()
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    viewModel.previewSession?.capture(this.build(), viewModel.captureCallback, viewModel.handler)

                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusArea))
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    viewModel.previewSession?.setRepeatingRequest(
                        this.build(),
                        viewModel.captureCallback,
                        viewModel.handler
                    )
                }
            }

            return@setOnTouchListener true
        }

        binding.toggleLight.setOnClickListener { viewModel.toggleLight(isFlashSupported, binding) }
    }

    override fun onResume() {
        super.onResume()

        startRecognitionWithPermissionCheck()
    }

    override fun onPause() {
        super.onPause()

        viewModel.leave()
    }

    @NeedsPermission(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    @OnPermissionDenied(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    internal fun startRecognition() {
        viewModel.startIdentify(this, binding, cameraManager, imageAvailableListener)
    }

    @NeedsPermission(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    internal fun switchSource(sourceMode: IdentifyViewModel.SourceMode) {
        viewModel.switchSource(sourceMode, this, binding, cameraManager, imageAvailableListener)
    }

    @OnNeverAskAgain(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    internal fun onPermissionNeverAskAgain() {
        // TODO: Show button to transit to app settings
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            RequestCode.REQUEST_CODE_PICK_MEDIA.ordinal -> {
                viewModel.mediaPickedFlag = true

                if (resultCode == Activity.RESULT_OK) {
                    data?.extractMediaBitmap()?.apply {
                        binding.mediaPreview.setImageBitmap(this)
                        viewModel.invokeMediaIdentify(binding)
                    }
                } else {
                    switchSourceWithPermissionCheck(
                        if ((binding.mediaPreview.drawable as? BitmapDrawable)?.bitmap == null)
                            IdentifyViewModel.SourceMode.CAMERA
                        else IdentifyViewModel.SourceMode.MEDIA
                    )
                }
            }
        }
    }

    private val isAFSupported: Boolean
        get() =
            viewModel.cameraDevice?.let {
                (cameraManager.getCameraCharacteristics(it.id)
                    .get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
                    ?: 0) > 0
            } ?: false

    private val isFlashSupported: Boolean
        get() =
            viewModel.cameraDevice?.let {
                cameraManager.getCameraCharacteristics(it.id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            } ?: false

    private fun Intent.extractMediaBitmap(): Bitmap? =
        data?.let {
            MediaStore.Images.Media.getBitmap(contentResolver, it)
                ?.rotate(it.getRotation())
        }

    private fun Uri.getRotation(): Int =
        contentResolver.openInputStream(this)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

    private fun Bitmap.rotate(orientation: Int): Bitmap =
        Bitmap.createBitmap(
            this,
            0, 0,
            this.width, this.height,
            Matrix().apply {
                postRotate(
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                )
            },
            false
        )

    private fun Int.getDegreeFromSurfaceRotate(): Int =
        when (this) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> throw IllegalArgumentException("Use this method to Surface's constant. given value: $this")
        }
}
