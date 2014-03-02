/*
 * Copyright (C) 2011 doixanh@xda
 * This code has been modified. Portions copyright (C) 2014, OmniRom Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.media.AudioManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.content.Context;
import java.io.IOException;

/**
 * This listener uses camera to detect light around the phone.
 * Used for detecting low light environment to louder the ringer
 */
public final class CameraLightListener implements SurfaceHolder.Callback {

    private final static int WIDTH = 320;
    private final static int HEIGHT = 240;
    private static final String TAG = "CameraLight";
    private static final boolean DEBUG = true;

    private boolean mEnabled;
    private boolean mSurfaceReady;
    private boolean mPreviewRunning;
    private boolean mWaitingSurface;
    private int mFrameCount;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private Camera mCamera;
    private AudioManager mAudioManager;
    private boolean mMaximizedVolume = false;
    private boolean mRestoredVolume = false;
    private int mMaxVolume;
    private int mDesiredVolume;

    private PreviewCallback pcb = new PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] previewData, Camera c) {
            if (previewData != null) {
                long value = 0;
                mFrameCount++;
                for (int i = 0; i < WIDTH * HEIGHT - 1; i++) {
                    value += (long) previewData[i] & 0xFF;
                }
                value /= (WIDTH * HEIGHT);
                Log.i(TAG, "previewData gotten " + previewData.length + " bytes, #" + mFrameCount + ", val=" + value);
                setLightValue((int)value);
            } else {
                Log.i(TAG, "previewData is null");
            }
        }
    };

    public CameraLightListener(Context context, SurfaceView surfaceView) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
        mDesiredVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_RING);
        mSurfaceView = surfaceView;

        try {
            mSurfaceHolder = mSurfaceView.getHolder();
            mSurfaceHolder.addCallback(this);
            mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
            mSurfaceHolder.setFixedSize(1, 1);
        } catch (Exception e) {
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (DEBUG) Log.d(TAG, "surface changed");
        mSurfaceReady = true;
        // were we waiting for the surface?
        if (mWaitingSurface) {
            // yes, preview now
            startPreview();
        }
        mWaitingSurface = false;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (DEBUG) Log.d(TAG, "surface created");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (DEBUG) Log.d(TAG, "surface destroyed");
        stopPreview();
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    // start the preview to get light data
    private boolean startPreview() {
        if (!mSurfaceReady)    {
            // surface is not ready, we have to wait till it's ready
            if (DEBUG) Log.d(TAG, "surface is not ready, waiting for it...");
            mWaitingSurface = true;
            return false;
        }

        mFrameCount = 0;
        // open camera
        try {
            mCamera = Camera.open();
        } catch (Exception e) {
            return true;
        }
        if (mPreviewRunning) {
            mCamera.stopPreview();
        }

        // setup preview parameters
        Camera.Parameters p = mCamera.getParameters();
        p.setPreviewFrameRate(5);
        p.setPreviewSize(WIDTH, HEIGHT);
        mCamera.setParameters(p);
        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);
        } catch (IOException e) {
        }

        // and the callback
        mCamera.setPreviewCallback(pcb);

        // go!
        if (DEBUG) Log.d(TAG, "start preview");
        mCamera.startPreview();
        mPreviewRunning = true;   
        return true;
    }

    // stop light preview and release the camera
    private void stopPreview() {
        mMaximizedVolume = false;
        mRestoredVolume = false;
        if (mPreviewRunning) {
            try {
                // no more receiving preview...
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
                mCamera.release();
                if (DEBUG) Log.d(TAG, "stopped preview");
            } catch (Exception e) {
            }
            mPreviewRunning = false;
        }
    }

    public void enable(boolean enable) {
        if (enable) {
            mMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
            mDesiredVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_RING);
        }
        mEnabled = enable;
        if (DEBUG) Log.d(TAG, "enable(" + enable + ")");
        synchronized (this) {
            if (enable) {
                enable = startPreview();
            } else {
                stopPreview();
            }
        }
    }

    private void setLightValue(int value) {
        if (value <= 20) {
            maxVolume();
        } else {
            normalVolume();
        }
    }

    private void maxVolume() {
        // we are in dark condition
        // did we maximize the volume?
        if (!mMaximizedVolume) {
            // not yet, maximize it to call the user!
            Log.i(TAG, "Too dark, gotta maximize volume");
            mMaximizedVolume = true;
            mAudioManager.setStreamVolume(AudioManager.STREAM_RING, mMaxVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
        }
    }

    private void normalVolume() {
        // light enough? reduce to normal volume
        if (!mRestoredVolume) {
            Log.i(TAG, "Enough light, restoring volume");
            mRestoredVolume = true;
            mAudioManager.setStreamVolume(AudioManager.STREAM_RING, mDesiredVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE );
            // stop camera light also
            enable(false);
        }
    }
}
