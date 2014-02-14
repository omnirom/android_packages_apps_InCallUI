/*
 * Copyright (C) 2014 Xiao-Long Chen <chenxiaolong@cxl.epac.to>
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

package com.android.incallui.service;

import com.android.contacts.common.GeoUtil;
import com.android.dialer.calllog.ContactInfo;
import com.android.dialer.lookup.LookupSettings;
import com.android.dialer.lookup.ReverseLookup;
import com.android.dialer.service.CachedNumberLookupServiceImpl;

import com.google.common.base.Objects;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Provides phone number lookup services.
 */
public class PhoneNumberServiceImpl implements PhoneNumberService {
    private static final String TAG = "PhoneNumberServiceImpl";

    // Debug mode disables caching so lookups are performed every time
    private static final boolean DEBUG = false;

    private static final CachedNumberLookupServiceImpl mCachedNumberLookupService =
            new CachedNumberLookupServiceImpl();
    private Context mContext;
    private String mCountryIso;
    private ExecutorService mImageExecutorService = Executors.newFixedThreadPool(2);
    private ExecutorService mLookupExecutorService = Executors.newFixedThreadPool(2);
    private ReverseLookup mReverseLookup;

    private static final int EVENT_NUMBER_INFO_COMPLETE = 1;
    private static final int EVENT_IMAGE_FETCH_COMPLETE = 2;

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_NUMBER_INFO_COMPLETE:
                Pair pair = (Pair)msg.obj;
                ((NumberLookupListener) pair.first)
                        .onPhoneNumberInfoComplete(
                                (PhoneNumberInfo) pair.second);
                break;

            case EVENT_IMAGE_FETCH_COMPLETE:
                Pair pair2 = (Pair)msg.obj;
                ((ImageLookupListener) pair2.first)
                        .onImageFetchComplete((Bitmap) pair2.second);
                break;

