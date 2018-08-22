package com.permobil.basicdigitalwatchface

import android.content.*
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v4.content.ContextCompat
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.ComplicationHelperActivity
import android.support.wearable.complications.SystemProviders
import android.support.wearable.complications.rendering.ComplicationDrawable
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.util.SparseArray
import android.view.SurfaceHolder
import android.view.WindowInsets
import android.widget.Toast
import com.permobil.basicdigitalwatchface.ComplicationConfigActivity.ComplicationLocation

import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.TimeZone

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
            when (complicationLocation) {
                ComplicationLocation.LEFT -> return LEFT_COMPLICATION_ID
                ComplicationLocation.RIGHT -> return RIGHT_COMPLICATION_ID
                else -> return -1
            }
        }

        fun getComplicationIds(): IntArray {
            return COMPLICATION_IDS
        }

        fun getSupportedComplicationTypes(
                complicationLocation: ComplicationLocation): IntArray {
            when (complicationLocation) {
                ComplicationLocation.LEFT -> return COMPLICATION_SUPPORTED_TYPES[0]
                ComplicationLocation.RIGHT -> return COMPLICATION_SUPPORTED_TYPES[1]
                else -> return intArrayOf()
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
        private var mYOffset: Float = 0F

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

            val resources = this@PermobilWatchFaceService.resources
            mYOffset = resources.getDimension(R.dimen.digital_y_offset)

            // Initializes background.
            mBackgroundPaint = Paint().apply {
                color = ContextCompat.getColor(applicationContext, R.color.background)
            }

            // Initializes Watch Face.
            mTextPaint = Paint().apply {
                typeface = NORMAL_TYPEFACE
                isAntiAlias = true
                color = ContextCompat.getColor(applicationContext, R.color.digital_text)
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

            // TODO: Step 2, ambient
            var complicationDrawable: ComplicationDrawable

            for (i in 0 until COMPLICATION_IDS.size) {
                complicationDrawable = mComplicationDrawableSparseArray.get(COMPLICATION_IDS[i])
                complicationDrawable.setInAmbientMode(mAmbient)
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP ->
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(applicationContext, R.string.message, Toast.LENGTH_SHORT)
                            .show()
            }
            invalidate()
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
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            // For most Wear devices, width and height are the same, so we just chose one (width).
            val sizeOfComplication = width / 4
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

            val leftComplicationDrawable = mComplicationDrawableSparseArray.get(LEFT_COMPLICATION_ID)
            leftComplicationDrawable.bounds = leftBounds

            val rightBounds =
            // Left, Top, Right, Bottom
                    Rect(
                            midpointOfScreen + horizontalOffset,
                            verticalOffset,
                            midpointOfScreen + horizontalOffset + sizeOfComplication,
                            verticalOffset + sizeOfComplication)

            val rightComplicationDrawable = mComplicationDrawableSparseArray.get(RIGHT_COMPLICATION_ID)
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
            this.mActiveComplicationDataSparseArray = SparseArray(COMPLICATION_IDS.size)
            val leftComplicationDrawable = getDrawable(R.drawable.custom_complication_styles) as ComplicationDrawable
            leftComplicationDrawable.setContext(applicationContext)
            val rightComplicationDrawable = getDrawable(R.drawable.custom_complication_styles) as ComplicationDrawable
            rightComplicationDrawable.setContext(applicationContext)
            mComplicationDrawableSparseArray = SparseArray(COMPLICATION_IDS.size)
            mComplicationDrawableSparseArray.put(LEFT_COMPLICATION_ID, leftComplicationDrawable)
            mComplicationDrawableSparseArray.put(RIGHT_COMPLICATION_ID, rightComplicationDrawable)
            setActiveComplications(0)
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
