package com.android.incallui;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.services.telephony.common.CallIdentification;
import com.android.services.telephony.common.Call;

/**
 * Handles the call card activity that pops up when a call
 * arrives
 */
public class InCallCardActivity extends Activity {
    private static final int SLIDE_IN_DURATION_MS = 500;

    private TextView mNameTextView;
    private ImageView mContactImage;

    private Call mCall;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        setContentView(R.layout.card_call_incoming);

        InCallPresenter.getInstance().setCardActivity(this);

        final CallList calls = CallList.getInstance();
        mCall = calls.getIncomingCall();

        CallIdentification identification = mCall.getIdentification();

        // Setup the fields to show the information of the call
        mNameTextView = (TextView) findViewById(R.id.txt_contact_name);
        mContactImage = (ImageView) findViewById(R.id.img_contact);

        // Setup the call button
        Button answer = (Button) findViewById(R.id.btn_answer);
        answer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, false);
                CallCommandClient.getInstance().answerCall(mCall.getCallId());
                finish();
            }
        });

        Button reject = (Button) findViewById(R.id.btn_reject);
        reject.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallCommandClient.getInstance().rejectCall(mCall, false, null);
                finish();
            }
        });

        // Slide in the dialog
        final LinearLayout vg = (LinearLayout) findViewById(R.id.root);

        vg.setTranslationY(getResources().getDimensionPixelSize(R.dimen.incoming_call_card_height));
        vg.animate().translationY(0.0f).setDuration(SLIDE_IN_DURATION_MS)
            .setInterpolator(new DecelerateInterpolator()).start();

        // Lookup contact info
        startContactInfoSearch(identification);
    }

    @Override
    public void onBackPressed() {
        final Call call = CallList.getInstance().getIncomingCall();
        if (call != null) {
            InCallPresenter.getInstance().startIncomingCallUi(InCallPresenter.InCallState.INCALL, true);
        }
        super.onBackPressed();
    }

    /**
     * Starts a query for more contact data for the save primary and secondary calls.
     */
    private void startContactInfoSearch(final CallIdentification identification) {
        final ContactInfoCache cache = ContactInfoCache.getInstance(InCallCardActivity.this);

        cache.findInfo(identification, true, new ContactInfoCacheCallback() {
                @Override
                public void onContactInfoComplete(int callId, ContactCacheEntry entry) {
                    mNameTextView.setText(entry.name == null ? entry.number : entry.name);
                    if (entry.personUri != null) {
                        CallerInfoUtils.sendViewNotification(InCallCardActivity.this, entry.personUri);
                    }
                }

                @Override
                public void onImageLoadComplete(int callId, ContactCacheEntry entry) {
                    if (entry.photo != null) {
                        Drawable current = mContactImage.getDrawable();
                        AnimationUtils.startCrossFade(mContactImage, current, entry.photo);
                    }
                }
            });
    }

}

