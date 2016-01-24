package com.tristanwiley.laserqr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import com.hound.android.fd.HoundSearchResult;
import com.hound.android.fd.Houndify;
import com.hound.android.libphs.PhraseSpotterReader;
import com.hound.android.sdk.VoiceSearchInfo;
import com.hound.android.sdk.audio.SimpleAudioByteStreamSource;
import com.hound.core.model.sdk.CommandResult;
import com.hound.core.model.sdk.HoundResponse;
import com.koushikdutta.ion.Ion;
import com.tristanwiley.laserqr.camera.CameraSource;
import com.tristanwiley.laserqr.camera.CameraSourcePreview;
import com.tristanwiley.laserqr.camera.GraphicOverlay;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

/**
 * Open source from Google https://github.com/googlesamples/android-vision/blob/master/visionSamples/barcode-reader/app/src/main/java/com/google/android/gms/samples/vision/barcodereader/BarcodeCaptureActivity.java
 */
public class QRScanner extends AppCompatActivity{
    private static final String TAG = "Barcode-reader";

    private PhraseSpotterReader phraseSpotterReader;
    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    TextToSpeechMgr textToSpeechMgr;

    // intent request code to handle updating play services if needed.
    private static final int RC_HANDLE_GMS = 9001;

    // permission request codes need to be < 256
    private static final int RC_HANDLE_CAMERA_PERM = 2;

    // constants used to pass extra data in the intent
    public static final String AutoFocus = "AutoFocus";
    public static final String UseFlash = "UseFlash";
    public static final String BarcodeObject = "Barcode";

    private CameraSource mCameraSource;
    private CameraSourcePreview mPreview;
    private GraphicOverlay<BarcodeGraphic> mGraphicOverlay;

