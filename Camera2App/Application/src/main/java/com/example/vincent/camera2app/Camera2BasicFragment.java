/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.vincent.camera2app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2BasicFragment extends Fragment implements View.OnClickListener {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = Camera2BasicFragment.class.getSimpleName();

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;
    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;
    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Camera state: Update the ISO and exposure parameters to initialize the manual control.
     */
    private static final int STATE_WAITING_UPDATE_CAMERA_CHARACTERISTICS = 5;


    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */

    private CameraCaptureSession mCaptureSession;
    /**
     * A reference to the opened {@link CameraDevice}.
     */

    private CameraDevice mCameraDevice;
    /**
     * The {@link android.util.Size} of camera preview.
     */

    private Size mPreviewSize;

    private boolean mInManualMode = false;
    //    private Range<Integer> mISORange;
    private int mCurrentISO = 0;
    //    private Range<Long> mExposureRange;
    private long mCurrentExposure = 0;
    private int[] mPossibleISOs = {100, 200, 300, 400, 600, 800, 1000, 1600, 2000, 3200, 4000, 6400, 8000, 10000}; // tailored for Nexus 5
    //TODO get this from the camera characteristics!

    private long[] mPossibleExposures = {1000000000 / 75000, 1000000000 / 30000, 1000000000 / 20000,
            1000000000 / 10000, 1000000000 / 8000, 1000000000 / 6000, 1000000000 / 5000, 1000000000 / 4000, 1000000000 / 3000,
            1000000000 / 2000, 1000000000 / 1500, 1000000000 / 1000, 1000000000 / 750, 1000000000 / 500,
            1000000000 / 250, 1000000000 / 125, 1000000000 / 100, 1000000000 / 60, 1000000000 / 30,
            1000000000 / 15, 1000000000 / 8, 1000000000 / 6, 1000000000 / 4, 1000000000 / 2, new Double(1000000000 / 1.2).longValue()}; // tailored for Nexus 5
    //TODO get this from the camera characteristics! see android.sensor.info.sensitivityrange

    private int mBurstSize = 5;
    private int mNbrPicturesTaken = 0;
    private boolean mBurstCompleted = false;
    private boolean mNeedToWaitForCameraSettings = false;

    enum SETTING_ACTION_INTENT {INCREASE_ISO, DECREASE_ISO, INCREASE_EXP, DECREASE_EXP};
    SETTING_ACTION_INTENT mSettingActionIntent = null;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
//            manualUpdateCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    /**
     * This is the output file for our picture.
     */
    private File mFile;

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile));
        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    int afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {

                        //Here in this IF statement, focus has been locked
                        //Vincent:
                        if(mInManualMode){
                            //no need for precapture sequence in manual mode
                            mState = STATE_PICTURE_TAKEN;
                            captureBurst();
                            break;
                        }

                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        //adapted by Vincent
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {

                            mState = STATE_WAITING_NON_PRECAPTURE;
                            //TODO understand why this transition is made! It sometimes make two bursts to be taken.
                            //unlockfocus will set the sate to STATE_PREVIEW anyway...
//                            captureStillPicture();
                            captureBurst();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        //the precapture sequence has been launched
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        //precapture sequence has ended!
                        mState = STATE_PICTURE_TAKEN;
//                        captureStillPicture();
                        captureBurst();
                    }
                    break;
                }
                case STATE_WAITING_UPDATE_CAMERA_CHARACTERISTICS: {
                    // added by Vincent

                    Integer temp1 = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if(temp1!=null){
                        mCurrentISO = temp1.intValue();
                    }
                    Long temp2 = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    if(temp2!=null){
                        mCurrentExposure = temp2.longValue();
                    }

                   if(mCurrentISO != 0 && mCurrentExposure != 0) {

                       //finish manual change of settings
                       mState = STATE_PREVIEW;
                       finishManualSettingChange();
                   }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * A {@link Handler} for showing {@link Toast}s.
     */
    private Handler mMessageHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Activity activity = getActivity();
            if (activity != null) {
                Toast.makeText(activity, (String) msg.obj, Toast.LENGTH_SHORT).show();
            }
        }
    };

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(String text) {
        // We show a Toast by sending request message to mMessageHandler. This makes sure that the
        // Toast is shown on the UI thread.
        Message message = Message.obtain();
        message.obj = text;
        mMessageHandler.sendMessage(message);
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static Camera2BasicFragment newInstance() {
        Camera2BasicFragment fragment = new Camera2BasicFragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        view.findViewById(R.id.exposure_minus).setOnClickListener(this);
        view.findViewById(R.id.exposure_plus).setOnClickListener(this);
        view.findViewById(R.id.iso_minus).setOnClickListener(this);
        view.findViewById(R.id.iso_plus).setOnClickListener(this);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
//        mFile = new File(getActivity().getExternalFilesDir(null), "pic.jpg");
        mFile = new File(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "MyCoolCameraApp"), "pic.jpg");
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                if (characteristics.get(CameraCharacteristics.LENS_FACING)
                        == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        width, height, largest);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            new ErrorDialog().show(getFragmentManager(), "dialog");
        }
    }

    /**
     * Opens the camera specified by {@link Camera2BasicFragment#mCameraId}.
     */
    private void openCamera(int width, int height) {
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                //Vincent: this is only if we want to start the app in automatic mode
                                //(which makes sense). When in manual mode, we call manualUpdate(...)
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    /**
     * Initiate a still image capture.
     */
    private void takePicture() {
        lockFocus();
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //Vincent: This should be called only when on automatic mode for now.

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when we
     * get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

//    /**
//     * Capture a still picture. This method should be called when we get a response in
//     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
//     */
//    private void captureStillPicture() {
//        try {
//            final Activity activity = getActivity();
//            if (null == activity || null == mCameraDevice) {
//                return;
//            }
//            // This is the CaptureRequest.Builder that we use to take a picture.
//            final CaptureRequest.Builder captureBuilder =
//                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
//            captureBuilder.addTarget(mImageReader.getSurface());
//
//            // Use the same AE and AF modes as the preview.
//            //Adapted by vincent
//            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//            if (!mInManualMode) {
//                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
//            } else {
//                beginUpdateLastUsedCameraCharacteristics();
//                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
//                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mCurrentExposure);
//                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, mCurrentISO);
//
//            }
//
//            // Orientation
//            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
//            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
//
//            CameraCaptureSession.CaptureCallback captureCallback
//                    = new CameraCaptureSession.CaptureCallback() {
//
//                @Override
//                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
//                                               TotalCaptureResult result) {
//                    updatemFile();
//                    showToast("Saved: " + mFile);
//                    unlockFocus();
//                }
//            };
//
//            mCaptureSession.stopRepeating();
//            mCaptureSession.capture(captureBuilder.build(), captureCallback, null);
////            mCaptureSession.captureBurst(getBurstRequestsList(captureBuilder), captureCallback, null);
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }
//    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is finished.
     */
    private void unlockFocus() {
        try {
            // Reset the autofocus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                takePicture();
                break;
            }
//            case R.id.info: {
//                Activity activity = getActivity();
//                if (null != activity) {
//                    new AlertDialog.Builder(activity)
//                            .setMessage(R.string.intro_message)
//                            .setPositiveButton(android.R.string.ok, null)
//                            .show();
//                }
//                break;
//            }
            case R.id.exposure_minus: {
                decreaseExposure();
                break;
            }
            case R.id.exposure_plus: {
                increaseExposure();
                break;
            }
            case R.id.iso_minus: {
                decreaseISO();
                break;
            }
            case R.id.iso_plus: {
                increaseISO();
                break;
            }
        }
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mFile;

        public ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    public static class ErrorDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage("This device doesn't support Camera2 API.")
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }


    //=============ADDED BY VINCENT===============
    public void decreaseExposure() {
        mInManualMode = true;
        mSettingActionIntent = SETTING_ACTION_INTENT.DECREASE_EXP;

        updateMNeedToWaitForCameraSettings();
        if(mNeedToWaitForCameraSettings){
            beginUpdateLastUsedCameraCharacteristics();
        }
        else{
            finishManualSettingChange();
        }



//        //find position is value range:
//        int length = mPossibleExposures.length;
//        int index = 0;
//        for (int i = 0; i < length; i++) {
//            if (mCurrentExposure == mPossibleExposures[i]) {
//                index = i;
//                break;
//            }
//        }
//        //check if already at the shortest exposure
//        if (index == 0) {
//            return;
//        }
//
//        mCurrentExposure = mPossibleExposures[index - 1];
//        //TODO: use a Message to toast the new exp.
//        manualUpdateCameraPreviewSession();


//        mISORange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
//        if(mISORange == null) {
//            Log.e(TAG, "ISO range was NULL !");
//            return;
//        }
//        int max1 = range2.getUpper();//10000
//        int min1 = range2.getLower();//100
//        int iso = ((progress * (max1 - min1)) / 100 + min1);
//        mPreviewBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
    }

    public void increaseExposure() {
        mInManualMode = true;

        mSettingActionIntent = SETTING_ACTION_INTENT.INCREASE_EXP;

        updateMNeedToWaitForCameraSettings();
        if(mNeedToWaitForCameraSettings){
            beginUpdateLastUsedCameraCharacteristics();
        }
        else{
            finishManualSettingChange();
        }
//        beginUpdateLastUsedCameraCharacteristics();
//
//        //find position is value range:
//        int length = mPossibleExposures.length;
//        int index = 0;
//        for (int i = 0; i < length; i++) {
//            if (mCurrentExposure == mPossibleExposures[i]) {
//                index = i;
//                break;
//            }
//        }
//        //check if already at the shortest exposure
//        if (index == length - 1) {
//            return;
//        }
//
//        mCurrentExposure = mPossibleExposures[index + 1];
//
//
//        manualUpdateCameraPreviewSession();
    }

    public void decreaseISO() {
        mInManualMode = true;
        mSettingActionIntent = SETTING_ACTION_INTENT.DECREASE_ISO;

        updateMNeedToWaitForCameraSettings();
        if(mNeedToWaitForCameraSettings){
            beginUpdateLastUsedCameraCharacteristics();
        }
        else{
            finishManualSettingChange();
        }

//        beginUpdateLastUsedCameraCharacteristics();
//
//        //find position is value range:
//        int length = mPossibleISOs.length;
//        int index = 0;
//        for (int i = 0; i < length; i++) {
//            if (mCurrentISO == mPossibleISOs[i]) {
//                index = i;
//                break;
//            }
//        }
//        //check if already at the shortest exposure
//        if (index == 0) {
//            return;
//        }
//
//        mCurrentISO = mPossibleISOs[index - 1];
//        manualUpdateCameraPreviewSession();
    }

    public void increaseISO() {
        mInManualMode = true;
        mSettingActionIntent = SETTING_ACTION_INTENT.INCREASE_ISO;

        updateMNeedToWaitForCameraSettings();
        if(mNeedToWaitForCameraSettings){
            beginUpdateLastUsedCameraCharacteristics();
        }
        else{
            finishManualSettingChange();
        }

//        beginUpdateLastUsedCameraCharacteristics();
//
//        //find position is value range:
//        int length = mPossibleISOs.length;
//        int index = 0;
//        for (int i = 0; i < length; i++) {
//            if (mCurrentISO == mPossibleISOs[i]) {
//                index = i;
//                break;
//            }
//        }
//        //check if already at the shortest exposure
//        if (index == length - 1) {
//            return;
//        }
//
//        mCurrentISO = mPossibleISOs[index + 1];
//        manualUpdateCameraPreviewSession();
    }

    /**
     *
     * @return boolean indicator of whether we have to wait for the camera to return the AE measured settings
     */
    private void beginUpdateLastUsedCameraCharacteristics() {

//        updateMNeedToWaitForCameraSettings();
//        if(mNeedToWaitForCameraSettings) {
//            //parameters are not initialized yet

            //safety check. Should always return true
            if (mState == STATE_PREVIEW) {
                mState = STATE_WAITING_UPDATE_CAMERA_CHARACTERISTICS;
                //end of update in the capture callback
            } else {
                Log.e(TAG, "Something went wrong while trying to retrieve camera settings! mState != preview but iso and exp were not initialized");
                //set default values
                mCurrentExposure = mPossibleExposures[mPossibleExposures.length / 2];
                mCurrentISO = mPossibleISOs[mPossibleISOs.length / 2];
                finishManualSettingChange();
            }
//        }
//        else{
//            manualUpdateCameraPreviewSession();
//        }
//        mCurrentISO = mPreviewRequest.get(CaptureRequest.SENSOR_SENSITIVITY);
//        mCurrentExposure = mPreviewRequest.get(CaptureRequest.SENSOR_EXPOSURE_TIME);

    }

    private void updateMNeedToWaitForCameraSettings(){
        if(mCurrentISO != 0 && mCurrentExposure != 0){
            mNeedToWaitForCameraSettings = false; //parameters are already set to a custom value. Nothing to do.
        } else{
            mNeedToWaitForCameraSettings = true;
        }
    }

    private void finishManualSettingChange(){
        switch (mSettingActionIntent){
            case DECREASE_EXP: {

                //find position is value range:
                int length = mPossibleExposures.length;
                int index = 0;
                for (int i = 0; i < length-1; i++) {
                    if (mCurrentExposure >= mPossibleExposures[i] && mCurrentExposure < mPossibleExposures[i+1]) {
                        index = i;
                        break;
                    }
                }
                if(mCurrentExposure ==  mPossibleExposures[length-1]){
                    index = length-1;
                }

                //check if already at the shortest exposure
                if (index == 0) {
                    return;
                }

                mCurrentExposure = mPossibleExposures[index - 1];
                //TODO: use a Message to toast the new exp.
                break;
            }
            case INCREASE_EXP: {

                //find position is value range:
                int length = mPossibleExposures.length;
                int index = 0;
                for (int i = 0; i < length-1; i++) {
                    if (mCurrentExposure >= mPossibleExposures[i] && mCurrentExposure < mPossibleExposures[i+1]) {
                        index = i;
                        break;
                    }
                }
                //check if already at the longest exposure
                if(mCurrentExposure ==  mPossibleExposures[length-1]){
                    index = length-1;
                    return;
                }


                mCurrentExposure = mPossibleExposures[index + 1];
                break;
            }
            case DECREASE_ISO: {

                //find position is value range:
                int length = mPossibleISOs.length;
                int index = 0;
                for (int i = 0; i < length-1; i++) {
                    if (mCurrentISO >= mPossibleISOs[i] && mCurrentISO < mPossibleISOs[i+1]) {
                        index = i;
                        break;
                    }
                }
                if(mCurrentISO ==  mPossibleISOs[length-1]){
                    index = length-1;
                }

                //check if already at the shortest exposure
                if (index == 0) {
                    return;
                }

                mCurrentISO = mPossibleISOs[index - 1];
                break;
            }
            case INCREASE_ISO: {

                //find position is value range:
                int length = mPossibleISOs.length;
                int index = 0;
                for (int i = 0; i < length-1; i++) {
                    if (mCurrentISO >= mPossibleISOs[i] && mCurrentISO < mPossibleISOs[i+1]) {
                        index = i;
                        break;
                    }
                }
                //check if already at the highest sensitivity
                if(mCurrentISO ==  mPossibleISOs[length-1]){
                    index = length-1;
                    return;
                }

                mCurrentISO = mPossibleISOs[index + 1];
                break;
            }
        }

        manualUpdateCameraPreviewSession();
    }

    private void manualUpdateCameraPreviewSession() {

        if (!mInManualMode) {
            Log.e(TAG, "asked for a manual preview while not in manual mode");
        }

        try {
            // Auto focus should be continuous for camera preview.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            //manual control:
            //TODO: set frame duration?

           if(mCurrentExposure == 0 || mCurrentISO == 0){
               Log.e(TAG, "Something went wrong while trying to set the exposure and ISO settings!");
               return;
           }

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mCurrentExposure);
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, mCurrentISO);

            // Finally, we start displaying the camera preview.
            mPreviewRequest = mPreviewRequestBuilder.build();
            mCaptureSession.setRepeatingRequest(mPreviewRequest,
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private File selectFile() {
//TODO optimize this method
        File file = null;
        String filename = "pic_";
        String fileExt = ".jpg";
        int seqNbr = 0;
        boolean stop = false;

        while (!stop && seqNbr < 10000) {
            file = new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "MyCoolCameraApp"), filename + seqNbr + fileExt);

            if (!file.exists()) {
                stop = true;
            }
            seqNbr++;
        }

        return file;
    }

    private void updatemFile() {
        mFile = selectFile();
    }

    private List<CaptureRequest> getBurstRequestsList(CaptureRequest.Builder builder) {
        List<CaptureRequest> list = new ArrayList<CaptureRequest>();

        for (int i = 0; i < mBurstSize; i++) {
            list.add(builder.build());
        }

        return list;
    }

    /**
     * Adapted by Vincent
     * Capture a burst of picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private void captureBurst() {
        Log.d(TAG,"going to take a burst of pictures");
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            //Adapted by vincent
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (!mInManualMode) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            } else {
//                beginUpdateLastUsedCameraCharacteristics();
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mCurrentExposure);
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, mCurrentISO);

            }

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            CameraCaptureSession.CaptureCallback captureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    mNbrPicturesTaken++;
                    if(mNbrPicturesTaken >= mBurstSize) {
                        mBurstCompleted = true;
                        mNbrPicturesTaken = 0; //reinitialize counter
                        Log.d(TAG,"all pictures in burst were taken");
                        showToast("Capture completed");
                    }

                    updatemFile();
//                    showToast("Saved: " + mFile);

                    if(mBurstCompleted) {
                        unlockFocus();
                    }
                }
            };

            mCaptureSession.stopRepeating();
//            mCaptureSession.capture(captureBuilder.build(), captureCallback, null);
            mBurstCompleted = false;
            mCaptureSession.captureBurst(getBurstRequestsList(captureBuilder), captureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


}
