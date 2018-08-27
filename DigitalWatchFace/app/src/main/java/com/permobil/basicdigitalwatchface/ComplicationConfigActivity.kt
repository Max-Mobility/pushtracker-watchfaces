package com.permobil.basicdigitalwatchface

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.support.wearable.complications.ComplicationHelperActivity
import android.support.wearable.complications.ComplicationProviderInfo
import android.support.wearable.complications.ProviderChooserIntent
import android.support.wearable.complications.ProviderInfoRetriever
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView

import java.util.concurrent.Executors

/**
 * The watch-side config activity for [PermobilWatchFaceService], which allows for setting
 * the left and right complications of watch face.
 */
class ComplicationConfigActivity : Activity(), View.OnClickListener {

    companion object {

        private const val TAG = "ConfigActivity"

        internal const val COMPLICATION_CONFIG_REQUEST_CODE = 1001
    }

    private var mLeftComplicationId: Int = 0
    private var mRightComplicationId: Int = 0

    // Selected complication id by user.
    private var mSelectedComplicationId: Int = 0

    // ComponentName used to identify a specific service that renders the watch face.
    private var mWatchFaceComponentName: ComponentName? = null

    // Required to retrieve complication data from watch face for preview.
    private var mProviderInfoRetriever: ProviderInfoRetriever? = null

    private var mLeftComplicationBackground: ImageView? = null
    private var mRightComplicationBackground: ImageView? = null

    private var mLeftComplication: ImageButton? = null
    private var mRightComplication: ImageButton? = null

    private var mDefaultAddComplicationDrawable: Drawable? = null

    /**
     * Used by associated watch face ([PermobilWatchFaceService]) to let this
     * configuration Activity know which complication locations are supported, their ids, and
     * supported complication data types.
     */
    enum class ComplicationLocation {
        LEFT,
        RIGHT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_config)

        mDefaultAddComplicationDrawable = getDrawable(R.drawable.add_complication)

        mSelectedComplicationId = -1

        mLeftComplicationId = PermobilWatchFaceService.getComplicationId(ComplicationLocation.LEFT)
        mRightComplicationId = PermobilWatchFaceService.getComplicationId(ComplicationLocation.RIGHT)

        mWatchFaceComponentName = ComponentName(applicationContext, PermobilWatchFaceService::class.java)

        // Sets up left complication preview.
        mLeftComplicationBackground = findViewById(R.id.left_complication_background)
        mLeftComplication = findViewById(R.id.left_complication)
        mLeftComplication!!.setOnClickListener(this)

        // Sets default as "Add Complication" icon.
        mLeftComplication!!.setImageDrawable(mDefaultAddComplicationDrawable)
        mLeftComplicationBackground!!.visibility = View.INVISIBLE

        // Sets up right complication preview.
        mRightComplicationBackground = findViewById(R.id.right_complication_background)
        mRightComplication = findViewById(R.id.right_complication)
        mRightComplication!!.setOnClickListener(this)

        // Sets default as "Add Complication" icon.
        mRightComplication!!.setImageDrawable(mDefaultAddComplicationDrawable)
        mRightComplicationBackground!!.visibility = View.INVISIBLE

        mProviderInfoRetriever = ProviderInfoRetriever(applicationContext, Executors.newCachedThreadPool())
        mProviderInfoRetriever!!.init()

        retrieveInitialComplicationsData()
    }

    override fun onDestroy() {
        super.onDestroy()
        mProviderInfoRetriever!!.release()
    }

    private fun retrieveInitialComplicationsData() {

        val complicationIds = PermobilWatchFaceService.getComplicationIds()

        mProviderInfoRetriever!!.retrieveProviderInfo(
                object : ProviderInfoRetriever.OnProviderInfoReceivedCallback() {
                    override fun onProviderInfoReceived(
                            watchFaceComplicationId: Int,
                            complicationProviderInfo: ComplicationProviderInfo?) {

                        Log.d(TAG, "onProviderInfoReceived: $complicationProviderInfo")

                        updateComplicationViews(watchFaceComplicationId, complicationProviderInfo)
                    }
                },
                mWatchFaceComponentName,
                *complicationIds)
    }

    override fun onClick(view: View) {
        if (view == mLeftComplication) {
            Log.i(TAG, "Left Complication click()")
            this.launchComplicationHelperActivity(ComplicationLocation.LEFT)

        } else if (view == mRightComplication) {
            Log.i(TAG, "Right Complication click()")
            this.launchComplicationHelperActivity(ComplicationLocation.RIGHT)
        }
    }

    // Verifies the watch face supports the complication location, then launches the helper
    // class, so user can choose their complication data provider.
    // TODO: Step 3, launch data selector
    private fun launchComplicationHelperActivity(complicationLocation: ComplicationLocation) {

        mSelectedComplicationId = PermobilWatchFaceService.getComplicationId(complicationLocation)

        if (mSelectedComplicationId >= 0) {

            val supportedTypes = PermobilWatchFaceService.getSupportedComplicationTypes(
                    complicationLocation)

            startActivityForResult(
                    ComplicationHelperActivity.createProviderChooserHelperIntent(
                            applicationContext,
                            mWatchFaceComponentName,
                            mSelectedComplicationId,
                            *supportedTypes),
                    ComplicationConfigActivity.COMPLICATION_CONFIG_REQUEST_CODE)

        } else {
            Log.d(TAG, "Complication not supported by watch face.")
        }
    }


    fun updateComplicationViews(
            watchFaceComplicationId: Int, complicationProviderInfo: ComplicationProviderInfo?) {
        Log.d(TAG, "updateComplicationViews(): id: $watchFaceComplicationId")
        Log.d(TAG, "\tinfo: $complicationProviderInfo")

        if (watchFaceComplicationId == mLeftComplicationId) {
            if (complicationProviderInfo != null) {
                mLeftComplication!!.setImageIcon(complicationProviderInfo.providerIcon)
                mLeftComplicationBackground!!.visibility = View.VISIBLE
            } else {
                mLeftComplication!!.setImageDrawable(mDefaultAddComplicationDrawable)
                mLeftComplicationBackground!!.visibility = View.INVISIBLE
            }

        } else if (watchFaceComplicationId == mRightComplicationId) {
            if (complicationProviderInfo != null) {
                mRightComplication!!.setImageIcon(complicationProviderInfo.providerIcon)
                mRightComplicationBackground!!.visibility = View.VISIBLE
            } else {
                mRightComplication!!.setImageDrawable(mDefaultAddComplicationDrawable)
                mRightComplicationBackground!!.visibility = View.INVISIBLE
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (requestCode == COMPLICATION_CONFIG_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            // Retrieves information for selected Complication provider.
            val complicationProviderInfo = data.getParcelableExtra<ComplicationProviderInfo>(ProviderChooserIntent.EXTRA_PROVIDER_INFO)
            Log.d(TAG, "Provider: $complicationProviderInfo")

            if (mSelectedComplicationId >= 0) {
                updateComplicationViews(mSelectedComplicationId, complicationProviderInfo)
            }
        }

    }


}