    // helper objects for detecting taps and pinches.
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    String id;
    /**
     * Initializes the UI and creates the detector pipeline.
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.barcode_capture);

        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay<BarcodeGraphic>) findViewById(R.id.graphicOverlay);
        // Normally you'd only have to do this once in your Application#onCreate
        Houndify.get(this).setClientId(Constants.CLIENT_ID);
        Houndify.get(this).setClientKey(Constants.CLIENT_KEY);
        Houndify.get(this).setRequestInfoFactory(StatefulRequestInfoFactory.get(this));

        Intent i = getIntent();
        id = i.getStringExtra("EXTRA_GAME");
        Log.d("QRS", id);

        textToSpeechMgr = new TextToSpeechMgr( this );

        // read parameters from the intent used to launch the activity.
        boolean autoFocus = true;
        boolean useFlash = false;

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource(autoFocus, useFlash);
        } else {
            requestCameraPermission();
        }

        gestureDetector = new GestureDetector(this, new CaptureGestureListener());
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

        Snackbar.make(mGraphicOverlay, "Tap to capture. Pinch/Stretch to zoom",
                Snackbar.LENGTH_LONG)
                .show();
    }

    /**
     * Handles the requesting of the camera permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.camera_permission,
                Snackbar.LENGTH_INDEFINITE)
                .setAction("Ok", listener)
                .show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean b = scaleGestureDetector.onTouchEvent(e);

        boolean c = gestureDetector.onTouchEvent(e);

        return b || c || super.onTouchEvent(e);
    }

    /**
     * Creates and starts the camera.  Note that this uses a higher resolution in comparison
     * to other detection examples to enable the barcode detector to detect small barcodes
     * at long distances.
     *
     * Suppressing InlinedApi since there is a check that the minimum version is met before using
     * the constant.
     */
    @SuppressLint("InlinedApi")
    @SuppressWarnings("deprecation")
    private void createCameraSource(boolean autoFocus, boolean useFlash) {
        Context context = getApplicationContext();

        // A barcode detector is created to track barcodes.  An associated multi-processor instance
        // is set to receive the barcode detection results, track the barcodes, and maintain
        // graphics for each barcode on screen.  The factory is used by the multi-processor to
        // create a separate tracker instance for each barcode.
        BarcodeDetector barcodeDetector = new BarcodeDetector.Builder(context).setBarcodeFormats(Barcode.QR_CODE).build();
        QRTrackerFactory barcodeFactory = new QRTrackerFactory(mGraphicOverlay);
        barcodeDetector.setProcessor(
                new MultiProcessor.Builder<>(barcodeFactory).build());

        if (!barcodeDetector.isOperational()) {
            // Note: The first time that an app using the barcode or face API is installed on a
            // device, GMS will download a native libraries to the device in order to do detection.
            // Usually this completes before the app is run for the first time.  But if that
            // download has not yet completed, then the above call will not detect any barcodes
            // and/or faces.
            //
            // isOperational() can be used to check if the required native libraries are currently
            // available.  The detectors will automatically become operational once the library
            // downloads complete on device.
            Log.w(TAG, "Detector dependencies are not yet available.");

            // Check for low storage.  If there is low storage, the native library will not be
            // downloaded, so detection will not become operational.
            IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowstorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this, R.string.low_storage, Toast.LENGTH_LONG).show();
                Log.w(TAG, getString(R.string.low_storage));
            }
        }

        // Creates and starts the camera.  Note that this uses a higher resolution in comparison
        // to other detection examples to enable the barcode detector to detect small barcodes
        // at long distances.
        CameraSource.Builder builder = new CameraSource.Builder(getApplicationContext(), barcodeDetector)
                .setFacing(CameraSource.CAMERA_FACING_BACK)
                .setRequestedPreviewSize(1600, 1024)
                .setRequestedFps(15.0f);

        // make sure that auto focus is an available option
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            builder = builder.setFocusMode(
                    autoFocus ? Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE : null);
        }

        mCameraSource = builder
                .setFlashMode(useFlash ? Camera.Parameters.FLASH_MODE_TORCH : null)
                .build();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();
        startCameraSource();
        startPhraseSpotting();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPreview != null) {
            mPreview.stop();
        }
        if ( phraseSpotterReader != null ) {
            stopPhraseSpotting();
        }

    }

    /**
     * Called to start the Phrase Spotter
     */
    private void startPhraseSpotting() {
        if ( phraseSpotterReader == null ) {
            phraseSpotterReader = new PhraseSpotterReader(new SimpleAudioByteStreamSource());
            phraseSpotterReader.setListener( phraseSpotterListener );
            phraseSpotterReader.start();
        }
    }



    /**
     * Called to stop the Phrase Spotter
     */
    private void stopPhraseSpotting() {
        if ( phraseSpotterReader != null ) {
            phraseSpotterReader.stop();
            phraseSpotterReader = null;
        }
    }

    /**
     * Implementation of the PhraseSpotterReader.Listener interface used to handle PhraseSpotter
     * call back.
     */
    private final PhraseSpotterReader.Listener phraseSpotterListener = new PhraseSpotterReader.Listener() {
        @Override
        public void onPhraseSpotted() {

            // It's important to note that when the phrase spotter detects "Ok Hound" it closes
            // the input stream it was provided.
            mainThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    Log.e("Phrase", "PhraseSpotted");
                    stopPhraseSpotting();
                    Log.e("Phrase", "Still good");
                    // Now start the HoundifyVoiceSearchActivity to begin the search.
                    Houndify.get(QRScanner.this).voiceSearch(QRScanner.this);
                }
            });
        }

        @Override
        public void onError(final Exception ex) {
            Log.d("QR onError", ex.getMessage());
            // for this sample we don't care about errors from the "Ok Hound" phrase spotter.

        }
    };

    /**
     * Releases the resources associated with the camera source, the associated detectors, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPreview != null) {
            mPreview.release();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source");
            // we have permission, so create the camerasource
            boolean autoFocus = getIntent().getBooleanExtra(AutoFocus,false);
            boolean useFlash = getIntent().getBooleanExtra(UseFlash, false);
            createCameraSource(autoFocus, useFlash);
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Multitracker sample")
                .setMessage(R.string.camera_permission_required)
                .setPositiveButton("Okay", listener)
                .show();
    }

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() throws SecurityException {
        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    /**
     * onTap is called to capture the oldest barcode currently detected and
     * return it to the caller.
     *
     * @param rawX - the raw position of the tap
     * @param rawY - the raw position of the tap.
     * @return true if the activity is ending.
     */
    private boolean onTap(float rawX, float rawY) {

        BarcodeGraphic graphic = mGraphicOverlay.getFirstGraphic();
        Barcode barcode = null;
        if (graphic != null) {
            barcode = graphic.getBarcode();
            if (barcode != null) {
                int score = barcode.getBoundingBox().width()/100;
                double fscore = Math.floor(score);
                Log.e("SCORE ROUNDED", "" + fscore);
                Ion.with(getApplicationContext())
                        .load("http://shootat.me/api/" + id + "/" + barcode.rawValue + "/" + fscore)
                        .asJsonObject();


            }
            else {
                Log.d(TAG, "barcode data is null");
            }
        }
        else {
            Log.d(TAG,"no barcode detected");
        }
        return barcode != null;
    }

    private class CaptureGestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {

            return onTap(e.getRawX(), e.getRawY()) || super.onSingleTapConfirmed(e);
        }
    }

    private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {

        /**
         * Responds to scaling events for a gesture in progress.
         * Reported by pointer motion.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         * @return Whether or not the detector should consider this event
         * as handled. If an event was not handled, the detector
         * will continue to accumulate movement until an event is
         * handled. This can be useful if an application, for example,
         * only wants to update scaling factors if the change is
         * greater than 0.01.
         */
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return false;
        }

        /**
         * Responds to the beginning of a scaling gesture. Reported by
         * new pointers going down.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         * @return Whether or not the detector should continue recognizing
         * this gesture. For example, if a gesture is beginning
         * with a focal point outside of a region where it makes
         * sense, onScaleBegin() may return false to ignore the
         * rest of the gesture.
         */
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        /**
         * Responds to the end of a scale gesture. Reported by existing
         * pointers going up.
         * <p/>
         * Once a scale has ended, {@link ScaleGestureDetector#getFocusX()}
         * and {@link ScaleGestureDetector#getFocusY()} will return focal point
         * of the pointers remaining on the screen.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         */
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            mCameraSource.doZoom(detector.getScaleFactor());
        }
    }


    /**
     * The HoundifyVoiceSearchActivity returns its result back to the calling Activity
     * using the Android's onActivityResult() mechanism.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == Houndify.REQUEST_CODE) {
            final HoundSearchResult result = Houndify.get(this).fromActivityResult(resultCode, data);

            if (result.hasResult()) {
                onResponse( result.getResponse() );
            }
            else if (result.getErrorType() != null) {
                onError(result.getException(), result.getErrorType());
            }
            else {
                Log.e("ErrorQR", ("Aborted search"));
            }
        }
    }

    /**
     * Called from onActivityResult() above
     *
     * @param response
     */
    private void onResponse(final HoundResponse response) {
        if (response.getResults().size() > 0) {
            // Required for conversational support
            StatefulRequestInfoFactory.get(this).setConversationState(response.getResults().get(0).getConversationState());

            Log.e("ErrorQR", ("Received response\n\n" + response.getResults().get(0).getWrittenResponse()));
            textToSpeechMgr.speak(response.getResults().get(0).getSpokenResponse());

            /**
             * "Client Match" demo code.
             *
             * Houndify client apps can specify their own custom phrases which they want matched using
             * the "Client Match" feature. This section of code demonstrates how to handle
             * a "Client Match phrase".  To enable this demo first open the
             * StatefulRequestInfoFactory.java file in this project and and uncomment the
             * "Client Match" demo code there.
             *
             * Example for parsing "Client Match"
             */
            if ( response.getResults().size() > 0 ) {
                CommandResult commandResult = response.getResults().get( 0 );
                if ( commandResult.getCommandKind().equals("ClientMatchCommand")) {
                    JsonNode matchedItemNode = commandResult.getJsonNode().findValue("MatchedItem");
                    String intentValue = matchedItemNode.findValue( "Intent").textValue();

                    if ( intentValue.equals("FIRE_ACTION") ) {
                        textToSpeechMgr.speak("Shot fired!");
                    }
//                    else if ( intentValue.equals("TURN_LIGHT_OFF") ) {
//                        textToSpeechMgr.speak("Client match TURN LIGHT OFF successful");
//                    }
                }
            }
        }
        else {
            Log.e("ErrorQR", ("Received empty response!"));
        }
    }

    /**
     * Called from onActivityResult() above
     *
     * @param ex
     * @param errorType
     */
    private void onError(final Exception ex, final VoiceSearchInfo.ErrorType errorType) {
        Log.e("ErrorQR", (errorType.name() + "\n\n" + exceptionToString(ex)));
    }

    private static String exceptionToString(final Exception ex) {
        try {
            final StringWriter sw = new StringWriter(1024);
            final PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            pw.close();
            return sw.toString();
        }
        catch (final Exception e) {
            return "";
        }
    }


    /**
     * Helper class used for managing the TextToSpeech engine
     */
    class TextToSpeechMgr implements TextToSpeech.OnInitListener {
        private TextToSpeech textToSpeech;

        public TextToSpeechMgr( Activity activity ) {
            textToSpeech = new TextToSpeech( activity, this );
        }

        @Override
        public void onInit( int status ) {
            // Set language to use for playing text
            if ( status == TextToSpeech.SUCCESS ) {
                int result = textToSpeech.setLanguage(Locale.US);
            }
        }

        /**
         * Play the text to the device speaker
         *
         * @param textToSpeak
         */
        public void speak( String textToSpeak ) {
            textToSpeech.speak(textToSpeak, TextToSpeech.QUEUE_ADD, null);
        }
    }
}
