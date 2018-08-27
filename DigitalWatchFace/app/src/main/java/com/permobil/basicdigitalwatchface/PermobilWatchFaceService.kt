package com.permobil.basicdigitalwatchface

import android.app.PendingIntent
import android.content.*
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v4.content.ContextCompat
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.ComplicationHelperActivity
import android.support.wearable.complications.rendering.ComplicationDrawable
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.util.SparseArray
import android.view.SurfaceHolder
import android.view.WindowInsets
import com.permobil.basicdigitalwatchface.ComplicationConfigActivity.ComplicationLocation
import java.lang.ref.WeakReference
import java.util.*


/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 *
 *
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
class PermobilWatchFaceService : CanvasWatchFaceService() {

    companion object {

        // BRAD - working on complication data exposure to watch face
        private const val TAG = "PermobilDigitalWatchFace"

        private const val LEFT_COMPLICATION_ID = 0
        private const val RIGHT_COMPLICATION_ID = 1

        private val COMPLICATION_IDS = intArrayOf(LEFT_COMPLICATION_ID, RIGHT_COMPLICATION_ID)
        // Left and right dial supported types.
        private val COMPLICATION_SUPPORTED_TYPES = arrayOf(intArrayOf(ComplicationData.TYPE_RANGED_VALUE, ComplicationData.TYPE_ICON, ComplicationData.TYPE_SHORT_TEXT, ComplicationData.TYPE_SMALL_IMAGE), intArrayOf(ComplicationData.TYPE_RANGED_VALUE, ComplicationData.TYPE_ICON, ComplicationData.TYPE_SHORT_TEXT, ComplicationData.TYPE_SMALL_IMAGE))


        private val NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

        /**
         * Updates rate in milliseconds for interactive mode. We update once a second since seconds
         * are displayed in interactive mode.
         */
        private const val INTERACTIVE_UPDATE_RATE_MS = 1000

        /**
         * Handler message id for updating the time periodically in interactive mode.
         */
        private const val MSG_UPDATE_TIME = 0

        // Used by {@link ComplicationConfigActivity} to retrieve id for complication locations and
        // to check if complication location is supported.
        fun getComplicationId(
                complicationLocation: ComplicationLocation): Int {
            return when (complicationLocation) {
                ComplicationLocation.LEFT -> LEFT_COMPLICATION_ID
                ComplicationLocation.RIGHT -> RIGHT_COMPLICATION_ID
            }
        }

        fun getComplicationIds(): IntArray {
            return COMPLICATION_IDS
        }

        fun getSupportedComplicationTypes(
                complicationLocation: ComplicationLocation): IntArray {
            return when (complicationLocation) {
                ComplicationLocation.LEFT -> COMPLICATION_SUPPORTED_TYPES[0]
                ComplicationLocation.RIGHT -> COMPLICATION_SUPPORTED_TYPES[1]
            }
        }
    }

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: PermobilWatchFaceService.Engine) : Handler() {
        private val mWeakReference: WeakReference<PermobilWatchFaceService.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var mCalendar: Calendar

        private var mRegisteredTimeZoneReceiver = false

        private var mXOffset: Float = 0F
//        private var mYOffset: Float = 0F

        private lateinit var mBackgroundPaint: Paint
        private lateinit var mTextPaint: Paint

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false
        private var mAmbient: Boolean = false

        /* Maps active complication ids to the data for that complication. Note: Data will only be
         * present if the user has chosen a provider via the settings activity for the watch face.
         */
        private var mActiveComplicationDataSparseArray: SparseArray<ComplicationData> = SparseArray()
        /* Maps complication ids to corresponding ComplicationDrawable that renders the
         * the complication data on the watch face.
         */
        private var mComplicationDrawableSparseArray: SparseArray<ComplicationDrawable> = SparseArray()

        private val mUpdateTimeHandler: Handler = EngineHandler(this)

        private val mTimeZoneReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(WatchFaceStyle.Builder(this@PermobilWatchFaceService)
                    .setAcceptsTapEvents(true)
                    .build())

            mCalendar = Calendar.getInstance()

//            val resources = this@PermobilWatchFaceService.resources
//            mYOffset = resources.getDimension(R.dimen.digital_y_offset)

            // Initializes background.
            mBackgroundPaint = Paint().apply {
                color = ContextCompat.getColor(applicationContext, R.color.background)
            }

            // Initializes Watch Face.
            mTextPaint = Paint().apply {
                typeface = NORMAL_TYPEFACE
                isAntiAlias = true
                color = ContextCompat.getColor(applicationContext, R.color.digital_text)
                textAlign = Paint.Align.CENTER
            }

            initializeComplications()
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                    WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
            mBurnInProtection = properties.getBoolean(
                    WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false)
        }

        override fun onComplicationDataUpdate(
                complicationId: Int, complicationData: ComplicationData) {
            Log.d(TAG, "onComplicationDataUpdate() id: $complicationId")
            // Adds/updates active complication data in the array.
            mActiveComplicationDataSparseArray.put(complicationId, complicationData)
            // Updates correct ComplicationDrawable with updated data.
            val complicationDrawable = mComplicationDrawableSparseArray.get(complicationId)
            complicationDrawable.setComplicationData(complicationData)
            invalidate()
        }


        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            Log.d(TAG, "OnTapCommand()")
            // TODO: Step 5, OnTapCommand()
            when (tapType) {
                TAP_TYPE_TAP -> {
                    val tappedComplicationId: Int = getTappedComplicationId(x, y)
                    if (tappedComplicationId != -1) {
                        onComplicationTap(tappedComplicationId)
                    }
                }
            }
        }

        private fun onComplicationTap(complicationId: Int) {
            Log.i(TAG, "onComplicationTap()")

            val complicationData = this.mActiveComplicationDataSparseArray.get(complicationId)

            if (complicationData == null) {
                Log.d(TAG, "No PendingIntent for complication $complicationId.")
                return
            }

            if (complicationData.tapAction != null) {
                try {
                    complicationData.tapAction.send()
                } catch (e: PendingIntent.CanceledException) {
                    Log.e(TAG, "onComplicationTap() tap action error $e")
                }
            } else if (complicationData.type == ComplicationData.TYPE_NO_PERMISSION) {
                // watch face does not have permission to receive complication data so launch permission request
                val componentName = ComponentName(applicationContext, PermobilWatchFaceService::class.java)
                val permissionRequestIntent = ComplicationHelperActivity.createPermissionRequestHelperIntent(applicationContext, componentName)

                startActivity(permissionRequestIntent)
            }
        }

        private fun getTappedComplicationId(x: Int, y: Int): Int {
            var complicationId: Int?
            var complicationData: ComplicationData
            var complicationDrawable: ComplicationDrawable

            val currentTimeMillis = System.currentTimeMillis()


            for (i in 0 until COMPLICATION_IDS.size) {
                complicationId = COMPLICATION_IDS[i]
                Log.i(TAG, "complicationId $complicationId")
                try {
                    complicationData = mActiveComplicationDataSparseArray[complicationId]
                    Log.i(TAG, "getTappedComplicationId $complicationData")

                    if (complicationData != null
                            && complicationData.isActive(currentTimeMillis)
                            && complicationData.type != ComplicationData.TYPE_NOT_CONFIGURED
                            && complicationData.type != ComplicationData.TYPE_EMPTY) {

                        complicationDrawable = mComplicationDrawableSparseArray[complicationId]
                        val complicationBoundingRect = complicationDrawable.bounds

                        if (complicationBoundingRect.width() > 0) {
                            if (complicationBoundingRect.contains(x, y)) {
                                return complicationId
                            }
                        } else {
                            Log.e(TAG, "Not a recognized complication id.")
                        }
                    }

                } catch (e: IllegalStateException) {
                    Log.e(TAG, "IllegalStateException in COMPLICATION_IDS loop $e")
                }

            }
            return -1
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            if (mLowBitAmbient) {
                mTextPaint.isAntiAlias = !inAmbientMode
            }

            var complicationDrawable: ComplicationDrawable

            for (i in 0 until COMPLICATION_IDS.size) {
                complicationDrawable = mComplicationDrawableSparseArray[COMPLICATION_IDS[i]]
                complicationDrawable.setInAmbientMode(mAmbient)
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }
        
        override fun onDraw(canvas: Canvas, bounds: Rect) {
            // Draw the background.
            if (mAmbient) {
                canvas.drawColor(Color.BLACK)
            } else {
                canvas.drawRect(
                        0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), mBackgroundPaint)
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now

            val text = if (mAmbient)
                String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE))
            else
                String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND))

            val xPos = (canvas.width / 2).toFloat()
            val yPos = (canvas.height / 2 - (mTextPaint.descent() + mTextPaint.ascent()) / 2)
            //((textPaint.descent() + textPaint.ascent()) / 2) is the distance from the baseline to the center.
            //            canvas.drawText(text, mXOffset, mYOffset, mTextPaint)
            canvas.drawText(text, xPos, yPos, mTextPaint)

            drawComplications(canvas, now)
        }

        private fun drawComplications(canvas: Canvas, currentTimeMillis: Long) {
            var complicationId: Int
            var complicationDrawable: ComplicationDrawable

            for (i in 0 until COMPLICATION_IDS.size) {
                complicationId = COMPLICATION_IDS[i]
                complicationDrawable = mComplicationDrawableSparseArray[complicationId]

                complicationDrawable.draw(canvas, currentTimeMillis)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            // For most Wear devices, width and height are the same, so we just chose one (width).
            val sizeOfComplication = width / 5
            val midpointOfScreen = width / 2

            val horizontalOffset = (midpointOfScreen - sizeOfComplication) / 2
            val verticalOffset = midpointOfScreen - sizeOfComplication / 2

            val leftBounds =
            // Left, Top, Right, Bottom
                    Rect(
                            horizontalOffset,
                            verticalOffset,
                            horizontalOffset + sizeOfComplication,
                            verticalOffset + sizeOfComplication)

            val leftComplicationDrawable = mComplicationDrawableSparseArray[LEFT_COMPLICATION_ID]
            leftComplicationDrawable.bounds = leftBounds

            val rightBounds =
            // Left, Top, Right, Bottom
                    Rect(
                            midpointOfScreen + horizontalOffset,
                            verticalOffset,
                            midpointOfScreen + horizontalOffset + sizeOfComplication,
                            verticalOffset + sizeOfComplication)


            val rightComplicationDrawable = mComplicationDrawableSparseArray[RIGHT_COMPLICATION_ID]
            rightComplicationDrawable.bounds = rightBounds
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()

                // Update time zone in case it changed while we weren't visible.
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        private fun initializeComplications() {
            Log.d(TAG, "initializeComplications()")
            mActiveComplicationDataSparseArray = SparseArray(COMPLICATION_IDS.size)
            val leftComplicationDrawable = getDrawable(R.drawable.custom_complication_styles) as ComplicationDrawable
            leftComplicationDrawable.setContext(applicationContext)
            val rightComplicationDrawable = getDrawable(R.drawable.custom_complication_styles) as ComplicationDrawable
            rightComplicationDrawable.setContext(applicationContext)
            mComplicationDrawableSparseArray = SparseArray(COMPLICATION_IDS.size)
            mComplicationDrawableSparseArray.put(LEFT_COMPLICATION_ID, leftComplicationDrawable)
            mComplicationDrawableSparseArray.put(RIGHT_COMPLICATION_ID, rightComplicationDrawable)
            setActiveComplications(*COMPLICATION_IDS)
        }


        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@PermobilWatchFaceService.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@PermobilWatchFaceService.unregisterReceiver(mTimeZoneReceiver)
        }

        override fun onApplyWindowInsets(insets: WindowInsets) {
            super.onApplyWindowInsets(insets)

            // Load resources that have alternate values for round watches.
            val resources = this@PermobilWatchFaceService.resources
            val isRound = insets.isRound
            mXOffset = resources.getDimension(
                    if (isRound)
                        R.dimen.digital_x_offset_round
                    else
                        R.dimen.digital_x_offset
            )

            val textSize = resources.getDimension(
                    if (isRound)
                        R.dimen.digital_text_size_round
                    else
                        R.dimen.digital_text_size
            )

            mTextPaint.textSize = textSize
        }

        /**
         * Starts the [.mUpdateTimeHandler] timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !isInAmbientMode
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }
    }
}
