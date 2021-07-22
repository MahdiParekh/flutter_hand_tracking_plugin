package xyz.zhzh.flutter_hand_tracking_plugin

import android.app.Activity
import androidx.camera.core.CameraX
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.annotation.NonNull
import com.google.mediapipe.components.*
import com.google.mediapipe.formats.proto.DetectionProto
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.glutil.EglManager
import com.google.protobuf.InvalidProtocolBufferException
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.plugin.platform.PlatformView

/** FlutterHandTrackingPlugin */
class FlutterHandTrackingPlugin(r: Registrar, id: Int) : PlatformView, MethodCallHandler {
    companion object {
        private const val TAG = "HandTrackingPlugin"
        private const val NAMESPACE = "plugins.zhzh.xyz/flutter_hand_tracking_plugin"
        private const val BINARY_GRAPH_NAME = "handtrackinggpu.binarypb"
        private const val INPUT_VIDEO_STREAM_NAME = "input_video"
        private const val OUTPUT_VIDEO_STREAM_NAME = "output_video"
        private const val OUTPUT_DETECTIONS_STREAM_NAME = "palm_detections"
        private val CAMERA_FACING = CameraHelper.CameraFacing.FRONT
        // Flips the camera-preview frames vertically before sending them into FrameProcessor to be
        // processed in a MediaPipe graph, and flips the processed frames back when they are displayed.
        // This is needed because OpenGL represents images assuming the image origin is at the bottom-left
        // corner, whereas MediaPipe in general assumes the image origin is at top-left.
        private const val FLIP_FRAMES_VERTICALLY = true
        

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            registrar.platformViewRegistry().registerViewFactory(
                    "$NAMESPACE/view",
                    HandTrackingViewFactory(registrar))
        }

        init { // Load all native libraries needed by the app.
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")
        }
    }

    private val activity: Activity = r.activity()
    private val methodChannel: MethodChannel = MethodChannel(r.messenger(), "$NAMESPACE/$id")
    private val eventChannel: EventChannel = EventChannel(r.messenger(), "$NAMESPACE/$id/landmarks")
    private var eventSink: EventChannel.EventSink? = null
    private val uiThreadHandler: Handler = Handler(Looper.getMainLooper())
    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private var previewFrameTexture: SurfaceTexture? = null
    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private var previewDisplayView: SurfaceView = SurfaceView(r.context())
    // Creates and manages an {@link EGLContext}.
    private var eglManager: EglManager = EglManager(null)
    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    private var processor: FrameProcessor = FrameProcessor(
            activity,
            eglManager.nativeContext,
            BINARY_GRAPH_NAME,
            INPUT_VIDEO_STREAM_NAME,
            OUTPUT_VIDEO_STREAM_NAME)
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private var converter: ExternalTextureConverter? = null
    // Handles camera access via the {@link CameraX} Jetpack support library.
    private var cameraHelper: CameraXPreviewHelper? = null

    private var isLoading = true;

    init {
        r.addRequestPermissionsResultListener(CameraRequestPermissionsListener())

        this.methodChannel.setMethodCallHandler(this)
        this.eventChannel.setStreamHandler(landMarksStreamHandler())
        setupPreviewDisplayView()
        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(activity)
        setupProcess()
        PermissionHelper.checkAndRequestCameraPermissions(activity)

        if (PermissionHelper.cameraPermissionsGranted(activity)) onResume()
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "getPlatformVersion") {
            result.success("Android ${android.os.Build.VERSION.RELEASE}")
        }
        else if (call.method == "isLoading") {
            result.success(isLoading)
        }
        else if (call.method == "close") {
            CameraX.unbindAll()
        }
        else
        {
            result.notImplemented()
        }
    }

    override fun getView(): SurfaceView? {
        return previewDisplayView
    }

    override fun dispose() {
        converter?.close()
    }

    private inner class CameraRequestPermissionsListener :
            PluginRegistry.RequestPermissionsResultListener {
        override fun onRequestPermissionsResult(requestCode: Int,
                                                permissions: Array<out String>?,
                                                grantResults: IntArray?): Boolean {
            return if (requestCode != 0) false
            else {
                for (result in grantResults!!) {
                    if (result == PERMISSION_GRANTED) onResume()
                    else Toast.makeText(activity, "请授予摄像头权限", Toast.LENGTH_LONG).show()
                }
                true
            }
        }

    }

    private fun onResume() {
        converter = ExternalTextureConverter(eglManager.context)
        converter!!.setFlipY(FLIP_FRAMES_VERTICALLY)
        converter!!.setConsumer(processor)
        if (PermissionHelper.cameraPermissionsGranted(activity)) {
            startCamera()
        }
    }

    private fun setupPreviewDisplayView() {
        previewDisplayView.visibility = View.GONE
        previewDisplayView.holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        processor.videoSurfaceOutput.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { // (Re-)Compute the ideal size of the camera-preview display (the area that the
                        // camera-preview frames get rendered onto, potentially with scaling and rotation)
                        // based on the size of the SurfaceView that contains the display.
                        val viewSize = Size(width, height)
                        val displaySize = cameraHelper!!.computeDisplaySizeFromViewSize(viewSize)
                        val isCameraRotated = cameraHelper!!.isCameraRotated
                        // Connect the converter to the camera-preview frames as its input (via
                        // previewFrameTexture), and configure the output width and height as the computed
                        // display size.
                        converter!!.setSurfaceTextureAndAttachToGLContext(
                                previewFrameTexture,
                               // if (isCameraRotated) displaySize.height else displaySize.width,
                               displaySize.height ,
                                //if (isCameraRotated) displaySize.width else displaySize.height)
                              displaySize.width
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        processor.videoSurfaceOutput.setSurface(null)
                    }
                })
    }

    private fun setupProcess() {
        processor.videoSurfaceOutput.setFlipY(FLIP_FRAMES_VERTICALLY)
        processor?.addPacketCallback(
                OUTPUT_DETECTIONS_STREAM_NAME
        ){ packet: Packet ->
            isLoading = false;
            val detections = PacketGetter.getProtoVector(packet,
                    DetectionProto.Detection.parser())
            if(detections == null){
                Log.d("OPFH", "detections null")
            }
            else{
                //Log.d("OPF", "detections not null 0: ${detections}")
                val scoreList = ArrayList<Float>()
                for(detection in detections)
                {
                    scoreList.add(detection.scoreList[0])

                }

                uiThreadHandler.post {
                    eventSink?.success(scoreList)}
                }
            }
        }


    private fun landMarksStreamHandler(): EventChannel.StreamHandler {
        return object : EventChannel.StreamHandler {

            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                eventSink = events
                // Log.e(TAG, "Listen Event Channel")
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        }
    }

    private fun startCamera() {
        CameraX.unbindAll()
        cameraHelper = CameraXPreviewHelper()
        cameraHelper!!.setOnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
            previewFrameTexture = surfaceTexture
            // Make the display view visible to start showing the preview. This triggers the
            // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
            previewDisplayView.visibility = View.VISIBLE
        }
        cameraHelper!!.startCamera(activity, CAMERA_FACING,  /*surfaceTexture=*/null)
    }
}