            default:
                super.handleMessage(msg);
                break;
            }
        }
    };

    public PhoneNumberServiceImpl(Context context) {
        mReverseLookup = ReverseLookup.getInstance(context);

        mContext = context;
        mCountryIso = ((TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE)).getSimCountryIso().toUpperCase();
    }

    /**
     * Lookup phone number info asynchronously. Calls the image lookup thread
     * if the server provides a contact photo.
     */
    private class LookupRunnable implements Runnable {
        private final ImageLookupListener mImageListener;
        private final boolean mIsIncoming;
        private final NumberLookupListener mListener;
        private final String mPhoneNumber;

        public LookupRunnable(String phoneNumber,
                NumberLookupListener listener,
                ImageLookupListener imageListener,
                boolean isIncoming) {
            mPhoneNumber = phoneNumber;
            mListener = listener;
            mImageListener = imageListener;
            mIsIncoming = isIncoming;
        }

        /**
         * Perform phone number lookup
         *
         * @param normalizedNumber The phone number to lookup
         * @return A Pair with the phone number info and optional data to pass
         * to the image lookup method
         */
        private Pair<PhoneNumberInfoImpl, Object> doLookup(
                String normalizedNumber) {
            if (!LookupSettings.isReverseLookupEnabled(mContext)) {
                return null;
            }

            String formattedNumber = PhoneNumberUtils.formatNumber(mPhoneNumber,
                    normalizedNumber, GeoUtil.getCurrentCountryIso(mContext));

            Pair<PhoneNumberInfoImpl, Object> p;

            p = mReverseLookup.lookupNumber(mContext, normalizedNumber,
                    formattedNumber, mIsIncoming);

            if (p == null) {
                return null;
            }

            PhoneNumberInfoImpl numberInfo = p.first;
            Object data = p.second;

            if (mCachedNumberLookupService != null && numberInfo != null
                    && numberInfo.getDisplayName() != null) {
                ContactInfo info = new ContactInfo();

                info.normalizedNumber = normalizedNumber;
                info.number = numberInfo.getNumber();
                if (info.number == null) {
                    info.number = formattedNumber;
                }

                info.name = numberInfo.getDisplayName();
                info.type = numberInfo.getPhoneType();
                info.label = numberInfo.getPhoneLabel();

                String imageUrl = numberInfo.getImageUrl();
                info.photoUri = imageUrl != null ? Uri.parse(imageUrl) : null;

                CachedNumberLookupServiceImpl.CachedContactInfoImpl cachedContactInfo =
                        mCachedNumberLookupService.buildCachedContactInfo(info);
                cachedContactInfo.setLookupSource(numberInfo.isBusiness());
                cachedContactInfo.setLookupKey(numberInfo.getLookupKey());

                mCachedNumberLookupService.addContact(mContext, cachedContactInfo);
            }

            return Pair.create(numberInfo, data);
        }

        @Override
        public void run() {
            try {
                String number = PhoneNumberUtils.formatNumberToE164(
                        mPhoneNumber, mCountryIso);

                if (DEBUG) Log.d(TAG, "raw number: " + mPhoneNumber +
                        ", formatted e164: " + number);

                if (number == null) {
                    Log.d(TAG, "Could not format phone number to E-164. " +
                            "Skipping lookup.");
                    return;
                }

                PhoneNumberInfoImpl numberInfo = null;
                boolean isLocalSearchWithoutPhotoUri = false;

                if (!DEBUG && mCachedNumberLookupService != null) {
                    CachedNumberLookupServiceImpl.CachedContactInfoImpl cachedContact =
                            mCachedNumberLookupService.lookupCachedContactFromNumber(
                                    mContext, number);

                    if (cachedContact != null) {
                        ContactInfo contactInfo = cachedContact.getContactInfo();

                        if (contactInfo != null
                                && contactInfo != ContactInfo.EMPTY) {
                            String photoUri;
                            if (contactInfo.photoUri != null) {
                                photoUri = contactInfo.photoUri.toString();
                            } else {
                                photoUri = null;
                            }

                            numberInfo = new PhoneNumberInfoImpl(
                                    contactInfo.name,
                                    contactInfo.normalizedNumber,
                                    contactInfo.number,
                                    contactInfo.type,
                                    contactInfo.label,
                                    photoUri, null,
                                    CachedNumberLookupServiceImpl.CachedContactInfoImpl
                                            .isBusiness(contactInfo.sourceType));

                            if (cachedContact.getSourceType() ==
                                    CachedNumberLookupServiceImpl.SOURCE_EXTENDED) {
                                isLocalSearchWithoutPhotoUri = true;
                            } else {
                                isLocalSearchWithoutPhotoUri = false;
                            }
                        }
                    }
                }

                // Lookup contact if it's not cached
                Object data = null;
                if (isLocalSearchWithoutPhotoUri || numberInfo == null) {
                    Pair<PhoneNumberInfoImpl, Object> results =
                            doLookup(number);

                    if (results == null) {
                        return;
                    }

                    numberInfo = results.first;
                    data = results.second;
                }

                if (numberInfo == null
                        || numberInfo.getDisplayName() == null
                        || numberInfo.getImageUrl() == null) {
                    Log.d(TAG, "Contact lookup. Remote contact found, no image.");
                } else {
                    Log.d(TAG, "Contact lookup. Remote contact found, loading image.");
                    mImageExecutorService.execute(new ImageLookupRunnable(
                        numberInfo.getNormalizedNumber(),
                        numberInfo.getImageUrl(), data, mImageListener));
                }

                mHandler.obtainMessage(EVENT_NUMBER_INFO_COMPLETE,
                        Pair.create(mListener, numberInfo))
                        .sendToTarget();
            } catch (Exception e) {
                Log.e(TAG, "Failed to lookup phone number.", e);
                return;
            }
        }
    }

    /**
     * Fetch an image asynchronously, adding it to the cache if necessary.
     */
    private class ImageLookupRunnable implements Runnable {
        private ImageLookupListener mListener;
        private String mNumber;
        private String mUrl;
        private Object mData;

        private ImageLookupRunnable(String number, String url, Object data,
                ImageLookupListener listener) {
            mNumber = number;
            mUrl = url;
            mListener = listener;
            mData = data;
        }

        @Override
        public void run() {
            Bitmap bitmap = null;

            try {
                Uri uri = Uri.parse(mUrl);
                String scheme = uri.getScheme();

                // Contains the actual image data
                byte[] imageData;

                boolean isRemoteImage = false;

                if (scheme.equals("https") || scheme.equals("http")) {
                    imageData = mReverseLookup.lookupImage(
                            mContext, mUrl, mData);
                    isRemoteImage = true;
                } else if (scheme.equals(ContentResolver.SCHEME_CONTENT)
                        || scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) {
                    imageData = loadPhotoFromContentUri(mContext, uri);
                } else {
                    Log.e(TAG, scheme + " scheme not supported for image lookups.");
                    imageData = null;
                }

                if (imageData != null) {
                    // Add image to cache if it isn't there already
                    if (mCachedNumberLookupService != null
                            && mNumber != null
                            && (isRemoteImage
                            || !mCachedNumberLookupService.isCacheUri(mUrl))) {
                        mCachedNumberLookupService.addPhoto(
                                mContext, mNumber, imageData);
                    }

                    bitmap = BitmapFactory.decodeByteArray(
                            imageData, 0, imageData.length);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching image.", e);
            }

            mHandler.obtainMessage(EVENT_IMAGE_FETCH_COMPLETE,
                    Pair.create(mListener, bitmap)).sendToTarget();
        }
    }

    /**
     * Perform a reverse number lookup asynchronously.
     *
     * @param phoneNumber The phone number to lookup
     * @param listener The listener to notify when the phone number lookup is complete
     * @param imageListener The listener to notify when the image lookup is complete
     * @param isIncoming Whether the call is incoming or outgoing
     */
    @Override
    public void getPhoneNumberInfo(String phoneNumber,
            NumberLookupListener listener, ImageLookupListener imageListener,
            boolean isIncoming) {
        try {
            mLookupExecutorService.execute(new LookupRunnable(
                    phoneNumber, listener, imageListener, isIncoming));
        } catch (Exception e) {
            Log.e(TAG, "Google reverse phone number lookup failed.", e);
        }
    }

    /**
     * Load a photo with a maximum size of 16384 bytes from a content URI.
     *
     * @param context The application context
     * @param uri The content URI
     * @return The byte array containing the photo data
     */
    private static byte[] loadPhotoFromContentUri(Context context, Uri uri)
            throws IOException {
        AssetFileDescriptor descriptor =
              context.getContentResolver().openAssetFileDescriptor(uri, "r");

        if (descriptor == null) {
            return null;
        }

        FileInputStream in = descriptor.createInputStream();
        if (in == null) {
            descriptor.close();
            return null;
        }

        byte[] array = new byte[16384];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            while (true) {
                int read = in.read(array);
                if (read == -1) {
                    break;
                }
                out.write(array, 0, read);
            }
        } finally {
            in.close();
            descriptor.close();
        }

        return out.toByteArray();
    }

    public static class PhoneNumberInfoImpl implements PhoneNumberInfo {
        private String mDisplayName;
        private String mImageUrl;
        private String mLabel;
        private String mLookupKey;
        private String mNormalizedNumber;
        private String mNumber;
        private int mType;
        private boolean mIsBusiness;

        public PhoneNumberInfoImpl(String name, String normalizedNumber,
                String number, int type, String label, String imageUrl,
                String lookupKey, boolean isBusiness) {
            mDisplayName = name;
            mNormalizedNumber = normalizedNumber;
            mNumber = number;
            mType = type;
            mLabel = label;
            mImageUrl = imageUrl;
            mLookupKey = lookupKey;
            mIsBusiness = isBusiness;
        }

        @Override
        public String getDisplayName() {
            return mDisplayName;
        }

        @Override
        public String getImageUrl() {
            return mImageUrl;
        }

        public String getLookupKey() {
            return mLookupKey;
        }

        @Override
        public String getNormalizedNumber() {
            return mNormalizedNumber;
        }

        @Override
        public String getNumber() {
            return mNumber;
        }

        @Override
        public String getPhoneLabel() {
            return mLabel;
        }

        @Override
        public int getPhoneType() {
            return mType;
        }

        @Override
        public boolean isBusiness() {
            return mIsBusiness;
        }

        public String toString() {
            return Objects.toStringHelper(this)
                    .add("mDisplayName", mDisplayName)
                    .add("mImageUrl", mImageUrl)
                    .add("mNormalizedNumber", mNormalizedNumber)
                    .toString();
        }
    }
}
